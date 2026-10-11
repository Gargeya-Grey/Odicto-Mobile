import { useState } from 'react';
import { onboarding, type OnboardingStatus } from '../native/onboarding';
import { Check, ChevronRight, Info } from './voice/icons';
import { Footer, Group, SectionLabel } from './voice/controls';

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
  const [disclosure, setDisclosure] = useState(false);
  const [accessibilityAttempt, setAccessibilityAttempt] = useState(false);
  const [optionalOpen, setOptionalOpen] = useState(false);
  if (!onboarding.supported) return null;
  const overlaySupported = status?.overlaySupported ?? false;
  const accessibilitySupported = status?.accessibilitySupported ?? false;

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
  if (accessibilitySupported) {
    steps.push({
      key: 'accessibility',
      ready: Boolean(status?.accessibility),
      label: 'Allow voice typing in other keyboards',
      action: 'Review',
      run: async () => setDisclosure(true),
      optional: true,
    });
  }

  const required = steps.filter((step) => !step.optional);
  const optional = steps.filter((step) => step.optional);
  const requiredDone = status !== null && required.every((step) => step.ready);
  const listed = requiredDone ? (optionalOpen ? optional : []) : steps;

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
        {requiredDone && optional.length === 0 && (
          <li className="voice-row voice-step" data-ready="true">
            <span className="voice-step-mark" aria-hidden="true">
              <Check size={14} />
            </span>
            <span className="voice-step-label">Setup complete</span>
          </li>
        )}
        {requiredDone && optional.length > 0 && (
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
      </Group>
      {!requiredDone && status !== null && (
        <Footer>
          {accessibilitySupported
            ? 'Enable and select the keyboard first. The floating microphone is optional.'
            : 'Enable and select the keyboard. That is all Odicto needs: it types, and its microphone dictates directly into the field you are using.'}
        </Footer>
      )}
      {accessibilitySupported && disclosure && (
        <div
          className="voice-group voice-panel"
          role="group"
          aria-labelledby="voice-disclosure-title"
        >
          <h3 id="voice-disclosure-title">Turn on Odicto voice typing?</h3>
          <p>
            Odicto needs Android&apos;s accessibility permission so the floating
            microphone can type into the field you are using while another
            keyboard is active. Odicto reads only the focused text field, to
            place your dictation and to skip password, phone, and numeric
            fields. It never reads or stores the rest of the screen.
          </p>
          <div className="voice-actions">
            <button
              type="button"
              className="voice-btn voice-btn-primary"
              onClick={() => {
                setDisclosure(false);
                setAccessibilityAttempt(true);
                run(onboarding.openAccessibilitySettings);
              }}
            >
              Agree and open settings
            </button>
            <button
              type="button"
              className="voice-btn voice-btn-tertiary"
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
          <p className="voice-callout">
            <Info size={16} />
            <span>
              Toggle greyed out or blocked? Android restricts accessibility for
              apps installed outside a store. Open App info, use the ⋮ menu to
              allow restricted settings, and turn Samsung Auto Blocker off first
              if that option is unavailable. Store installs skip this step.
            </span>
          </p>
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
