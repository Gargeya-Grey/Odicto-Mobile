import {
  Capacitor,
  registerPlugin,
  type PluginListenerHandle,
} from '@capacitor/core';

export type OnboardingStatus = {
  microphone: boolean;
  imeEnabled: boolean;
  imeSelected: boolean;
  overlay: boolean;
  notifications: boolean;
  /** Whether this build ships the floating microphone at all. */
  overlaySupported: boolean;
};
const plugin = registerPlugin<{
  status(): Promise<OnboardingStatus>;
  requestMicrophone(): Promise<void>;
  requestNotifications(): Promise<void>;
  openKeyboardSettings(): Promise<void>;
  showKeyboardPicker(): Promise<void>;
  openOverlaySettings(): Promise<void>;
  addListener(
    eventName: 'statusChanged',
    listener: () => void,
  ): Promise<PluginListenerHandle>;
}>('OdictoOnboarding');
export const onboarding = {
  supported: Capacitor.getPlatform() === 'android',
  status: () => plugin.status(),
  requestMicrophone: () => plugin.requestMicrophone(),
  requestNotifications: () => plugin.requestNotifications(),
  openKeyboardSettings: () => plugin.openKeyboardSettings(),
  showKeyboardPicker: () => plugin.showKeyboardPicker(),
  openOverlaySettings: () => plugin.openOverlaySettings(),
  /** Fires when the default or enabled keyboards change, e.g. after the system picker. */
  onStatusChanged: (listener: () => void) =>
    plugin.addListener('statusChanged', listener),
};
