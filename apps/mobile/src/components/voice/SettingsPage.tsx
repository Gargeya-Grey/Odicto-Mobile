import { useEffect, useMemo, useRef, useState } from 'react';
import { modelGroups, modelLabel, MAX_MODEL_ID } from '../../lib/modelGroups';
import type { Appearance, Point } from '../../lib/theme';
import { checkResultLine, updateSettingLine } from '../../lib/updateCard';
import { formatRelativeTime } from '../../lib/relativeTime';
import type { OnboardingStatus } from '../../native/onboarding';
import type { UpdateStatus } from '../../native/update';
import type { OpenRouterModel, VoiceSettings } from '../../native/voice';
import { AndroidOnboarding } from '../AndroidOnboarding';
import {
  Chip,
  Footer,
  Group,
  Row,
  SectionLabel,
  Segmented,
  Switch,
} from './controls';
import { useNow } from './HistoryEntry';
import { Select, type SelectGroup } from './Select';
import {
  DEFAULT_OPENROUTER_MODEL,
  type KeyName,
  type useVoiceSettings,
} from './useVoiceSettings';
import type { useSettingsDraft } from './useSettingsDraft';

const SECTIONS = [
  { id: 'voice-keyboard', label: 'Keyboard' },
  { id: 'voice-connections', label: 'Connections' },
  { id: 'voice-ai', label: 'AI answers' },
  { id: 'voice-polish', label: 'Text polish' },
  { id: 'voice-live', label: 'Live' },
  { id: 'voice-appearance', label: 'Appearance' },
  { id: 'voice-updates', label: 'Updates' },
  { id: 'voice-setup', label: 'Android setup' },
] as const;

const prefersReducedMotion = () =>
  window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/** Sticky pill row. It follows the section in view and jumps to a tapped one. */
function SectionNav() {
  const [active, setActive] = useState<string>(SECTIONS[0].id);
  const nav = useRef<HTMLElement>(null);

  useEffect(() => {
    // Scroll events already arrive once per frame, and six rect reads are cheap.
    const measure = () => {
      const atBottom =
        window.innerHeight + window.scrollY >=
        document.documentElement.scrollHeight - 4;
      let current: string = SECTIONS[0].id;
      for (const section of SECTIONS) {
        const element = document.getElementById(section.id);
        if (element && element.getBoundingClientRect().top <= 120)
          current = section.id;
      }
      setActive(atBottom ? SECTIONS[SECTIONS.length - 1].id : current);
    };
    // The bar only gets a backdrop while it is stuck to the top edge.
    const stick = () =>
      nav.current?.toggleAttribute(
        'data-stuck',
        nav.current.getBoundingClientRect().top <= 0.5,
      );
    const onScroll = () => {
      measure();
      stick();
    };
    stick();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    const row = nav.current;
    const pill = row?.querySelector<HTMLElement>('[aria-current="location"]');
    if (!row || !pill) return;
    const target = pill.offsetLeft - (row.clientWidth - pill.offsetWidth) / 2;
    row.scrollTo({ left: target, behavior: 'auto' });
  }, [active]);

  return (
    <nav className="voice-pillnav" aria-label="Settings sections" ref={nav}>
      {SECTIONS.map((section) => (
        <a
          key={section.id}
          href={`#${section.id}`}
          aria-current={active === section.id ? 'location' : undefined}
          onClick={(event) => {
            event.preventDefault();
            setActive(section.id);
            document.getElementById(section.id)?.scrollIntoView({
              behavior: prefersReducedMotion() ? 'auto' : 'smooth',
              block: 'start',
            });
          }}
        >
          {section.label}
        </a>
      ))}
    </nav>
  );
}

type Voice = ReturnType<typeof useVoiceSettings>;
type Draft = ReturnType<typeof useSettingsDraft>;

function KeyRow({
  label,
  name,
  isSet,
  draft,
  placeholderSet,
  placeholderUnset,
  hint,
  onRemove,
}: {
  label: string;
  name: KeyName;
  isSet: boolean;
  draft: Draft;
  placeholderSet: string;
  placeholderUnset: string;
  hint: string;
  onRemove: () => void;
}) {
  return (
    <Row
      title={label}
      asLabel
      badge={isSet ? <Chip>Saved</Chip> : undefined}
      action={
        isSet ? (
          <button
            type="button"
            className="voice-link"
            aria-label={`Remove saved ${label.replace(/ key$/, '')} key`}
            onClick={onRemove}
          >
            Remove
          </button>
        ) : undefined
      }
      info={hint}
      below={({ id, describedBy }) => (
        <input
          id={id}
          className="voice-input"
          type="password"
          value={draft.keys[name]}
          onChange={(event) => draft.setKey(name, event.target.value)}
          placeholder={isSet ? placeholderSet : placeholderUnset}
          aria-describedby={describedBy}
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
        />
      )}
    />
  );
}

const PROVIDERS: SelectGroup[] = [
  {
    options: [
      { value: 'gemini', label: 'Gemini' },
      { value: 'openrouter', label: 'OpenRouter' },
    ],
  },
];

const AUTO_LABEL = 'Auto · Laguna XS (free)';
const AUTO_OPTION = { value: DEFAULT_OPENROUTER_MODEL, label: AUTO_LABEL };

export function SettingsPage({
  settings,
  status,
  update,
  removeKey,
  draft,
  models,
  loadingModels,
  onRefreshStatus,
  appearance,
  onSetAppearance,
  appUpdate: updateStatus,
  onSetUpdateEnabled,
  onCheckUpdate,
  checkingUpdate,
}: {
  settings: VoiceSettings | null;
  status: OnboardingStatus | null;
  update: Voice['update'];
  removeKey: Voice['removeKey'];
  draft: Draft;
  models: OpenRouterModel[];
  loadingModels: boolean;
  onRefreshStatus: () => Promise<void> | void;
  appearance: Appearance;
  onSetAppearance: (value: Appearance, origin?: Point) => void;
  appUpdate: UpdateStatus | null;
  onSetUpdateEnabled: (enabled: boolean) => void;
  onCheckUpdate: () => void;
  checkingUpdate: boolean;
}) {
  const now = useNow();
  const [modelSearch, setModelSearch] = useState('');
  const themeOrigin = useRef<Point | undefined>(undefined);
  const provider = draft.value('provider') ?? 'gemini';
  const model = draft.value('model') ?? '';
  const polishModel = draft.value('polishModel') ?? 'poolside/laguna-xs-2.1';
  const [polishSearch, setPolishSearch] = useState('');
  const answerGroups = useMemo<SelectGroup[]>(
    () => modelGroups(models, modelSearch, { pinned: AUTO_OPTION }),
    [models, modelSearch],
  );
  const polishGroups = useMemo<SelectGroup[]>(
    () => modelGroups(models, polishSearch, { allowCustom: true }),
    [models, polishSearch],
  );
  const polishCatalogModel = models.find((item) => item.id === polishModel);
  const selectedModel = model || DEFAULT_OPENROUTER_MODEL;
  const selectedModelLabel = models.find((item) => item.id === selectedModel);
  const off = !settings;
  const modelHint =
    provider === 'openrouter'
      ? loadingModels
        ? 'Loading the current OpenRouter model catalog…'
        : models.length > 0
          ? 'Models are loaded from OpenRouter. Requests prefer the lowest-latency provider with low thinking enabled.'
          : settings?.openrouterKeySet
            ? 'Could not load OpenRouter models. Save a valid key and try again.'
            : 'Save your OpenRouter key to load its live model catalog.'
      : undefined;

  return (
    <>
      <SectionNav />

      <section
        className="voice-section"
        id="voice-keyboard"
        aria-labelledby="voice-keyboard-title"
      >
        <SectionLabel id="voice-keyboard-title" title="Keyboard" />
        <Group>
          <Row
            title="Touch feedback"
            secondary="How strongly keys and controls vibrate."
            below={({ describedBy }) => (
              <Segmented
                label="Touch feedback strength"
                describedBy={describedBy}
                disabled={off}
                value={settings?.hapticLevel ?? 2}
                options={[
                  { value: 0, label: 'Off' },
                  { value: 1, label: 'Light' },
                  { value: 2, label: 'Medium' },
                  { value: 3, label: 'Strong' },
                ]}
                onChange={(hapticLevel) =>
                  void update({ hapticLevel }, { hapticAfterSave: true })
                }
              />
            )}
          />
          <Row
            title="Key size"
            secondary="Height of the keys and the letters on them."
            below={({ describedBy }) => (
              <Segmented
                label="Key size"
                describedBy={describedBy}
                disabled={off}
                value={settings?.keySize ?? 'medium'}
                options={[
                  { value: 'small', label: 'S', name: 'Small' },
                  { value: 'medium', label: 'M', name: 'Medium' },
                  { value: 'large', label: 'L', name: 'Large' },
                ]}
                onChange={(keySize) => void update({ keySize })}
              />
            )}
          />
          <Row
            title="Tap tolerance"
            secondary="How much of each key’s touch cell counts."
            info={
              <>
                Keep the visible gaps. Forgiving uses each key’s full touch
                cell; Precise uses only the visible key. Touch regions never
                overlap. This does not change hardware touch sensitivity.
                <br />
                <br />
                Using a screen protector on Samsung? Try Settings &gt; Display
                &gt; Touch sensitivity, if available. Odicto does not change
                this system setting.
              </>
            }
            below={({ describedBy }) => (
              <Segmented
                label="Tap tolerance"
                describedBy={describedBy}
                disabled={off}
                value={settings?.tapTolerance ?? 2}
                options={[
                  { value: 0, label: 'Precise' },
                  { value: 1, label: 'Balanced' },
                  { value: 2, label: 'Forgiving' },
                ]}
                onChange={(tapTolerance) => void update({ tapTolerance })}
              />
            )}
          />
          <Row
            title="Key font"
            secondary="Typeface on the keys."
            info="System is the Android default. Instrument Sans is Odicto's own face."
            below={({ describedBy }) => (
              <Segmented
                label="Key font"
                describedBy={describedBy}
                disabled={off}
                value={settings?.keyFont ?? 'instrument'}
                options={[
                  { value: 'system', label: 'System' },
                  { value: 'instrument', label: 'Instrument Sans' },
                ]}
                onChange={(keyFont) => void update({ keyFont })}
              />
            )}
          />
          <Row
            title="Autocorrect (English)"
            secondary="Fixes common typos on this device."
            info="Press Backspace after a correction to undo it. Suggestions appear with the keyboard expanded."
            trailing={({ describedBy }) => (
              <Switch
                label="Autocorrect (English)"
                describedBy={describedBy}
                disabled={off}
                checked={settings?.autocorrectEnabled ?? false}
                onChange={(autocorrectEnabled) =>
                  void update({ autocorrectEnabled })
                }
              />
            )}
          />
          <Row
            title="Show text preview"
            secondary="Words under the voice status."
            info="Show processed words below the voice status. Turn off for smaller controls; words still go to your editor and history."
            trailing={({ describedBy }) => (
              <Switch
                label="Show text preview"
                describedBy={describedBy}
                disabled={off}
                checked={settings?.showTextPreview ?? true}
                onChange={(showTextPreview) => void update({ showTextPreview })}
              />
            )}
          />
        </Group>
      </section>

      <section
        className="voice-section"
        id="voice-connections"
        aria-labelledby="voice-connections-title"
      >
        <SectionLabel
          id="voice-connections-title"
          title="Connections"
          info="Your phone connects directly to Groq, Gemini, and OpenRouter. Add a key once, then leave its field empty to keep it saved. No computer or Odicto server is needed."
        />
        <Group>
          <KeyRow
            label="Groq key"
            name="groqKey"
            isSet={settings?.groqKeySet ?? false}
            draft={draft}
            placeholderSet="Enter a new key to replace saved key"
            placeholderUnset="Required for Raw and AI speech"
            hint="Raw uses Groq Whisper. AI uses Groq for speech, then your selected answer provider."
            onRemove={() => void removeKey('groqKey')}
          />
          <KeyRow
            label="Gemini key"
            name="geminiKey"
            isSet={settings?.geminiKeySet ?? false}
            draft={draft}
            placeholderSet="Enter a new key to replace saved key"
            placeholderUnset="Required for Live and Gemini AI"
            hint="Gemini powers Live transcription and Gemini AI answers."
            onRemove={() => void removeKey('geminiKey')}
          />
          <KeyRow
            label="OpenRouter key"
            name="openrouterKey"
            isSet={settings?.openrouterKeySet ?? false}
            draft={draft}
            placeholderSet="Enter a new key to replace saved key"
            placeholderUnset="Only needed for OpenRouter AI"
            hint="OpenRouter powers its AI answers and text polish."
            onRemove={() => void removeKey('openrouterKey')}
          />
        </Group>
        <Footer>
          Your phone connects directly to Groq, Gemini, and OpenRouter, with no
          Odicto server in between. Keys are stored using Android Keystore and
          sent only for your selected provider over HTTPS. Audio stays only for
          the current request.
        </Footer>
      </section>

      <section
        className="voice-section"
        id="voice-ai"
        aria-labelledby="voice-ai-title"
      >
        <SectionLabel
          id="voice-ai-title"
          title="AI answers"
          info="AI selects the whole field unless you have chosen a range. Keep the selection unchanged until the result replaces it; otherwise the result is saved to copy."
        />
        <Group>
          <Row
            title="AI system prompt"
            asLabel
            info="Saved on this device and sent only with AI requests. Leave blank to use Odicto's built-in instructions, which already handle editing and drafting, keep your language, and return only the text to insert. A prompt you write replaces them. Maximum 8,000 characters; selected text is limited to 20,000 characters."
            below={({ id, describedBy }) => (
              <textarea
                id={id}
                className="voice-input"
                value={draft.value('systemPrompt') ?? ''}
                onChange={(event) =>
                  draft.setFields({ systemPrompt: event.target.value })
                }
                maxLength={8000}
                rows={4}
                aria-describedby={describedBy}
                placeholder="Blank uses the built-in instructions. Example of your own: Reply in British English and keep it brief."
              />
            )}
          />
          <Row
            title="Provider"
            asLabel
            below={({ id, labelId }) => (
              <Select
                id={id}
                labelledBy={labelId}
                groups={PROVIDERS}
                value={provider}
                disabled={off}
                onChange={(next) =>
                  draft.setFields({
                    provider: next as VoiceSettings['provider'],
                    model:
                      next === 'openrouter' ? DEFAULT_OPENROUTER_MODEL : '',
                  })
                }
              />
            )}
          />
          <Row
            title="Model"
            asLabel
            hint={modelHint}
            below={({ id, labelId, describedBy }) =>
              provider === 'openrouter' ? (
                <Select
                  id={id}
                  labelledBy={labelId}
                  describedBy={describedBy}
                  groups={answerGroups}
                  value={selectedModel}
                  valueLabel={
                    selectedModel === DEFAULT_OPENROUTER_MODEL
                      ? AUTO_LABEL
                      : selectedModelLabel
                        ? modelLabel(selectedModelLabel)
                        : selectedModel
                  }
                  disabled={off || loadingModels}
                  onChange={(next) => draft.setFields({ model: next })}
                  emptyText="No matching models"
                  search={{
                    value: modelSearch,
                    onChange: setModelSearch,
                    label: 'Search OpenRouter models',
                    placeholder: 'Search models',
                  }}
                />
              ) : (
                <input
                  id={id}
                  className="voice-input"
                  value={model}
                  onChange={(event) =>
                    draft.setFields({ model: event.target.value })
                  }
                  placeholder="gemini-3.5-flash-lite"
                  autoCapitalize="none"
                  spellCheck={false}
                />
              )
            }
          />
        </Group>
        <Footer>
          AI uses the whole field unless you select part of it. That text goes
          to your AI provider with your spoken instruction.
        </Footer>
      </section>

      <section
        className="voice-section"
        id="voice-polish"
        aria-labelledby="voice-polish-title"
      >
        <SectionLabel id="voice-polish-title" title="Text polish" />
        <Group>
          <Row
            title="OpenRouter key"
            badge={settings?.openrouterKeySet ? <Chip>Saved</Chip> : undefined}
            secondary={
              settings?.openrouterKeySet
                ? 'Your key is saved securely under Connections.'
                : 'Save a key under Connections to use text polish.'
            }
          />
          <Row
            title="Model"
            asLabel
            hint={
              loadingModels
                ? 'Loading the current OpenRouter model catalog…'
                : models.length > 0
                  ? 'Search by name or ID. To use a model that is not listed, type its full ID, such as vendor/model.'
                  : 'The catalog did not load. Type a full model ID, such as vendor/model.'
            }
            below={({ id, labelId, describedBy }) => (
              <Select
                id={id}
                labelledBy={labelId}
                describedBy={describedBy}
                groups={polishGroups}
                value={polishModel}
                valueLabel={
                  polishCatalogModel
                    ? modelLabel(polishCatalogModel)
                    : polishModel
                }
                disabled={off || loadingModels}
                onChange={(next) =>
                  draft.setFields({ polishModel: next.slice(0, MAX_MODEL_ID) })
                }
                emptyText="No matching models. Type a full ID, such as vendor/model."
                search={{
                  value: polishSearch,
                  onChange: setPolishSearch,
                  label: 'Search OpenRouter models for text polish',
                  placeholder: 'Search or type a model ID',
                }}
              />
            )}
          />
          <Row
            title="Text polish system prompt"
            asLabel
            info="Leave the prompt blank to use the built-in rules: fix spelling, grammar, punctuation and capitalization only, keep your wording, tone and language (including Hinglish), and leave names, links, @mentions and emojis alone. A prompt you write replaces them. A model can still make mistakes; review its result. Fields over 20,000 characters are refused rather than partially rewritten."
            below={({ id, describedBy }) => (
              <textarea
                id={id}
                className="voice-input"
                value={draft.value('polishSystemPrompt') ?? ''}
                onChange={(event) =>
                  draft.setFields({ polishSystemPrompt: event.target.value })
                }
                maxLength={8000}
                rows={4}
                aria-describedby={describedBy}
                placeholder="Blank uses the built-in proofreading rules."
              />
            )}
          />
        </Group>
        <Footer>
          Tap Polish beside the keyboard mic to correct the whole text field
          with OpenRouter. Typing alone sends nothing. Password and protected
          fields are refused.
        </Footer>
      </section>

      <section
        className="voice-section"
        id="voice-live"
        aria-labelledby="voice-live-title"
      >
        <SectionLabel id="voice-live-title" title="Live transcription" />
        <Group>
          <Row
            title="Gemini Live model"
            asLabel
            below={({ id }) => (
              <input
                id={id}
                className="voice-input"
                value={draft.value('liveModel') ?? ''}
                onChange={(event) =>
                  draft.setFields({ liveModel: event.target.value })
                }
                placeholder="gemini-3.5-transcribe-live"
                autoCapitalize="none"
                spellCheck={false}
              />
            )}
          />
        </Group>
        <Footer>
          Live streams speech directly to Gemini and updates words as you speak.
        </Footer>
      </section>

      <section
        className="voice-section"
        id="voice-appearance"
        aria-labelledby="voice-appearance-title"
      >
        <SectionLabel id="voice-appearance-title" title="Appearance" />
        <Group>
          <Row
            title="Theme"
            secondary="Matches your phone by default."
            info="The navbar button switches between light and dark. Choose System here to follow your phone again. The keyboard and floating microphone follow this setting too."
            below={({ describedBy }) => (
              // The new theme grows from the segment that was tapped.
              <div
                onClickCapture={(event) => {
                  const rect = (event.target as Element)
                    .closest('button')
                    ?.getBoundingClientRect();
                  if (rect)
                    themeOrigin.current = {
                      x: rect.left + rect.width / 2,
                      y: rect.top + rect.height / 2,
                    };
                }}
              >
                <Segmented
                  label="Theme"
                  describedBy={describedBy}
                  value={appearance}
                  options={[
                    { value: 'system', label: 'System' },
                    { value: 'light', label: 'Light' },
                    { value: 'dark', label: 'Dark' },
                  ]}
                  onChange={(value) =>
                    onSetAppearance(value, themeOrigin.current)
                  }
                />
              </div>
            )}
          />
        </Group>
      </section>

      {updateStatus && (
        <section
          className="voice-section"
          id="voice-updates"
          aria-labelledby="voice-updates-title"
        >
          <SectionLabel id="voice-updates-title" title="Updates" />
          <Group>
            <Row
              title="Check for updates"
              secondary={updateSettingLine(updateStatus.enabled)}
              info="Asks GitHub for the newest Odicto release number and shows a card on Home if there is one. Nothing about you or your phone is sent. Nothing is downloaded or installed until you tap Download, and Android does the install."
              trailing={({ describedBy }) => (
                <Switch
                  label="Check for updates"
                  describedBy={describedBy}
                  checked={updateStatus.enabled}
                  onChange={onSetUpdateEnabled}
                />
              )}
            />
            <Row
              title="Check now"
              secondary={checkResultLine(updateStatus, now, formatRelativeTime)}
              trailing={() => (
                <button
                  type="button"
                  className="voice-link"
                  disabled={checkingUpdate || !updateStatus.enabled}
                  onClick={onCheckUpdate}
                >
                  {checkingUpdate ? 'Checking…' : 'Check'}
                </button>
              )}
            />
            {updateStatus.current && (
              <Row title="Version" secondary={updateStatus.current} />
            )}
          </Group>
        </section>
      )}

      <AndroidOnboarding status={status} onRefresh={onRefreshStatus} />
    </>
  );
}
