import { describe, expect, it } from 'vitest';
import type { OpenRouterModel } from '../native/voice';
import { modelGroups } from './modelGroups';

const model = (id: string, name: string, free = false): OpenRouterModel => ({
  id,
  name,
  free,
  contextLength: 0,
  reasoning: false,
});

const CATALOG = [
  model('openai/gpt-4o', 'OpenAI: GPT-4o'),
  model('reka/flash-3', 'Reka Flash 3'),
  model('anthropic/claude-haiku', 'Anthropic: Claude Haiku'),
  model('openai/gpt-4', 'OpenAI: GPT-4'),
  model('openai/gpt-4-batch', 'OpenAI: GPT-4 (batch)'),
  model('poolside/laguna-xs-2.1:free', 'Poolside: Laguna XS', true),
];

const values = (groups: ReturnType<typeof modelGroups>) =>
  groups.flatMap((group) => group.options.map((option) => option.value));

describe('modelGroups', () => {
  it('sorts by name and groups by first letter', () => {
    const groups = modelGroups(CATALOG, '');
    expect(groups.map((group) => group.label)).toEqual(['A', 'O', 'P', 'R']);
    expect(values(groups)).toEqual([
      'anthropic/claude-haiku',
      'openai/gpt-4',
      'openai/gpt-4o',
      'poolside/laguna-xs-2.1:free',
      'reka/flash-3',
    ]);
  });

  it('drops batch models and labels free ones', () => {
    const labels = modelGroups(CATALOG, '').flatMap((group) =>
      group.options.map((option) => option.label),
    );
    expect(labels).not.toContain('OpenAI: GPT-4 (batch)');
    expect(labels).toContain('Poolside: Laguna XS · free');
  });

  it('matches the search against name and ID, ignoring case', () => {
    expect(values(modelGroups(CATALOG, 'ANTHROPIC'))).toEqual([
      'anthropic/claude-haiku',
    ]);
    expect(values(modelGroups(CATALOG, 'flash-3'))).toEqual(['reka/flash-3']);
  });

  it('puts a pinned option first and removes its ID from the list', () => {
    const pinned = { value: 'poolside/laguna-xs-2.1:free', label: 'Auto' };
    const groups = modelGroups(CATALOG, '', { pinned });
    expect(groups[0]).toEqual({ options: [pinned] });
    expect(values(groups).filter((v) => v === pinned.value)).toHaveLength(1);
    expect(values(modelGroups(CATALOG, 'reka', { pinned }))).toEqual([
      'reka/flash-3',
    ]);
  });

  it('offers a typed model ID that the catalog does not list', () => {
    const groups = modelGroups(CATALOG, ' acme/new-model ', {
      allowCustom: true,
    });
    expect(groups.at(-1)).toEqual({
      label: 'Model ID',
      options: [{ value: 'acme/new-model', label: 'Use “acme/new-model”' }],
    });
  });

  it('does not offer a typed ID that is listed, blank, or not an ID', () => {
    const custom = (search: string) =>
      modelGroups(CATALOG, search, { allowCustom: true }).some(
        (group) => group.label === 'Model ID',
      );
    expect(custom('reka/flash-3')).toBe(false);
    expect(custom('Reka/Flash-3')).toBe(false);
    expect(custom('   ')).toBe(false);
    expect(custom('two words')).toBe(false);
    expect(custom(`a/${'x'.repeat(199)}`)).toBe(false);
    expect(custom(`a/${'x'.repeat(198)}`)).toBe(true);
    expect(custom('anthropic')).toBe(false);
    expect(custom('anthropic/claude-opus')).toBe(true);
    expect(modelGroups(CATALOG, 'acme/x').length).toBe(0);
  });

  it('lists catalog matches before the typed ID', () => {
    const groups = modelGroups(CATALOG, 'openai/gpt-4', { allowCustom: true });
    expect(values(groups)).toEqual(['openai/gpt-4', 'openai/gpt-4o']);
    const partial = modelGroups(CATALOG, 'openai/gpt', { allowCustom: true });
    expect(values(partial).at(-1)).toBe('openai/gpt');
  });
});
