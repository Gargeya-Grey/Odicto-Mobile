import { describe, expect, it } from 'vitest';
import { switchPolishProvider } from './polishProviderDraft';

describe('text polish provider drafts', () => {
  it('keeps each provider model when switching back and forth', () => {
    const gemini = switchPolishProvider(
      {},
      'openrouter',
      'vendor/my-saved-model',
      'gemini',
    );
    expect(gemini.model).toBe('gemini-3.5-flash-lite');

    const openrouter = switchPolishProvider(
      gemini.remembered,
      'gemini',
      'gemini-my-draft',
      'openrouter',
    );
    expect(openrouter.model).toBe('vendor/my-saved-model');

    const again = switchPolishProvider(
      openrouter.remembered,
      'openrouter',
      'vendor/my-new-draft',
      'gemini',
    );
    expect(again.model).toBe('gemini-my-draft');
    expect(again.remembered.openrouter).toBe('vendor/my-new-draft');
  });

  it('starts an unseen provider with its default after the draft is cleared', () => {
    const result = switchPolishProvider(
      {},
      'gemini',
      'gemini-saved',
      'openrouter',
    );
    expect(result.model).toBe('poolside/laguna-xs-2.1');
  });

  it('restores the saved model when returning to Settings with a different provider still drafted', () => {
    const result = switchPolishProvider(
      {},
      'gemini',
      'gemini-custom',
      'openrouter',
      { provider: 'openrouter', model: 'vendor/saved-model' },
    );
    expect(result.model).toBe('vendor/saved-model');
  });
});
