import { registerPlugin } from '@capacitor/core';

export type VoiceSettings = {
  enabled: boolean;
  mode: 'raw' | 'ai' | 'live';
  provider: 'gemini' | 'openrouter';
  model: string;
  liveModel: string;
  systemPrompt: string;
  groqKeySet: boolean;
  haptics: boolean;
  showTextPreview: boolean;
  geminiKeySet: boolean;
  openrouterKeySet: boolean;
  openSettings?: boolean;
};
export type HistoryItem = {
  id: string;
  text: string;
  outcome: string;
  createdAt: number;
};
export const voice = registerPlugin<{
  status(): Promise<VoiceSettings>;
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
