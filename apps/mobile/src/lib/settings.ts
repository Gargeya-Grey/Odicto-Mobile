import type { AppConfig } from '../types';

const KEY = 'odicto.settings';
export const DEFAULT_SETTINGS: AppConfig = {
  LLM_PROVIDER: 'gemini',
  GROQ_API_KEY: '',
  GROQ_MODEL: 'whisper-large-v3-turbo',
  GEMINI_API_KEY: '',
  GEMINI_MODEL: 'gemini-3.5-flash-lite',
  GEMINI_THINKING_LEVEL: 'minimal',
  GEMINI_MAX_OUTPUT_TOKENS: 2048,
  OPENROUTER_API_KEY: '',
  OPENROUTER_MODEL: 'meta-llama/llama-3.3-70b-instruct',
  OPENROUTER_API_BASE: 'https://openrouter.ai/api/v1',
  META_API_KEY: '',
  META_MODEL: 'muse-spark-1.2-contributor',
  META_API_BASE: 'https://api.meta.ai/v1',
  META_REASONING_EFFORT: 'low',
  META_MAX_OUTPUT_TOKENS: 2048,
  SYSTEM_PROMPT: '',
  ENABLE_MULTI_TURN: false,
  SELECTED_FILTER: 'clean_speech',
  PLAY_AUDIO_CUES: true,
  SHOW_VISUAL_INDICATOR: true,
  AUTO_COPY_CLIPBOARD: true,
};
export function loadSettings(): AppConfig {
  try {
    return {
      ...DEFAULT_SETTINGS,
      ...JSON.parse(localStorage.getItem(KEY) || '{}'),
    };
  } catch {
    return DEFAULT_SETTINGS;
  }
}
export function saveSettings(settings: AppConfig): void {
  localStorage.setItem(KEY, JSON.stringify(settings));
}
