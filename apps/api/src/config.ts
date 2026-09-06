import { z } from 'zod';

const schema = z.object({
  NODE_ENV: z
    .enum(['development', 'test', 'production'])
    .default('development'),
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),
  PUBLIC_API_URL: z.string().url().default('http://localhost:8080'),
  DATABASE_URL: z.string().min(1).optional(),
  TOKEN_SIGNING_SECRET: z
    .string()
    .min(32)
    .default('development-only-secret-change-me-now'),
  CORS_ALLOWED_ORIGINS: z.string().default('http://localhost:5173'),
  GROQ_API_KEY: z.string().optional(),
  GROQ_MODEL: z.string().default('whisper-large-v3-turbo'),
  GEMINI_API_KEY: z.string().optional(),
  GEMINI_TRANSCRIBE_LIVE_MODEL: z
    .string()
    .default('gemini-3.5-transcribe-live'),
  GEMINI_MODEL: z.string().default('gemini-3.5-flash-lite'),
  GEMINI_PREMIUM_MODEL: z.string().default('gemini-3.5-flash'),
  OPENROUTER_API_KEY: z.string().optional(),
  OPENROUTER_MODEL: z.string().optional(),
  OPENROUTER_API_BASE: z.string().url().default('https://openrouter.ai/api/v1'),
  META_API_KEY: z.string().optional(),
  META_MODEL: z.string().default('muse-spark-1.2-contributor'),
  META_API_BASE: z.string().url().default('https://api.meta.ai/v1'),
  MAX_AUDIO_BYTES: z.coerce.number().int().positive().default(26_214_400),
  MAX_AUDIO_SECONDS: z.coerce.number().int().positive().default(300),
  FREE_AUDIO_SECONDS: z.coerce.number().int().positive().default(1800),
});

export type ApiConfig = z.infer<typeof schema> & { corsOrigins: string[] };

export function loadConfig(env: NodeJS.ProcessEnv = process.env): ApiConfig {
  const parsed = schema.parse(env);
  if (parsed.NODE_ENV === 'production') {
    if (!parsed.DATABASE_URL)
      throw new Error('DATABASE_URL is required in production');
    if (!parsed.GROQ_API_KEY)
      throw new Error('GROQ_API_KEY is required in production');
    if (parsed.TOKEN_SIGNING_SECRET.includes('development-only'))
      throw new Error('TOKEN_SIGNING_SECRET must be replaced in production');
  }
  return {
    ...parsed,
    corsOrigins: parsed.CORS_ALLOWED_ORIGINS.split(',')
      .map((value) => value.trim())
      .filter(Boolean),
  };
}
