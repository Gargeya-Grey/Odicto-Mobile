import { LlmProvider, OutputFilter, FilterPreset } from '../types';

export interface ProviderMeta {
  id: LlmProvider;
  name: string;
  badge: string;
  description: string;
  defaultModel: string;
  popularModels: string[];
}

export const PROVIDERS: Record<LlmProvider, ProviderMeta> = {
  gemini: {
    id: 'gemini',
    name: 'Google Gemini',
    badge: 'Default · Ultra-Fast',
    description:
      'Ultra-low latency Google GenAI model for real-time mobile voice rewriting and instant execution.',
    defaultModel: 'gemini-3.7-flash',
    popularModels: [
      'gemini-3.7-flash',
      'gemini-3.6-flash',
      'gemini-3.1-pro-preview',
    ],
  },
  openrouter: {
    id: 'openrouter',
    name: 'OpenRouter',
    badge: 'Multi-Model Hub',
    description:
      'Access hundreds of frontier models (Claude, Llama, Gemini, DeepSeek) through a single unified endpoint.',
    defaultModel: 'poolside/laguna-xs-2.1:free',
    popularModels: [],
  },
  meta: {
    id: 'meta',
    name: 'Meta API',
    badge: 'Llama Intelligence',
    description:
      'Direct Meta AI endpoint for specialized contributor models and Llama reasoning engines.',
    defaultModel: 'muse-spark-1.2-contributor',
    popularModels: [
      'muse-spark-1.2-contributor',
      'llama-3.3-70b-instruct',
      'llama-3.1-8b-instruct',
    ],
  },
};

export const GROQ_MODELS = [
  {
    id: 'whisper-large-v3-turbo',
    label: 'Whisper Large v3 Turbo (Recommended · ~250ms LPU)',
  },
  { id: 'whisper-large-v3', label: 'Whisper Large v3 (Maximum accuracy)' },
  {
    id: 'distil-whisper-large-v3-en',
    label: 'Distil-Whisper English (Ultra-low latency)',
  },
];

export const FILTER_PRESETS: FilterPreset[] = [
  {
    id: 'clean_speech',
    label: 'Clean Speech',
    shortDesc: 'Fix speech slips, stutter & grammar',
    promptInstruction:
      'Clean up the dictated speech: fix grammar, remove filler words (um, ah, like, you know), and format into fluent, professional written prose while retaining the original meaning.',
    iconName: 'Sparkles',
  },
  {
    id: 'bullet_points',
    label: 'Action Bullets',
    shortDesc: 'Structured bullet point summary',
    promptInstruction:
      'Convert the dictated speech into a clean, concise bulleted list of key takeaways, action items, or structured notes. Keep items brief and actionable.',
    iconName: 'ListOrdered',
  },
  {
    id: 'professional',
    label: 'Professional Tone',
    shortDesc: 'Polished executive email / message',
    promptInstruction:
      'Rephrase the dictated input into polished, polite, and articulate professional language suitable for workplace messages, emails, or reports.',
    iconName: 'Briefcase',
  },
  {
    id: 'concise',
    label: 'Ultra Concise',
    shortDesc: 'Eliminate fluff & condense thoughts',
    promptInstruction:
      'Distill the core meaning into the most concise and punchy phrasing possible, stripping away all unnecessary words without losing context.',
    iconName: 'Zap',
  },
  {
    id: 'code_markdown',
    label: 'Technical / Code',
    shortDesc: 'Format code directives & markdown',
    promptInstruction:
      'Structure technical thoughts into clean Markdown with syntax-highlighted code blocks, terminal commands, or structured technical specifications.',
    iconName: 'Code',
  },
  {
    id: 'translate_en',
    label: 'Translate to English',
    shortDesc: 'Translate foreign audio to English',
    promptInstruction:
      'Translate the spoken text into natural, fluent English prose. If already English, refine grammar and clarity.',
    iconName: 'Languages',
  },
  {
    id: 'verbatim',
    label: 'Raw Verbatim',
    shortDesc: 'Exact spoken audio transcript',
    promptInstruction:
      'Output the exact transcribed speech with proper capitalization and punctuation. Do not rephrase or alter words.',
    iconName: 'Mic',
  },
];
