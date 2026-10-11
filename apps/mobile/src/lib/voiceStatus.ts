import type { OnboardingStatus } from '../native/onboarding';
import type { VoiceSettings } from '../native/voice';

export type ReadinessItem = {
  key: 'keyboard' | 'microphone' | 'floating';
  label: string;
  state: string;
  ok: boolean;
};

export type Readiness = {
  tone: 'ready' | 'paused' | 'setup';
  headline: string;
  detail: string;
  items: ReadinessItem[];
};

/** What is ready right now, from the saved settings and the Android setup status. */
export function summarizeReadiness(
  settings: Pick<VoiceSettings, 'enabled' | 'paused'> | null,
  status: OnboardingStatus | null,
): Readiness | null {
  if (!settings || !status) return null;
  const keyboardReady = status.imeEnabled && status.imeSelected;
  const items: ReadinessItem[] = [
    {
      key: 'keyboard',
      label: 'Keyboard',
      state: keyboardReady
        ? 'Ready'
        : status.imeEnabled
          ? 'Not selected'
          : 'Not enabled',
      ok: keyboardReady,
    },
    {
      key: 'microphone',
      label: 'Microphone',
      state: status.microphone ? 'Allowed' : 'Needs permission',
      ok: status.microphone,
    },
  ];
  if (status.overlaySupported) {
    const floatingOn = settings.enabled && status.overlay && !settings.paused;
    items.push({
      key: 'floating',
      label: 'Floating mic',
      state: settings.paused
        ? 'Paused'
        : settings.enabled && !status.overlay
          ? 'Needs permission'
          : settings.enabled
            ? 'On'
            : 'Off',
      ok: floatingOn,
    });
  }
  if (settings.paused) {
    return {
      tone: 'paused',
      headline: 'Odicto is paused',
      detail:
        'The floating microphone and voice typing in other keyboards are off.',
      items,
    };
  }
  if (!keyboardReady || !status.microphone) {
    return {
      tone: 'setup',
      headline: 'Finish setup',
      detail: 'Complete the steps below to start dictating.',
      items,
    };
  }
  return {
    tone: 'ready',
    headline: 'Ready to dictate',
    detail:
      status.overlaySupported && settings.enabled && status.overlay
        ? 'The keyboard and the floating microphone are on.'
        : 'Use the microphone on the Odicto keyboard.',
    items,
  };
}
