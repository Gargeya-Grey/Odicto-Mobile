import Fastify from 'fastify';
import cors from '@fastify/cors';
import multipart from '@fastify/multipart';
import {
  contextResetResponseSchema,
  dictationMetadataSchema,
  installationRequestSchema,
  providerTestRequestSchema,
} from '@odicto/contracts';
import type { ApiConfig } from './config.js';
import { detectAudio } from './audio.js';
import {
  InstallationAuth,
  MemoryInstallationStore,
  type InstallationStore,
} from './auth.js';
import { refine, transcribe } from './providers.js';
import { registerLive } from './live.js';

export function createApp(
  config: ApiConfig,
  store: InstallationStore = new MemoryInstallationStore(),
) {
  const app = Fastify({
    logger: {
      redact: [
        'req.headers.authorization',
        'req.headers.x-provider-key',
        'req.body',
      ],
    },
    bodyLimit: config.MAX_AUDIO_BYTES + 64_000,
    requestIdHeader: 'x-request-id',
  });
  const auth = new InstallationAuth(config.TOKEN_SIGNING_SECRET, store);
  registerLive(app, config, auth, store);
  app.register(cors, { origin: config.corsOrigins });
  app.register(multipart, {
    limits: { files: 1, fileSize: config.MAX_AUDIO_BYTES, fields: 2 },
  });
  app.get('/health/live', async () => ({ status: 'ok' }));
  app.get('/health/ready', async () => ({ status: 'ready' }));
  app.post('/v1/installations', async (request, reply) => {
    const parsed = installationRequestSchema.safeParse(request.body);
    if (!parsed.success)
      return reply
        .code(400)
        .send(
          error('invalid_request', 'Invalid installation request', request.id),
        );
    return auth.issue(
      parsed.data.platform,
      parsed.data.appVersion,
      parsed.data.refreshToken,
    );
  });

  async function identity(request: { headers: Record<string, unknown> }) {
    const value = request.headers.authorization;
    if (typeof value !== 'string' || !value.startsWith('Bearer '))
      throw new Error('Unauthorized');
    return auth.verify(value.slice(7));
  }
  app.get('/v1/usage', async (request, reply) => {
    try {
      const id = await identity(request);
      const used = await store.getUsage(id.installationId);
      const start = new Date();
      start.setUTCHours(0, 0, 0, 0);
      const end = new Date(start);
      end.setUTCDate(end.getUTCDate() + 30);
      return {
        tier: id.tier,
        periodStartsAt: start.toISOString(),
        periodEndsAt: end.toISOString(),
        audioSecondsLimit: config.FREE_AUDIO_SECONDS,
        audioSecondsUsed: used,
        audioSecondsReserved: 0,
      };
    } catch {
      return reply
        .code(401)
        .send(
          error(
            'unauthorized',
            'Installation token is invalid or expired',
            request.id,
          ),
        );
    }
  });
  app.post('/v1/context/reset', async (request, reply) => {
    try {
      await identity(request);
      return contextResetResponseSchema.parse({
        ok: true,
        requestId: request.id,
      });
    } catch {
      return reply
        .code(401)
        .send(
          error(
            'unauthorized',
            'Installation token is invalid or expired',
            request.id,
          ),
        );
    }
  });
  app.post('/v1/providers/test', async (request, reply) => {
    try {
      await identity(request);
      const parsed = providerTestRequestSchema.safeParse(request.body);
      if (!parsed.success)
        return reply
          .code(400)
          .send(error('invalid_request', 'Invalid provider test', request.id));
      if (parsed.data.provider === 'managed')
        return {
          ok: Boolean(config.GROQ_API_KEY),
          provider: 'managed',
          model: config.GROQ_MODEL,
          requestId: request.id,
        };
      await refine(
        config,
        'Return OK',
        'verbatim',
        [],
        parsed.data.credential,
        parsed.data.provider,
      );
      return {
        ok: true,
        provider: parsed.data.provider,
        model: parsed.data.model,
        requestId: request.id,
      };
    } catch {
      return reply
        .code(400)
        .send(
          error(
            'provider_test_failed',
            'Provider connection failed',
            request.id,
          ),
        );
    }
  });

  app.post('/v1/dictations', async (request, reply) => {
    let id;
    try {
      id = await identity(request);
    } catch {
      return reply
        .code(401)
        .send(
          error(
            'unauthorized',
            'Installation token is invalid or expired',
            request.id,
          ),
        );
    }
    const parts = request.parts();
    let audio: Buffer | undefined;
    let metadataText = '';
    let providerKey: string | undefined;
    for await (const part of parts) {
      if (part.type === 'file' && part.fieldname === 'audio')
        audio = await part.toBuffer();
      else if (part.type === 'field' && part.fieldname === 'metadata')
        metadataText = String(part.value);
      else if (part.type === 'field' && part.fieldname === 'providerKey')
        providerKey = String(part.value);
    }
    if (!audio || !metadataText)
      return reply
        .code(400)
        .send(
          error(
            'invalid_multipart',
            'Audio and metadata are required',
            request.id,
          ),
        );
    const format = detectAudio(audio);
    if (!format)
      return reply
        .code(415)
        .send(
          error('unsupported_audio', 'Unsupported audio container', request.id),
        );
    let metadataJson: unknown;
    try {
      metadataJson = JSON.parse(metadataText);
    } catch {
      return reply
        .code(400)
        .send(
          error('invalid_metadata', 'Metadata must be valid JSON', request.id),
        );
    }
    const parsed = dictationMetadataSchema.safeParse(metadataJson);
    if (!parsed.success)
      return reply
        .code(400)
        .send(
          error('invalid_metadata', 'Invalid dictation metadata', request.id),
        );
    const estimatedSeconds = Math.max(1, Math.ceil(audio.length / 4000));
    const reservation = await store.reserve(
      id.installationId,
      parsed.data.operationId,
      estimatedSeconds,
      config.FREE_AUDIO_SECONDS,
    );
    if (reservation === 'duplicate')
      return reply
        .code(409)
        .send(
          error(
            'duplicate_operation',
            'Operation was already submitted',
            request.id,
          ),
        );
    if (reservation === 'exhausted')
      return reply
        .code(429)
        .send(
          error(
            'quota_exhausted',
            'Managed dictation quota is exhausted',
            request.id,
          ),
        );
    try {
      const stt = await transcribe(
        config,
        audio,
        `audio.${format.extension}`,
        format.mimeType,
        parsed.data.language,
      );
      let result = { text: stt.text, provider: 'groq', model: stt.model };
      let refined = false;
      if (parsed.data.mode === 'refine') {
        try {
          result = await refine(
            config,
            stt.text,
            parsed.data.filter,
            parsed.data.context,
            providerKey,
            parsed.data.provider,
          );
          refined = result.text !== stt.text;
        } catch {}
      }
      await store.finalize(id.installationId, parsed.data.operationId, {
        seconds: stt.duration || estimatedSeconds,
        provider: result.provider,
        model: result.model,
      });
      return {
        operationId: parsed.data.operationId,
        transcript: stt.text,
        text: result.text,
        refined,
        provider: result.provider,
        model: result.model,
        usage: {
          audioSeconds: stt.duration || estimatedSeconds,
          inputTokens: 0,
          outputTokens: 0,
        },
        requestId: request.id,
      };
    } catch {
      await store.finalize(id.installationId, parsed.data.operationId, {
        seconds: estimatedSeconds,
        provider: 'groq',
        model: config.GROQ_MODEL,
        failure: 'provider_error',
      });
      return reply
        .code(502)
        .send(
          error(
            'provider_unavailable',
            'Dictation provider is unavailable',
            request.id,
            true,
          ),
        );
    }
  });
  return app;
}

function error(
  code: string,
  message: string,
  requestId: string,
  retryable = false,
) {
  return { error: { code, message, requestId, retryable } };
}
