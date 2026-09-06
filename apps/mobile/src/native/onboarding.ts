import { Capacitor, registerPlugin } from '@capacitor/core';

type Status = {
  microphone: boolean;
  imeEnabled: boolean;
  imeSelected: boolean;
  overlay: boolean;
  notifications: boolean;
};
const plugin = registerPlugin<{
  status(): Promise<Status>;
  requestMicrophone(): Promise<void>;
  requestNotifications(): Promise<void>;
  openKeyboardSettings(): Promise<void>;
  showKeyboardPicker(): Promise<void>;
  openOverlaySettings(): Promise<void>;
}>('OdictoOnboarding');
export const onboarding = {
  supported: Capacitor.getPlatform() === 'android',
  status: () => plugin.status(),
  requestMicrophone: () => plugin.requestMicrophone(),
  requestNotifications: () => plugin.requestNotifications(),
  openKeyboardSettings: () => plugin.openKeyboardSettings(),
  showKeyboardPicker: () => plugin.showKeyboardPicker(),
  openOverlaySettings: () => plugin.openOverlaySettings(),
};
