export const OPENAI_LIVE_MODELS = [
  { value: 'gpt-live-transcribe', label: 'GPT Live Transcribe' },
  { value: 'gpt-realtime-whisper', label: 'GPT Realtime Whisper' },
] as const;

// Dedicated transcription model documented by Google; conversational audio models use a different setup.
export const GEMINI_LIVE_MODELS = [
  { value: 'gemini-3.5-transcribe-live', label: 'Gemini 3.5 Transcribe Live' },
] as const;

export function geminiLiveModelGroups(search: string, current: string) {
  const query = search.trim().toLowerCase();
  const matches = (option: { value: string; label: string }) =>
    `${option.label} ${option.value}`.toLowerCase().includes(query);
  const included = GEMINI_LIVE_MODELS.filter(matches);
  const saved =
    current && !GEMINI_LIVE_MODELS.some((option) => option.value === current)
      ? [{ value: current, label: current }].filter(matches)
      : [];
  return [
    ...(included.length
      ? [{ label: 'Live transcription', options: included }]
      : []),
    ...(saved.length ? [{ label: 'Current model', options: saved }] : []),
  ];
}

export function geminiLiveModelLabel(current: string) {
  return (
    GEMINI_LIVE_MODELS.find((option) => option.value === current)?.label ??
    current
  );
}

export function liveProviderOptions(openaiKeySet: boolean, current: string) {
  return [
    { value: 'gemini', label: 'Gemini' },
    ...(openaiKeySet || current === 'openai'
      ? [{ value: 'openai', label: 'OpenAI', disabled: !openaiKeySet }]
      : []),
  ];
}
