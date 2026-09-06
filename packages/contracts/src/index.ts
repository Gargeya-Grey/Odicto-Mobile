import { z } from 'zod';

export const apiErrorSchema = z.object({
  error: z.object({
    code: z.string(),
    message: z.string(),
    requestId: z.string(),
    retryable: z.boolean().default(false),
  }),
});

export const installationRequestSchema = z.object({
  refreshToken: z.string().min(32).optional(),
  platform: z.enum(['android', 'ios', 'web']),
  appVersion: z.string().min(1).max(64),
  attestationToken: z.string().max(16384).optional(),
});

export const installationResponseSchema = z.object({
  installationId: z.string().uuid(),
  accessToken: z.string(),
  accessTokenExpiresAt: z.string().datetime(),
  refreshToken: z.string().min(32),
  refreshTokenExpiresAt: z.string().datetime(),
});

export const outputFilterSchema = z.enum([
  'verbatim',
  'clean_speech',
  'bullet_points',
  'professional',
  'concise',
  'code_markdown',
  'translate_en',
]);

export const contextTurnSchema = z.object({
  role: z.enum(['user', 'assistant']),
  content: z.string().min(1).max(4000),
});

export const dictationMetadataSchema = z.object({
  operationId: z.string().uuid(),
  mode: z.enum(['raw', 'refine']),
  filter: outputFilterSchema.default('verbatim'),
  language: z.string().min(2).max(16).default('en'),
  context: z.array(contextTurnSchema).max(8).default([]),
  provider: z
    .enum(['managed', 'gemini', 'openrouter', 'meta'])
    .default('managed'),
});

export const dictationResponseSchema = z.object({
  operationId: z.string().uuid(),
  transcript: z.string(),
  text: z.string(),
  refined: z.boolean(),
  provider: z.string(),
  model: z.string(),
  usage: z.object({
    audioSeconds: z.number().nonnegative(),
    inputTokens: z.number().int().nonnegative(),
    outputTokens: z.number().int().nonnegative(),
  }),
  requestId: z.string(),
});

export const usageResponseSchema = z.object({
  tier: z.enum(['free', 'premium', 'byok']),
  periodStartsAt: z.string().datetime(),
  periodEndsAt: z.string().datetime(),
  audioSecondsLimit: z.number().int().nonnegative(),
  audioSecondsUsed: z.number().nonnegative(),
  audioSecondsReserved: z.number().nonnegative(),
});

export const providerTestRequestSchema = z.object({
  provider: z.enum(['managed', 'gemini', 'openrouter', 'meta']),
  credential: z.string().min(1).max(8192).optional(),
  model: z.string().min(1).max(200).optional(),
});

export const providerTestResponseSchema = z.object({
  ok: z.boolean(),
  provider: z.string(),
  model: z.string().optional(),
  requestId: z.string(),
});
export const contextResetResponseSchema = z.object({
  ok: z.literal(true),
  requestId: z.string(),
});

export type InstallationRequest = z.infer<typeof installationRequestSchema>;
export type InstallationResponse = z.infer<typeof installationResponseSchema>;
export type DictationMetadata = z.infer<typeof dictationMetadataSchema>;
export type DictationResponse = z.infer<typeof dictationResponseSchema>;
export type UsageResponse = z.infer<typeof usageResponseSchema>;
export type ProviderTestRequest = z.infer<typeof providerTestRequestSchema>;

// Audio travels as binary PCM frames after the authenticated start message.
export const liveClientMessageSchema = z.discriminatedUnion('type', [
  z.object({
    type: z.literal('start'),
    operationId: z.string().uuid(),
    editorSession: z.number().int().nonnegative(),
    mode: z.enum(['ai', 'live']),
    provider: z.enum(['managed', 'gemini', 'openrouter']).default('managed'),
    model: z.string().min(1).max(200).optional(),
    credential: z.string().min(1).max(8192).optional(),
    speechCredential: z.string().min(1).max(8192).optional(),
  }),
  z.object({ type: z.literal('mode'), mode: z.enum(['ai', 'live']) }),
  z.object({ type: z.literal('finish') }),
  z.object({ type: z.literal('cancel') }),
]);
export const liveServerMessageSchema = z.discriminatedUnion('type', [
  z.object({
    type: z.literal('ready'),
    operationId: z.string().uuid(),
    editorSession: z.number().int(),
  }),
  z.object({ type: z.literal('interim'), text: z.string() }),
  z.object({ type: z.literal('final'), text: z.string() }),
  z.object({ type: z.literal('processing') }),
  z.object({
    type: z.literal('result'),
    operationId: z.string().uuid(),
    editorSession: z.number().int(),
    transcript: z.string(),
    text: z.string(),
  }),
  z.object({ type: z.literal('error'), code: z.string(), message: z.string() }),
]);
export type LiveClientMessage = z.infer<typeof liveClientMessageSchema>;
export type LiveServerMessage = z.infer<typeof liveServerMessageSchema>;
