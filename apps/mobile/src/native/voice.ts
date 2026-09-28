import { registerPlugin } from '@capacitor/core';

export type VoiceSettings = {
  enabled: boolean;
  mode: 'raw' | 'ai' | 'live';
  provider: 'gemini' | 'openrouter';
  model: string;
  liveModel: string;
  systemPrompt: string;
  polishModel: string;
  polishSystemPrompt: string;
  groqKeySet: boolean;
  haptics: boolean;
  /** 0 off, 1 light, 2 medium, 3 strong. Replaces the old boolean for the user. */
  hapticLevel: number;
  keySize: 'small' | 'medium' | 'large';
  keyFont: 'system' | 'sansflex';
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
export type HistoryItem = {
  id: string;
  text: string;
  outcome: string;
  createdAt: number;
};
export const voice = registerPlugin<{
  status(): Promise<VoiceSettings>;
  openrouterModels(): Promise<{ models: OpenRouterModel[] }>;
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
