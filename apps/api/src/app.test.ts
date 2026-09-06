import { describe, expect, it } from 'vitest';
import { createApp } from './app.js';
import { loadConfig } from './config.js';

const config = loadConfig({
  NODE_ENV: 'test',
  TOKEN_SIGNING_SECRET: 'a'.repeat(32),
});
describe('v1 API', () => {
  it('issues anonymous installation credentials without exposing configuration', async () => {
    const app = createApp(config);
    const response = await app.inject({
      method: 'POST',
      url: '/v1/installations',
      payload: { platform: 'android', appVersion: '0.1.0' },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json().refreshToken.length).toBeGreaterThan(32);
    expect(response.body).not.toContain('TOKEN_SIGNING_SECRET');
    await app.close();
  });
  it('requires authentication for usage', async () => {
    const app = createApp(config);
    const response = await app.inject({ method: 'GET', url: '/v1/usage' });
    expect(response.statusCode).toBe(401);
    expect(response.json().error.code).toBe('unauthorized');
    await app.close();
  });
});
