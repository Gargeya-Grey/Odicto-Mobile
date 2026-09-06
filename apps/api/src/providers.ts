import { GoogleGenAI } from '@google/genai';
import { buildSystemPrompt, type OutputFilter } from '@odicto/domain';
import type { ApiConfig } from './config.js';

export type SttResult = { text: string; model: string; duration: number };
export async function transcribe(
  config: ApiConfig,
  audio: Buffer,
  filename: string,
  mimeType: string,
  language: string,
  signal?: AbortSignal,
): Promise<SttResult> {
  if (!config.GROQ_API_KEY)
    throw new Error('Managed speech transcription is unavailable');
  const form = new FormData();
  form.append(
    'file',
    new File([new Uint8Array(audio)], filename, { type: mimeType }),
  );
  form.append('model', config.GROQ_MODEL);
  form.append('response_format', 'verbose_json');
  if (language !== 'auto') form.append('language', language);
  const response = await fetch(
    'https://api.groq.com/openai/v1/audio/transcriptions',
    {
      method: 'POST',
      headers: { Authorization: `Bearer ${config.GROQ_API_KEY}` },
      body: form,
      signal: signal
        ? AbortSignal.any([signal, AbortSignal.timeout(15_000)])
        : AbortSignal.timeout(15_000),
    },
  );
  if (!response.ok)
    throw new Error(`Speech provider failed (${response.status})`);
  const result = (await response.json()) as {
    text?: string;
    duration?: number;
  };
  if (!result.text?.trim()) throw new Error('Speech provider returned no text');
  return {
    text: result.text.trim(),
    model: config.GROQ_MODEL,
    duration: result.duration ?? 0,
  };
}

export async function refine(
  config: ApiConfig,
  text: string,
  filter: OutputFilter,
  context: Array<{ role: 'user' | 'assistant'; content: string }>,
  credential?: string,
  requestedProvider = 'managed',
) {
  if (requestedProvider === 'openrouter')
    return refineOpenRouter(config, text, filter, context, credential);
  const apiKey = credential || config.GEMINI_API_KEY;
  if (!apiKey) throw new Error('Refinement provider is unavailable');
  const model = config.GEMINI_MODEL;
  const ai = new GoogleGenAI({ apiKey });
  const response = await ai.models.generateContent({
    model,
    contents: [
      ...context.map((turn) => ({
        role: turn.role === 'assistant' ? 'model' : 'user',
        parts: [{ text: turn.content }],
      })),
      { role: 'user', parts: [{ text }] },
    ],
    config: {
      systemInstruction: buildSystemPrompt(filter),
      maxOutputTokens: 2048,
    },
  });
  return { text: response.text?.trim() || text, provider: 'gemini', model };
}

async function refineOpenRouter(
  config: ApiConfig,
  text: string,
  filter: OutputFilter,
  context: Array<{ role: string; content: string }>,
  credential?: string,
) {
  const key = credential || config.OPENROUTER_API_KEY;
  if (!key || !config.OPENROUTER_MODEL)
    throw new Error('OpenRouter is unavailable');
  const response = await fetch(
    `${config.OPENROUTER_API_BASE.replace(/\/$/, '')}/chat/completions`,
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${key}`,
        'Content-Type': 'application/json',
        'HTTP-Referer': config.PUBLIC_API_URL,
        'X-Title': 'Odicto',
      },
      body: JSON.stringify({
        model: config.OPENROUTER_MODEL,
        messages: [
          { role: 'system', content: buildSystemPrompt(filter) },
          ...context,
          { role: 'user', content: text },
        ],
        max_tokens: 2048,
      }),
      signal: AbortSignal.timeout(12_000),
    },
  );
  if (!response.ok) throw new Error(`OpenRouter failed (${response.status})`);
  const body = (await response.json()) as {
    choices?: Array<{ message?: { content?: string } }>;
  };
  return {
    text: body.choices?.[0]?.message?.content?.trim() || text,
    provider: 'openrouter',
    model: config.OPENROUTER_MODEL,
  };
}
