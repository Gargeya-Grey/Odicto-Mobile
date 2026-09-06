import { describe, expect, it } from 'vitest';
import { buildSystemPrompt, canTransition } from './index.js';

describe('dictation domain', () => {
  it('adds filter instructions without changing verbatim prompts', () => {
    expect(buildSystemPrompt('verbatim', 'base')).toBe('base');
    expect(buildSystemPrompt('concise', 'base')).toContain('essential facts');
  });

  it('rejects stale direct insertion transitions', () => {
    expect(canTransition('recording', 'inserting')).toBe(false);
    expect(canTransition('transcribing', 'inserting')).toBe(true);
  });
});
