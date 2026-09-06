export type LlmProvider = 'gemini' | 'openrouter' | 'meta';

export type OutputFilter =
  | 'verbatim'
  | 'clean_speech'
  | 'bullet_points'
  | 'professional'
  | 'concise'
  | 'code_markdown'
  | 'translate_en';

export interface FilterPreset {
  id: OutputFilter;
  label: string;
  shortDesc: string;
  promptInstruction: string;
  iconName: string;
}

export interface AppConfig {
  LLM_PROVIDER: LlmProvider;

  // Groq Speech-to-Text
  GROQ_API_KEY: string;
  GROQ_MODEL: string;
  GROQ_API_KEY_SET?: boolean;

  // Google Gemini
  GEMINI_API_KEY: string;
  GEMINI_MODEL: string;
  GEMINI_THINKING_LEVEL: string;
  GEMINI_MAX_OUTPUT_TOKENS: number;
  GEMINI_API_KEY_SET?: boolean;

  // OpenRouter
  OPENROUTER_API_KEY: string;
  OPENROUTER_MODEL: string;
  OPENROUTER_API_BASE: string;
  OPENROUTER_API_KEY_SET?: boolean;

  // Meta API
  META_API_KEY: string;
  META_MODEL: string;
  META_API_BASE: string;
  META_REASONING_EFFORT: string;
  META_MAX_OUTPUT_TOKENS: number;
  META_API_KEY_SET?: boolean;

  // App Features & Customization
  SYSTEM_PROMPT: string;
  DEFAULT_SYSTEM_PROMPT?: string;
  ENABLE_MULTI_TURN: boolean;
  SELECTED_FILTER: OutputFilter;
  PLAY_AUDIO_CUES: boolean;
  SHOW_VISUAL_INDICATOR: boolean;
  AUTO_COPY_CLIPBOARD: boolean;
}

export interface DictationEntry {
  id: string;
  timestamp: number;
  mode: 'raw' | 'ai';
  rawTranscript: string;
  context?: string;
  resultText: string;
  provider?: string;
  model?: string;
  filter?: OutputFilter;
  durationMs?: number;
  sttEngine?: string;
}

export interface MessageTurn {
  role: 'user' | 'assistant' | 'system';
  content: string;
}
