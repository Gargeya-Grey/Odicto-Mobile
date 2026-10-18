export type PromptMode = 'append' | 'replace';
export type PromptKind = 'ai' | 'polish';

export const PROMPT_MODE_OPTIONS = [
  { value: 'append', label: "Add to Odicto's rules" },
  { value: 'replace', label: 'Replace them' },
] as const;

/** The line under a prompt box: what is sent now, given the choice and whether anything is typed. */
export function promptHint(
  mode: PromptMode,
  typed: boolean,
  kind: PromptKind,
): string {
  if (!typed) {
    return kind === 'ai'
      ? "Nothing added. Odicto's built-in instructions are used."
      : "Nothing added. Odicto's built-in proofreading rules are used.";
  }
  if (mode === 'append') {
    return "Sent after Odicto's rules, so your notes cannot break them. Good for your name, role, tone or spellings.";
  }
  return kind === 'ai'
    ? "Only your prompt is sent. Odicto's rules are off, so say that the reply should be only the text to insert."
    : "Only your prompt is sent. Odicto's rules are off, so say that the reply should be only the corrected text.";
}
