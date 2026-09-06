import {
  dictationResponseSchema,
  installationResponseSchema,
  providerTestResponseSchema,
  type DictationMetadata,
  type DictationResponse,
} from '@odicto/contracts';

const API_BASE = (
  import.meta.env.VITE_PUBLIC_API_BASE_URL || 'http://localhost:8080'
).replace(/\/$/, '');
const REFRESH_KEY = 'odicto.installation.refresh';
let accessToken = '';

async function install(): Promise<string> {
  const refreshToken = localStorage.getItem(REFRESH_KEY) || undefined;
  const response = await fetch(`${API_BASE}/v1/installations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      platform: 'web',
      appVersion: '0.1.0',
      refreshToken,
    }),
  });
  if (!response.ok) throw new Error('Unable to register this installation');
  const credentials = installationResponseSchema.parse(await response.json());
  accessToken = credentials.accessToken;
  localStorage.setItem(REFRESH_KEY, credentials.refreshToken);
  return accessToken;
}

async function authorized(
  path: string,
  init: RequestInit,
  refresh = true,
): Promise<Response> {
  const token = accessToken || (await install());
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  });
  if (response.status === 401 && refresh) {
    accessToken = '';
    return authorized(path, init, false);
  }
  return response;
}

export async function createDictation(
  audio: Blob,
  metadata: DictationMetadata,
  providerKey?: string,
): Promise<DictationResponse> {
  const body = new FormData();
  body.append(
    'audio',
    audio,
    `recording.${audio.type.includes('mp4') ? 'm4a' : 'webm'}`,
  );
  body.append('metadata', JSON.stringify(metadata));
  if (providerKey) body.append('providerKey', providerKey);
  const response = await authorized('/v1/dictations', { method: 'POST', body });
  const json = await response.json();
  if (!response.ok) throw new Error(json?.error?.message || 'Dictation failed');
  return dictationResponseSchema.parse(json);
}

export async function testProvider(
  provider: 'managed' | 'gemini' | 'openrouter' | 'meta',
  credential?: string,
  model?: string,
) {
  const response = await authorized('/v1/providers/test', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      provider,
      credential: credential || undefined,
      model,
    }),
  });
  const json = await response.json();
  if (!response.ok)
    throw new Error(json?.error?.message || 'Provider test failed');
  return providerTestResponseSchema.parse(json);
}

export async function resetContext() {
  const response = await authorized('/v1/context/reset', { method: 'POST' });
  if (!response.ok) throw new Error('Context reset failed');
}
