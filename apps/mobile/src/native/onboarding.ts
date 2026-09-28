import { Capacitor, registerPlugin } from '@capacitor/core';

export type OnboardingStatus = {
  microphone: boolean;
  imeEnabled: boolean;
  imeSelected: boolean;
  overlay: boolean;
  notifications: boolean;
  accessibility: boolean;
  /** Whether this build ships the floating microphone at all. */
  overlaySupported: boolean;
  /** Whether this build ships an accessibility service at all. */
  accessibilitySupported: boolean;
};
const plugin = registerPlugin<{
  status(): Promise<OnboardingStatus>;
  requestMicrophone(): Promise<void>;
  requestNotifications(): Promise<void>;
  openKeyboardSettings(): Promise<void>;
  showKeyboardPicker(): Promise<void>;
  openOverlaySettings(): Promise<void>;
  openAccessibilitySettings(): Promise<void>;
}>('OdictoOnboarding');
export const onboarding = {
  supported: Capacitor.getPlatform() === 'android',
  status: () => plugin.status(),
  requestMicrophone: () => plugin.requestMicrophone(),
  requestNotifications: () => plugin.requestNotifications(),
  openKeyboardSettings: () => plugin.openKeyboardSettings(),
  showKeyboardPicker: () => plugin.showKeyboardPicker(),
  openOverlaySettings: () => plugin.openOverlaySettings(),
  openAccessibilitySettings: () => plugin.openAccessibilitySettings(),
};
