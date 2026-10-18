import { Capacitor, registerPlugin } from '@capacitor/core';
import type { Appearance } from '../lib/theme';

const plugin = registerPlugin<{
  get(): Promise<{ appearance: Appearance }>;
  set(options: { appearance: Appearance }): Promise<{ appearance: Appearance }>;
}>('OdictoAppearance');

export const appearanceStore = {
  supported: Capacitor.getPlatform() === 'android',
  /** The saved choice. The keyboard and floating microphone read the same one. */
  get: () => plugin.get(),
  set: (appearance: Appearance) => plugin.set({ appearance }),
};
