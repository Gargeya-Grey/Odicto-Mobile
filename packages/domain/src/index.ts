export const OUTPUT_FILTERS = [
  'verbatim',
  'clean_speech',
  'bullet_points',
  'professional',
  'concise',
  'code_markdown',
  'translate_en',
] as const;
export type OutputFilter = (typeof OUTPUT_FILTERS)[number];

export const FILTER_INSTRUCTIONS: Record<OutputFilter, string> = {
  verbatim: '',
  clean_speech:
    'Fix grammar, punctuation, and casing. Remove speech fillers and repetition while preserving intent and voice.',
  bullet_points: 'Format the content as concise, actionable bullet points.',
  professional: 'Rewrite in polished, courteous professional language.',
  concise: 'Remove filler and preserve only essential facts and decisions.',
  code_markdown:
    'Format technical content as readable Markdown, using code blocks only where needed.',
  translate_en:
    'Translate to natural English. If already English, improve grammar and readability.',
};

export const DEFAULT_SYSTEM_PROMPT =
  'Transform the dictated text exactly as requested. Return only text that can be inserted at the cursor; do not add greetings or commentary.';

export function buildSystemPrompt(
  filter: OutputFilter,
  base = DEFAULT_SYSTEM_PROMPT,
): string {
  const instruction = FILTER_INSTRUCTIONS[filter];
  return instruction ? `${base}\n\n${instruction}` : base;
}

export type DictationState =
  | 'idle'
  | 'ready'
  | 'recording'
  | 'uploading'
  | 'transcribing'
  | 'refining'
  | 'inserting'
  | 'completed'
  | 'saved_to_history'
  | 'cancelled'
  | 'failed';

export const DICTATION_TRANSITIONS: Record<
  DictationState,
  readonly DictationState[]
> = {
  idle: ['ready'],
  ready: ['recording', 'cancelled'],
  recording: ['uploading', 'cancelled', 'failed'],
  uploading: ['transcribing', 'saved_to_history', 'failed'],
  transcribing: ['refining', 'inserting', 'saved_to_history', 'failed'],
  refining: ['inserting', 'saved_to_history', 'failed'],
  inserting: ['completed', 'saved_to_history', 'failed'],
  completed: ['ready', 'idle'],
  saved_to_history: ['ready', 'idle'],
  cancelled: ['ready', 'idle'],
  failed: ['ready', 'idle'],
};

export function canTransition(
  from: DictationState,
  to: DictationState,
): boolean {
  return DICTATION_TRANSITIONS[from].includes(to);
}
