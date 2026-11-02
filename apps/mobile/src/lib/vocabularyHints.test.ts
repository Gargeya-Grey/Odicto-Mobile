import { describe, expect, it } from 'vitest';
import {
  commitVocabulary,
  editVocabulary,
  normalizeVocabularyHints,
  sameVocabulary,
  vocabularyDraft,
  vocabularyFits,
  VOCABULARY_FULL_MESSAGE,
} from './vocabularyHints';

describe('vocabulary tag editing', () => {
  it('turns comma-separated terms into tags and retains the unfinished term', () => {
    const result = editVocabulary(
      vocabularyDraft([]),
      '  Odicto, EduDojo,New York',
    );
    expect(result).toEqual({
      tags: ['Odicto', 'EduDojo'],
      input: 'New York',
      error: '',
    });
    expect(commitVocabulary(result)).toEqual({
      tags: ['Odicto', 'EduDojo', 'New York'],
      input: '',
      error: '',
    });
  });

  it('supports pasted lists, fullwidth commas, empty entries, and line breaks', () => {
    expect(
      commitVocabulary(
        vocabularyDraft(['Odicto']),
        'EduDojo，New York\r\nGargeya,,',
      ),
    ).toEqual({
      tags: ['Odicto', 'EduDojo', 'New York', 'Gargeya'],
      input: '',
      error: '',
    });
  });

  it('deduplicates case and canonically equivalent text, preserving the first spelling', () => {
    expect(
      normalizeVocabularyHints([
        'Odicto',
        'ODICTO',
        'Café',
        'Cafe\u0301',
        'नमस्ते',
      ]),
    ).toEqual(['Odicto', 'Café', 'नमस्ते']);
    expect(commitVocabulary(vocabularyDraft(['Odicto']), '  odicto, ')).toEqual(
      { tags: ['Odicto'], input: '', error: '' },
    );
  });

  it('leaves composing text intact until composition finishes', () => {
    const composing = editVocabulary(vocabularyDraft([]), 'नमस्ते,', true);
    expect(composing).toEqual({ tags: [], input: 'नमस्ते,', error: '' });
    expect(editVocabulary(composing, composing.input)).toEqual({
      tags: ['नमस्ते'],
      input: '',
      error: '',
    });
  });

  it('includes pending text when preparing Save, even without a final comma', () => {
    const state = editVocabulary(vocabularyDraft(['Odicto']), 'New York');
    expect(state.tags).toEqual(['Odicto']);
    expect(commitVocabulary(state).tags).toEqual(['Odicto', 'New York']);
  });

  it('rejects a whole over-budget paste without dropping terms or text', () => {
    const initial = vocabularyDraft(['Odicto']);
    const pasted = 'EduDojo, ' + 'x'.repeat(224);
    expect(commitVocabulary(initial, pasted)).toEqual({
      ...initial,
      input: pasted,
      error: VOCABULARY_FULL_MESSAGE,
    });
    const changed = editVocabulary(initial, pasted);
    expect(changed.tags).toEqual(['Odicto']);
    expect(changed.input).toBe(pasted);
    expect(editVocabulary(changed, 'EduDojo,')).toEqual({
      tags: ['Odicto', 'EduDojo'],
      input: '',
      error: '',
    });
  });

  it('counts the serialized UTF-8 prompt, including separators and non-Latin text', () => {
    expect(vocabularyFits(['a'.repeat(224)])).toBe(true);
    expect(vocabularyFits(['a'.repeat(225)])).toBe(false);
    expect(vocabularyFits(['a'.repeat(221), 'b'])).toBe(true);
    expect(vocabularyFits(['a'.repeat(222), 'b'])).toBe(false);
    expect(vocabularyFits(['ह'.repeat(74)])).toBe(true);
    expect(vocabularyFits(['ह'.repeat(75)])).toBe(false);
  });

  it('compares tag values so re-adding a duplicate does not create an unsaved change', () => {
    expect(
      sameVocabulary(
        commitVocabulary(vocabularyDraft(['Odicto']), 'odicto').tags,
        ['Odicto'],
      ),
    ).toBe(true);
    expect(sameVocabulary(['Odicto', 'EduDojo'], ['Odicto'])).toBe(false);
  });
});
