import { registerPlugin } from '@capacitor/core';

export type VoiceSettings = {
  enabled: boolean;
  mode: 'raw' | 'ai' | 'live';
  provider: 'gemini' | 'openrouter';
  model: string;
  liveModel: string;
  systemPrompt: string;
  /** How the AI prompt combines with Odicto's built-in instructions. */
  systemPromptMode: 'append' | 'replace';
  polishProvider: 'gemini' | 'openrouter';
  polishModel: string;
  polishSystemPrompt: string;
  polishSystemPromptMode: 'append' | 'replace';
  groqKeySet: boolean;
  haptics: boolean;
  /** 0 off, 1 light, 2 medium, 3 strong. Replaces the old boolean for the user. */
  hapticLevel: number;
  tapTolerance: 0 | 1 | 2;
  autocorrectEnabled: boolean;
  keySize: 'small' | 'medium' | 'large';
  keyFont: 'system' | 'instrument';
  pinnedEmoji: string[];
  showTextPreview: boolean;
  paused: boolean;
  geminiKeySet: boolean;
  openrouterKeySet: boolean;
  openSettings?: boolean;
};
export type OpenRouterModel = {
  id: string;
  name: string;
  free: boolean;
  contextLength: number;
  reasoning: boolean;
};
export type GeminiModel = { id: string; name: string };
export type HistoryItem = {
  id: string;
  text: string;
  outcome: string;
  createdAt: number;
};
export const voice = registerPlugin<{
  status(): Promise<VoiceSettings>;
  openrouterModels(): Promise<{ models: OpenRouterModel[] }>;
  geminiModels(): Promise<{ models: GeminiModel[] }>;
  save(
    settings: Partial<VoiceSettings> & {
      geminiKey?: string;
      groqKey?: string;
      openrouterKey?: string;
    },
  ): Promise<void>;
  history(): Promise<{ entries: HistoryItem[] }>;
  feedback(): Promise<void>;
}>('OdictoVoice');
