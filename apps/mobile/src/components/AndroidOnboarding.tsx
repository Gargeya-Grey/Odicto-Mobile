import { useState } from 'react';
import { onboarding, type OnboardingStatus } from '../native/onboarding';
import { Check, ChevronRight } from './voice/icons';
import { Footer, Group, Row, SectionLabel, Switch } from './voice/controls';
import {
  CLIPBOARD_CONSENT_TEXT,
  CLIPBOARD_CONSENT_TITLE,
  describeClipboardSaving,
} from '../lib/clipboardConsent';

type Step = {
  key: string;
  ready: boolean;
  label: string;
  action: string;
  run: () => Promise<void>;
  optional?: boolean;
};

/**
 * Android setup: one row per step, with a clear done / to do mark. While a
 * required step is open the whole list shows; when all required steps are
 * done, one "Setup complete" row remains and the optional steps fold away.
 * The parent owns the status so Home, Settings, and this list never disagree.
 */
export function AndroidOnboarding({
  status = null,
  onRefresh = () => undefined,
  note = true,
}: {
  status?: OnboardingStatus | null;
  onRefresh?: () => Promise<void> | void;
  /** Show the protected-fields sentence under the list. */
  note?: boolean;
}) {
  const [optionalOpen, setOptionalOpen] = useState(false);
  if (!onboarding.supported) return null;
  const overlaySupported = status?.overlaySupported ?? false;

  const run = (action: () => Promise<void>) =>
    void action()
      .catch(() => undefined)
      .then(onRefresh);

  const steps: Step[] = [
    {
      key: 'microphone',
      ready: Boolean(status?.microphone),
      label: 'Allow microphone access',
      action: 'Allow',
      run: onboarding.requestMicrophone,
    },
    {
      key: 'ime-enabled',
      ready: Boolean(status?.imeEnabled),
      label: 'Enable Odicto Keyboard',
      action: 'Enable',
      run: onboarding.openKeyboardSettings,
    },
    {
      key: 'ime-selected',
      ready: Boolean(status?.imeSelected),
      label: 'Select Odicto as current keyboard',
      action: 'Select',
      run: onboarding.showKeyboardPicker,
    },
  ];
  if (overlaySupported) {
    steps.push(
      {
        key: 'overlay',
        ready: Boolean(status?.overlay),
        label: 'Allow floating microphone',
        action: 'Allow',
        run: onboarding.openOverlaySettings,
        optional: true,
      },
      {
        key: 'notifications',
        ready: Boolean(status?.notifications),
        label: 'Allow recording notifications',
        action: 'Allow',
        run: onboarding.requestNotifications,
        optional: true,
      },
    );
  }
  const required = steps.filter((step) => !step.optional);
  const optional = steps.filter((step) => step.optional);
  const requiredDone = status !== null && required.every((step) => step.ready);
  const listed = requiredDone ? (optionalOpen ? optional : []) : steps;
  const showConsent = status !== null && (!requiredDone || optionalOpen);
  const consent = describeClipboardSaving(
    status?.clipboardSaving ?? null,
    Boolean(status?.imeSelected),
  );

  const stepRow = (step: Step) => (
    <li key={step.key}>
      <button
        type="button"
        className="voice-rowbtn voice-step"
        data-ready={step.ready ? 'true' : 'false'}
        onClick={() => run(step.run)}
      >
        <span className="voice-step-mark" aria-hidden="true">
          {step.ready ? <Check size={14} /> : null}
        </span>
        <span className="voice-step-text">
          <span className="voice-step-label">{step.label}</span>
          {step.optional && <span className="voice-step-note">Optional</span>}
        </span>
        <span className="voice-step-state">
          <span className="voice-sr">{step.ready ? 'Done. ' : 'To do. '}</span>
          {step.ready ? (
            'Done'
          ) : (
            <>
              {step.action}
              <ChevronRight size={16} />
            </>
          )}
        </span>
      </button>
    </li>
  );

  return (
    <section className="voice-section" id="voice-setup">
      <SectionLabel id="voice-setup-title" title="Android setup" />
      <Group labelledBy="voice-setup-title">
        {status === null && (
          <li className="voice-row">
            <p className="voice-row-note">Checking setup…</p>
          </li>
        )}
        {requiredDone && (
          <li>
            <button
              type="button"
              className="voice-rowbtn voice-step"
              data-ready="true"
              aria-expanded={optionalOpen}
              onClick={() => setOptionalOpen((value) => !value)}
            >
              <span className="voice-step-mark" aria-hidden="true">
                <Check size={14} />
              </span>
              <span className="voice-step-text">
                <span className="voice-step-label">Setup complete</span>
              </span>
              <span className="voice-step-state" data-open={optionalOpen}>
                Optional steps
                <ChevronRight size={16} />
              </span>
            </button>
          </li>
        )}
        {status !== null && listed.map(stepRow)}
        {showConsent && (
          <Row
            title={CLIPBOARD_CONSENT_TITLE}
            badge={<span className="voice-step-note">Optional</span>}
            secondary={consent.line}
            hint={CLIPBOARD_CONSENT_TEXT}
            trailing={({ describedBy }) => (
              <Switch
                label={CLIPBOARD_CONSENT_TITLE}
                describedBy={describedBy}
                checked={consent.checked}
                disabled={consent.disabled}
                onChange={(enabled) =>
                  run(() => onboarding.setClipboardSaving(enabled))
                }
              />
            )}
          />
        )}
      </Group>
      {!requiredDone && status !== null && (
        <Footer>
          Enable and select the keyboard. The floating microphone is optional
          and types through it, so it works wherever the Odicto keyboard does.
        </Footer>
      )}
      {note && (
        <Footer>
          Odicto disables voice input in password, phone, numeric/payment-like,
          and protected fields.
        </Footer>
      )}
    </section>
  );
}
