import React, { useEffect, useState } from 'react';
import { onboarding } from '../native/onboarding';

type Status = {
  microphone: boolean;
  imeEnabled: boolean;
  imeSelected: boolean;
  overlay: boolean;
  notifications: boolean;
};
export function AndroidOnboarding() {
  const [status, setStatus] = useState<Status | null>(null);
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
          Enable and select the keyboard first. The floating microphone is
          optional; the keyboard microphone remains available without overlay
          permission.
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
      {row(
        Boolean(status?.overlay),
        'Allow floating microphone',
        onboarding.openOverlaySettings,
        true,
      )}
      {row(
        Boolean(status?.notifications),
        'Allow recording notifications',
        onboarding.requestNotifications,
        true,
      )}
      <p className="text-[10px] text-[#9ca3af]">
        Odicto hides voice controls in password, phone, numeric/payment-like,
        and protected fields.
      </p>
    </section>
  );
}
