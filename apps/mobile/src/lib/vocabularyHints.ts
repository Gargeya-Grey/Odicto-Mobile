export const MAX_VOCABULARY_BYTES = 224;
export const VOCABULARY_FULL_MESSAGE =
  'These hints are full. Remove or shorten a term to add more.';

const separators = /[,，\r\n]+/u;
const encoder = new TextEncoder();

export type VocabularyDraft = {
  tags: string[];
  input: string;
  error: string;
};

export function normalizeVocabularyHints(values: readonly string[]): string[] {
  const seen = new Set<string>();
  return values
    .flatMap((value) => value.split(separators))
    .flatMap((value) => {
      const term = value.normalize('NFC').trim();
      const identity = term.toLocaleLowerCase('en-US');
      if (!term || seen.has(identity)) return [];
      seen.add(identity);
      return [term];
    });
}

export function vocabularyFits(tags: readonly string[]): boolean {
  return encoder.encode(tags.join(', ')).length <= MAX_VOCABULARY_BYTES;
}

export function vocabularyDraft(tags: readonly string[]): VocabularyDraft {
  return { tags: normalizeVocabularyHints(tags), input: '', error: '' };
}

export function commitVocabulary(
  state: VocabularyDraft,
  text = state.input,
): VocabularyDraft {
  const tags = normalizeVocabularyHints([...state.tags, text]);
  return vocabularyFits(tags)
    ? { tags, input: '', error: '' }
    : { ...state, input: text, error: VOCABULARY_FULL_MESSAGE };
}

export function editVocabulary(
  state: VocabularyDraft,
  input: string,
  composing = false,
): VocabularyDraft {
  if (composing) return { ...state, input, error: '' };
  const terms = input.split(separators);
  const pending = terms.pop() ?? '';
  const tags = normalizeVocabularyHints([...state.tags, ...terms]);
  if (!vocabularyFits(normalizeVocabularyHints([...tags, pending])))
    return { ...state, input, error: VOCABULARY_FULL_MESSAGE };
  return { tags, input: pending, error: '' };
}

export function sameVocabulary(a: readonly string[], b: readonly string[]) {
  return a.length === b.length && a.every((term, index) => term === b[index]);
}
