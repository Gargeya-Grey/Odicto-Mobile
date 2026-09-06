import websocket from '@fastify/websocket';
import WebSocket from 'ws';
import type { FastifyInstance } from 'fastify';
import {
  liveClientMessageSchema,
  type LiveServerMessage,
} from '@odicto/contracts';
import type { ApiConfig } from './config.js';
import type { InstallationAuth, InstallationStore } from './auth.js';
import { transcribe } from './providers.js';

const answerPrompt =
  'You are a voice assistant whose answer is inserted at the user’s cursor. Execute the spoken instruction: answer, draft, transform, or decide. Do not echo or polish the request. Return only the requested result in concise, plain human-readable text. Do not invent facts. No surrounding commentary. You have no access to the surrounding app or its text.';

export async function answer(
  config: ApiConfig,
  text: string,
  provider: string,
  model?: string,
  credential?: string,
  signal?: AbortSignal,
) {
  const openrouter = provider === 'openrouter';
  const key =
    credential ||
    (openrouter ? config.OPENROUTER_API_KEY : config.GEMINI_API_KEY);
  const selectedModel =
    model || (openrouter ? config.OPENROUTER_MODEL : config.GEMINI_MODEL);
  if (!key || !selectedModel) throw new Error('AI provider unavailable');
  const response = await fetch(
    openrouter
      ? `${config.OPENROUTER_API_BASE.replace(/\/$/, '')}/chat/completions`
      : `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(selectedModel)}:generateContent`,
    {
      method: 'POST',
      headers: openrouter
        ? { 'Content-Type': 'application/json', Authorization: `Bearer ${key}` }
        : { 'Content-Type': 'application/json', 'x-goog-api-key': key },
      signal: signal
        ? AbortSignal.any([signal, AbortSignal.timeout(30_000)])
        : AbortSignal.timeout(30_000),
      body: JSON.stringify(
        openrouter
          ? {
              model: selectedModel,
              messages: [
                { role: 'system', content: answerPrompt },
                { role: 'user', content: text },
              ],
              max_tokens: 2048,
            }
          : {
              systemInstruction: { parts: [{ text: answerPrompt }] },
              contents: [{ role: 'user', parts: [{ text }] }],
              generationConfig: { maxOutputTokens: 2048 },
            },
      ),
    },
  );
  if (!response.ok) throw new Error('AI provider unavailable');
  const data = (await response.json()) as {
    choices?: Array<{ message?: { content?: string } }>;
    candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
  };
  const result = openrouter
    ? data.choices?.[0]?.message?.content
    : data.candidates?.[0]?.content?.parts?.map((p) => p.text || '').join('');
  if (!result?.trim()) throw new Error('Empty AI response');
  return result.trim();
}

export function registerLive(
  app: FastifyInstance,
  config: ApiConfig,
  auth: InstallationAuth,
  store: InstallationStore,
) {
  app.register(websocket, { options: { maxPayload: 32_768 } });
  app.register(async (scope) => {
    scope.get(
      '/v1/dictations/live',
      {
        websocket: true,
        preValidation: async (request, reply) => {
          try {
            await auth.verify(
              (request.headers.authorization || '').replace(/^Bearer /, ''),
            );
          } catch {
            return reply.code(401).send({ error: 'Unauthorized' });
          }
        },
      },
      (socket, request) => {
        let upstream: WebSocket | undefined;
        let start:
          | Extract<
              ReturnType<typeof liveClientMessageSchema.parse>,
              { type: 'start' }
            >
          | undefined;
        let installationId = '';
        let phase:
          | 'new'
          | 'connecting'
          | 'recording'
          | 'finishing'
          | 'answering'
          | 'closed' = 'new';
        let reserved = false;
        let settled = false;
        let bytes = 0;
        let chunks: Buffer[] = [];
        const managed = () => start?.provider === 'managed';
        let transcript = '';
        let serial = Promise.resolve();
        let pendingBytes = 0;
        const beganAt = Date.now();
        const controller = new AbortController();
        let timer = setTimeout(
          () => fail('timeout', 'Voice connection timed out'),
          10_000,
        );
        const send = (message: LiveServerMessage) => {
          if (socket.readyState === WebSocket.OPEN)
            socket.send(JSON.stringify(message));
        };
        const settle = async (failure?: string) => {
          if (!reserved || settled || !start) return;
          settled = true;
          await store.finalize(installationId, start.operationId, {
            seconds: bytes / 32_000,
            provider: managed() ? 'groq' : 'gemini',
            model: managed()
              ? config.GROQ_MODEL
              : config.GEMINI_TRANSCRIBE_LIVE_MODEL,
          });
          request.log.info(
            {
              requestId: request.id,
              status: failure || 'completed',
              latencyMs: Date.now() - beganAt,
              provider: managed() ? 'groq' : 'gemini',
              model: managed()
                ? config.GROQ_MODEL
                : config.GEMINI_TRANSCRIBE_LIVE_MODEL,
              audioSeconds: bytes / 32_000,
            },
            'Live request finished',
          );
        };
        const close = async (failure?: string) => {
          phase = 'closed';
          clearTimeout(timer);
          controller.abort();
          chunks = [];
          upstream?.close();
          socket.close();
          try {
            await settle(failure);
          } catch {
            request.log.error(
              { requestId: request.id, status: 'settlement_failed' },
              'Live quota settlement failed',
            );
          }
        };
        function fail(code: string, message: string) {
          if (phase === 'closed') return;
          send({ type: 'error', code, message });
          void close(code);
        }
        const deadline = (ms: number) => {
          clearTimeout(timer);
          timer = setTimeout(
            () => fail('timeout', 'Voice request timed out. Try again.'),
            ms,
          );
        };
        const complete = async () => {
          if (phase !== 'finishing' || !start) return;
          phase = 'answering';
          deadline(managed() ? 48_000 : 32_000);
          upstream?.close();
          if (managed() && bytes > 0) {
            try {
              const pcm = Buffer.concat(chunks);
              chunks = [];
              const header = Buffer.alloc(44);
              header.write('RIFF');
              header.writeUInt32LE(36 + pcm.length, 4);
              header.write('WAVEfmt ', 8);
              header.writeUInt32LE(16, 16);
              header.writeUInt16LE(1, 20);
              header.writeUInt16LE(1, 22);
              header.writeUInt32LE(16000, 24);
              header.writeUInt32LE(32000, 28);
              header.writeUInt16LE(2, 32);
              header.writeUInt16LE(16, 34);
              header.write('data', 36);
              header.writeUInt32LE(pcm.length, 40);
              const result = await transcribe(
                config,
                Buffer.concat([header, pcm]),
                'recording.wav',
                'audio/wav',
                'auto',
                controller.signal,
              );
              if (controller.signal.aborted) return;
              transcript = result.text;
              send({ type: 'final', text: transcript });
            } catch {
              fail(
                'provider_error',
                'Groq transcription failed. Check the backend key and connection.',
              );
              return;
            }
          }
          if (!transcript.trim()) {
            fail('no_speech', 'No speech heard. Try again.');
            return;
          }
          try {
            const text =
              start.mode === 'ai'
                ? await answer(
                    config,
                    transcript,
                    start.provider,
                    start.model,
                    start.credential,
                    controller.signal,
                  )
                : transcript;
            if (controller.signal.aborted) return;
            await settle();
            send({
              type: 'result',
              operationId: start.operationId,
              editorSession: start.editorSession,
              transcript,
              text,
            });
            await close();
          } catch {
            fail(
              'provider_error',
              'Could not finish. Your available transcript is kept on this device.',
            );
          }
        };
        const handle = async (data: Buffer, binary: boolean) => {
          if (phase === 'closed') return;
          if (binary) {
            if (
              phase !== 'recording' ||
              (!managed() && !upstream) ||
              data.length % 2 !== 0
            ) {
              fail('invalid_audio', 'Recording is not ready');
              return;
            }
            if (
              bytes + data.length > config.MAX_AUDIO_SECONDS * 32_000 ||
              bytes + data.length + 44 > config.MAX_AUDIO_BYTES ||
              (upstream?.bufferedAmount ?? 0) > 320_000
            ) {
              fail(
                'audio_limit',
                'Recording limit reached. Finish and try a shorter recording.',
              );
              return;
            }
            bytes += data.length;
            if (managed()) {
              chunks.push(data);
              return;
            }
            upstream!.send(
              JSON.stringify({
                realtimeInput: {
                  audio: {
                    data: data.toString('base64'),
                    mimeType: 'audio/pcm;rate=16000',
                  },
                },
              }),
            );
            return;
          }
          const parsed = liveClientMessageSchema.safeParse(
            JSON.parse(data.toString()),
          );
          if (!parsed.success) {
            fail('invalid_message', 'Invalid voice request');
            return;
          }
          const message = parsed.data;
          if (message.type === 'cancel') {
            await close('cancelled');
            return;
          }
          if (message.type === 'mode' && start && phase === 'recording') {
            start.mode = message.mode;
            return;
          }
          if (message.type === 'finish' && phase === 'recording') {
            phase = 'finishing';
            send({ type: 'processing' });
            if (managed()) {
              void complete();
              return;
            }
            deadline(8_000);
            upstream?.send(
              JSON.stringify({ realtimeInput: { activityEnd: {} } }),
            );
            return;
          }
          if (message.type !== 'start' || phase !== 'new') {
            fail('invalid_state', 'Voice request is already active');
            return;
          }
          phase = 'connecting';
          start = message;
          const key = managed()
            ? config.GROQ_API_KEY
            : message.speechCredential || config.GEMINI_API_KEY;
          if (!key) {
            fail(
              'not_configured',
              managed()
                ? 'Configure GROQ_API_KEY on the backend.'
                : 'Add a Gemini key in settings or configure the managed backend.',
            );
            return;
          }
          installationId = (
            await auth.verify(
              (request.headers.authorization || '').replace(/^Bearer /, ''),
            )
          ).installationId;
          const reservation = await store.reserve(
            installationId,
            message.operationId,
            config.MAX_AUDIO_SECONDS,
            config.FREE_AUDIO_SECONDS,
          );
          if (reservation !== 'reserved') {
            fail(
              reservation,
              reservation === 'exhausted'
                ? 'Voice quota is exhausted'
                : 'This recording was already submitted',
            );
            return;
          }
          reserved = true;
          if (controller.signal.aborted) {
            await settle('cancelled');
            return;
          }
          if (managed()) {
            phase = 'recording';
            deadline(config.MAX_AUDIO_SECONDS * 1000);
            send({
              type: 'ready',
              operationId: message.operationId,
              editorSession: message.editorSession,
            });
            return;
          }
          upstream = new WebSocket(
            'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent',
            { headers: { 'x-goog-api-key': key }, maxPayload: 1_048_576 },
          );
          upstream.on('open', () =>
            upstream?.send(
              JSON.stringify({
                setup: {
                  model: `models/${config.GEMINI_TRANSCRIBE_LIVE_MODEL}`,
                  generationConfig: { responseModalities: ['TEXT'] },
                  realtimeInputConfig: {
                    automaticActivityDetection: { disabled: true },
                  },
                  inputAudioTranscription: { mode: 'SMART', languageCodes: [] },
                },
              }),
            ),
          );
          upstream.on('message', (raw) => {
            try {
              const event = JSON.parse(raw.toString());
              if (event.error) {
                fail('provider_error', 'Live transcription is unavailable');
                return;
              }
              if (event.setupComplete && phase === 'connecting') {
                upstream?.send(
                  JSON.stringify({ realtimeInput: { activityStart: {} } }),
                );
                phase = 'recording';
                deadline(config.MAX_AUDIO_SECONDS * 1000);
                send({
                  type: 'ready',
                  operationId: message.operationId,
                  editorSession: message.editorSession,
                });
              }
              const content = event.serverContent;
              if (content?.interimInputTranscription?.text)
                send({
                  type: 'interim',
                  text: content.interimInputTranscription.text,
                });
              if (content?.inputTranscription?.text) {
                transcript = [
                  transcript,
                  content.inputTranscription.text.trim(),
                ]
                  .filter(Boolean)
                  .join(' ');
                if (transcript.length > 100_000) {
                  fail('text_limit', 'Transcript limit reached');
                  return;
                }
                send({ type: 'final', text: transcript });
              }
              if (content?.turnComplete && phase === 'finishing')
                void complete();
            } catch {
              fail('provider_error', 'Invalid transcription response');
            }
          });
          upstream.on('error', () =>
            fail('provider_error', 'Live transcription connection failed'),
          );
          upstream.on('close', () => {
            if (
              phase === 'recording' ||
              phase === 'connecting' ||
              phase === 'finishing'
            )
              fail(
                'disconnected',
                'Live transcription disconnected. Try again.',
              );
          });
        };
        socket.on('message', (data, binary) => {
          const frame = Buffer.from(data as Buffer);
          pendingBytes += frame.length;
          if (pendingBytes > 320_000) {
            fail('buffer_limit', 'Voice connection is overloaded');
            return;
          }
          serial = serial
            .then(() => handle(frame, binary))
            .catch(() => fail('request_error', 'Voice request failed'))
            .finally(() => {
              pendingBytes -= frame.length;
            });
        });
        socket.on('close', () => {
          void close('disconnected');
        });
        socket.on('error', () => {
          void close('disconnected');
        });
      },
    );
  });
}
