import { DEFAULT_GEMINI_MODEL } from './geminiModels';
import type { VoiceSettings } from '../native/voice';

export type PolishProvider = VoiceSettings['polishProvider'];
export type PolishModels = Partial<Record<PolishProvider, string>>;

const DEFAULT_MODELS: Record<PolishProvider, string> = {
  gemini: DEFAULT_GEMINI_MODEL,
  openrouter: 'poolside/laguna-xs-2.1',
};

export function switchPolishProvider(
  models: PolishModels,
  currentProvider: PolishProvider,
  currentModel: string,
  nextProvider: PolishProvider,
  saved?: { provider: PolishProvider; model: string },
) {
  const remembered = {
    ...(saved ? { [saved.provider]: saved.model } : {}),
    ...models,
    [currentProvider]: currentModel,
  };
  return {
    remembered,
    model: remembered[nextProvider] ?? DEFAULT_MODELS[nextProvider],
  };
}
