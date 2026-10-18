import { describe, expect, it } from 'vitest';
import { PROMPT_MODE_OPTIONS, promptHint } from './promptMode';

describe('promptHint', () => {
  it('says nothing is added when the box is empty, in either mode', () => {
    for (const mode of ['append', 'replace'] as const) {
      expect(promptHint(mode, false, 'ai')).toMatch(/built-in instructions/);
      expect(promptHint(mode, false, 'polish')).toMatch(/proofreading rules/);
    }
  });
  it('explains that appended notes cannot break the rules', () => {
    expect(promptHint('append', true, 'ai')).toMatch(/after Odicto's rules/);
    expect(promptHint('append', true, 'polish')).toMatch(/cannot break/);
  });
  it('warns that replacing turns the rules off and what to restate', () => {
    expect(promptHint('replace', true, 'ai')).toMatch(
      /only the text to insert/,
    );
    expect(promptHint('replace', true, 'polish')).toMatch(
      /only the corrected text/,
    );
  });
  it('offers exactly the two choices', () => {
    expect(PROMPT_MODE_OPTIONS.map((option) => option.value)).toEqual([
      'append',
      'replace',
    ]);
  });
});
