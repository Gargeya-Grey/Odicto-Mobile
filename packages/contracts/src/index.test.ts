import { describe, expect, it } from 'vitest';
import { dictationMetadataSchema, installationRequestSchema } from './index.js';

describe('API contracts', () => {
  it('bounds conversation context', () => {
    const context = Array.from({ length: 9 }, () => ({
      role: 'user' as const,
      content: 'x',
    }));
    expect(
      dictationMetadataSchema.safeParse({
        operationId: crypto.randomUUID(),
        mode: 'refine',
        context,
      }).success,
    ).toBe(false);
  });

  it('requires a real installation refresh credential', () => {
    expect(
      installationRequestSchema.safeParse({
        platform: 'android',
        appVersion: '1',
        refreshToken: 'short',
      }).success,
    ).toBe(false);
  });
});
