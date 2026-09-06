import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { createApp } from './app.js';
import { loadConfig } from './config.js';
import { MemoryInstallationStore } from './auth.js';

const provider = vi.hoisted(() => ({ instances: [] as any[] }));
vi.mock('ws', async (importOriginal) => {
  const actual = await importOriginal<typeof import('ws')>();
  const { EventEmitter } = await import('node:events');
  class FakeGemini extends EventEmitter {
    static OPEN = 1;
    bufferedAmount = 0;
    sent: any[] = [];
    constructor() {
      super();
      provider.instances.push(this);
      queueMicrotask(() => this.emit('open'));
    }
    send(data: string) {
      const value = JSON.parse(data);
      this.sent.push(value);
      if (value.setup)
        queueMicrotask(() =>
          this.emit('message', Buffer.from('{"setupComplete":{}}')),
        );
    }
    close() {
      this.emit('close');
    }
    message(value: unknown) {
      this.emit('message', Buffer.from(JSON.stringify(value)));
    }
  }
  return { ...actual, default: FakeGemini };
});

const apps: ReturnType<typeof createApp>[] = [];
beforeEach(() => {
  provider.instances.length = 0;
});
afterEach(async () => {
  vi.unstubAllGlobals();
  await Promise.all(apps.splice(0).map((app) => app.close()));
});
async function connect(managed = false) {
  const store = new MemoryInstallationStore();
  const app = createApp(
    loadConfig({
      NODE_ENV: 'test',
      ...(managed
        ? { GROQ_API_KEY: 'test-placeholder' }
        : { GEMINI_API_KEY: 'test-placeholder' }),
      TOKEN_SIGNING_SECRET: 'a'.repeat(32),
    }),
    store,
  );
  apps.push(app);
  await app.ready();
  const credentials = (
    await app.inject({
      method: 'POST',
      url: '/v1/installations',
      payload: { platform: 'android', appVersion: 'test' },
    })
  ).json();
  const socket = await app.injectWS('/v1/dictations/live', {
    headers: { authorization: `Bearer ${credentials.accessToken}` },
  });
  const messages: any[] = [];
  socket.on('message', (data) => messages.push(JSON.parse(data.toString())));
  return { app, socket, messages, store, credentials };
}
async function start(
  connection: Awaited<ReturnType<typeof connect>>,
  mode = 'live',
  selectedProvider = 'gemini',
) {
  connection.socket.send(
    JSON.stringify({
      type: 'start',
      mode,
      provider: selectedProvider,
      operationId: randomUUID(),
      editorSession: 7,
    }),
  );
  await vi.waitFor(() =>
    expect(connection.messages.some((m) => m.type === 'ready')).toBe(true),
  );
  return provider.instances.at(-1)!;
}
describe('Live voice transport', () => {
  it('records with only Groq configured and returns a transcript on release', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ text: 'Groq writes these words.', duration: 0.1 }),
        ),
      );
    vi.stubGlobal('fetch', fetchMock);
    const c = await connect(true);
    await start(c, 'live', 'managed');
    expect(provider.instances).toHaveLength(0);
    c.socket.send(Buffer.alloc(3200));
    c.socket.send('{"type":"finish"}');
    await vi.waitFor(() =>
      expect(c.messages.find((m) => m.type === 'result')?.text).toBe(
        'Groq writes these words.',
      ),
    );
    expect(fetchMock.mock.calls[0][0]).toBe(
      'https://api.groq.com/openai/v1/audio/transcriptions',
    );
    const wav = Buffer.from(
      await fetchMock.mock.calls[0][1].body.get('file').arrayBuffer(),
    );
    expect(wav.toString('ascii', 0, 4)).toBe('RIFF');
    expect(wav.readUInt32LE(40)).toBe(3200);
    expect(await c.store.getUsage(c.credentials.installationId)).toBe(0.1);
    c.socket.terminate();
  });
  it('rejects an unauthenticated upgrade', async () => {
    const app = createApp(loadConfig({ NODE_ENV: 'test' }));
    apps.push(app);
    await app.ready();
    await expect(app.injectWS('/v1/dictations/live')).rejects.toThrow();
    expect(provider.instances).toHaveLength(0);
  });
  it('cancels an in-flight Groq request without returning an insertion result', async () => {
    let signal: AbortSignal | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url, options) =>
          new Promise((_resolve, reject) => {
            signal = options.signal;
            signal!.addEventListener('abort', () =>
              reject(new Error('aborted')),
            );
          }),
      ),
    );
    const c = await connect(true);
    await start(c, 'live', 'managed');
    c.socket.send(Buffer.alloc(3200));
    c.socket.send('{"type":"finish"}');
    await vi.waitFor(() => expect(signal).toBeDefined());
    c.socket.send('{"type":"cancel"}');
    await vi.waitFor(() => expect(signal?.aborted).toBe(true));
    expect(c.messages.some((m) => m.type === 'result')).toBe(false);
    expect(await c.store.getUsage(c.credentials.installationId)).toBe(0.1);
    c.socket.terminate();
  });
  it('keeps interim text provisional, waits for finalization, and settles actual audio', async () => {
    const c = await connect();
    const upstream = await start(c);
    expect(upstream.sent[0].setup.inputAudioTranscription.mode).toBe('SMART');
    expect(
      upstream.sent[0].setup.realtimeInputConfig.automaticActivityDetection
        .disabled,
    ).toBe(true);
    upstream.message({
      serverContent: { interimInputTranscription: { text: 'hel' } },
    });
    upstream.message({
      serverContent: { interimInputTranscription: { text: 'hello' } },
    });
    c.socket.send(Buffer.alloc(3200));
    c.socket.send(JSON.stringify({ type: 'finish' }));
    await vi.waitFor(() =>
      expect(upstream.sent.some((m: any) => m.realtimeInput?.activityEnd)).toBe(
        true,
      ),
    );
    expect(c.messages.some((m) => m.type === 'result')).toBe(false);
    upstream.message({
      serverContent: {
        inputTranscription: { text: 'Hello world.' },
        turnComplete: true,
      },
    });
    await vi.waitFor(() =>
      expect(c.messages.find((m) => m.type === 'result')?.text).toBe(
        'Hello world.',
      ),
    );
    expect(
      c.messages.filter((m) => m.type === 'interim').map((m) => m.text),
    ).toEqual(['hel', 'hello']);
    expect(c.messages.find((m) => m.type === 'result').editorSession).toBe(7);
    expect(await c.store.getUsage(c.credentials.installationId)).toBe(0.1);
    c.socket.terminate();
  });
  it('settles cancellation without returning an insertion result', async () => {
    const c = await connect();
    await start(c);
    c.socket.send(Buffer.alloc(6400));
    c.socket.send('{"type":"cancel"}');
    await vi.waitFor(async () =>
      expect(await c.store.getUsage(c.credentials.installationId)).toBe(0.2),
    );
    expect(c.messages.some((m) => m.type === 'result')).toBe(false);
    c.socket.terminate();
  });
  it('settles disconnected sessions and does not retain reservations', async () => {
    const c = await connect();
    await start(c);
    c.socket.terminate();
    await vi.waitFor(async () =>
      expect(await c.store.getUsage(c.credentials.installationId)).toBe(0),
    );
  });
  it('executes an AI instruction and never substitutes a polished query for an answer', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          candidates: [{ content: { parts: [{ text: 'Paris.' }] } }],
        }),
        { status: 200 },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);
    const c = await connect();
    const upstream = await start(c, 'ai');
    c.socket.send('{"type":"finish"}');
    await vi.waitFor(() =>
      expect(c.messages.some((m) => m.type === 'processing')).toBe(true),
    );
    upstream.message({
      serverContent: {
        inputTranscription: { text: 'What is the capital of France?' },
        turnComplete: true,
      },
    });
    await vi.waitFor(() =>
      expect(c.messages.find((m) => m.type === 'result')?.text).toBe('Paris.'),
    );
    expect(
      JSON.parse(fetchMock.mock.calls[0][1].body).systemInstruction.parts[0]
        .text,
    ).toContain('Execute the spoken instruction');
    c.socket.terminate();
  });
  it('returns an error instead of inserting the question when the AI provider fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('', { status: 503 })),
    );
    const c = await connect();
    const upstream = await start(c, 'ai');
    c.socket.send('{"type":"finish"}');
    await vi.waitFor(() =>
      expect(c.messages.some((m) => m.type === 'processing')).toBe(true),
    );
    upstream.message({
      serverContent: {
        inputTranscription: { text: 'Draft a reply' },
        turnComplete: true,
      },
    });
    await vi.waitFor(() =>
      expect(c.messages.some((m) => m.type === 'error')).toBe(true),
    );
    expect(c.messages.some((m) => m.type === 'result')).toBe(false);
    c.socket.terminate();
  });
});
