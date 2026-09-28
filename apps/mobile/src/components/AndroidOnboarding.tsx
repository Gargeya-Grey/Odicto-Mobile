import React, { useEffect, useState } from 'react';
import { onboarding, type OnboardingStatus } from '../native/onboarding';

export function AndroidOnboarding() {
  const [status, setStatus] = useState<OnboardingStatus | null>(null);
  const [disclosure, setDisclosure] = useState(false);
  const [accessibilityAttempt, setAccessibilityAttempt] = useState(false);
  const refresh = () =>
    onboarding
      .status()
      .then(setStatus)
      .catch(() => setStatus(null));
  useEffect(() => {
    if (onboarding.supported) refresh();
    const resume = () => {
      if (!document.hidden && onboarding.supported) refresh();
    };
    document.addEventListener('visibilitychange', resume);
    return () => document.removeEventListener('visibilitychange', resume);
  }, []);
  if (!onboarding.supported) return null;
  const overlaySupported = status?.overlaySupported ?? false;
  const accessibilitySupported = status?.accessibilitySupported ?? false;
  const row = (
    ready: boolean,
    label: string,
    action: () => Promise<void>,
    optional = false,
  ) => (
    <button
      type="button"
      onClick={() => action().then(refresh)}
      className="w-full flex items-center justify-between p-3 rounded-xl bg-[#0d0e15] border border-[rgba(255,255,255,0.08)]"
    >
      <span className="text-xs text-left">
        {label}
        {optional ? ' (optional)' : ''}
      </span>
      <span className={ready ? 'text-[#10b981]' : 'text-[#f59e0b]'}>
        {ready ? 'Ready' : 'Open'}
      </span>
    </button>
  );
  return (
    <section className="bg-[#11131c] border border-[rgba(255,255,255,0.08)] rounded-2xl p-4 space-y-3">
      <div className="voice-section-heading">
        <h3 className="text-sm font-semibold">Activate Odicto Keyboard</h3>
        <p className="text-xs text-[#9ca3af]">
          {accessibilitySupported
            ? 'Enable and select the keyboard first. The floating microphone is optional.'
            : 'Enable and select the keyboard. That is all Odicto needs: it types, and its microphone dictates directly into the field you are using.'}
        </p>
      </div>
      {row(
        Boolean(status?.microphone),
        'Allow microphone access',
        onboarding.requestMicrophone,
      )}
      {row(
        Boolean(status?.imeEnabled),
        'Enable Odicto Keyboard',
        onboarding.openKeyboardSettings,
      )}
      {row(
        Boolean(status?.imeSelected),
        'Select Odicto as current keyboard',
        onboarding.showKeyboardPicker,
      )}
      {overlaySupported &&
        row(
          Boolean(status?.overlay),
          'Allow floating microphone',
          onboarding.openOverlaySettings,
          true,
        )}
      {overlaySupported &&
        row(
          Boolean(status?.notifications),
          'Allow recording notifications',
          onboarding.requestNotifications,
          true,
        )}
      {accessibilitySupported &&
        row(
          Boolean(status?.accessibility),
          'Allow voice typing in other keyboards',
          async () => setDisclosure(true),
          true,
        )}
      {accessibilitySupported && disclosure && (
        <div className="bg-[#0d0e15] border border-[rgba(255,255,255,0.12)] rounded-xl p-3 space-y-3">
          <h4 className="text-xs font-semibold">
            Turn on Odicto voice typing?
          </h4>
          <p className="text-[11px] text-[#9ca3af]">
            Odicto needs Android&apos;s accessibility permission so the floating
            microphone can type into the field you are using while another
            keyboard is active. Odicto reads only the focused text field, to
            place your dictation and to skip password, phone, and numeric
            fields. It never reads or stores the rest of the screen.
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              className="flex-1 p-2 rounded-lg bg-[#6d28d9] text-xs font-semibold"
              onClick={() => {
                setDisclosure(false);
                setAccessibilityAttempt(true);
                void onboarding.openAccessibilitySettings().then(refresh);
              }}
            >
              Agree and open settings
            </button>
            <button
              type="button"
              className="flex-1 p-2 rounded-lg border border-[rgba(255,255,255,0.12)] text-xs"
              onClick={() => setDisclosure(false)}
            >
              Not now
            </button>
          </div>
        </div>
      )}
      {accessibilitySupported &&
        accessibilityAttempt &&
        status &&
        !status.accessibility && (
          <p className="text-[10px] text-[#f59e0b]">
            Toggle greyed out or blocked? Android restricts accessibility for
            apps installed outside a store. Open App info, use the ⋮ menu to
            allow restricted settings, and turn Samsung Auto Blocker off first
            if that option is unavailable. Store installs skip this step.
          </p>
        )}
      <p className="text-[10px] text-[#9ca3af]">
        Odicto disables voice input in password, phone, numeric/payment-like,
        and protected fields.
      </p>
    </section>
  );
}
