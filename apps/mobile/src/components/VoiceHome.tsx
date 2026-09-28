import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ArrowLeft,
  ArrowUpRight,
  Check,
  Copy,
  Mic,
  Settings2,
  Sparkles,
  AudioLines,
  History,
  MoveUpRight,
} from 'lucide-react';
import {
  voice,
  type VoiceSettings,
  type HistoryItem,
  type OpenRouterModel,
} from '../native/voice';
import { AndroidOnboarding } from './AndroidOnboarding';
import { onboarding } from '../native/onboarding';
import { copyToClipboard } from '../lib/clipboard';
import '../voice.css';

export function VoiceHome() {
  const [settings, setSettings] = useState<VoiceSettings | null>(null);
  const [capabilities, setCapabilities] = useState<{
    overlay: boolean;
    accessibility: boolean;
  } | null>(null);
  const [page, setPage] = useState<'home' | 'settings' | 'history'>('home');
  const overlaySupported = capabilities?.overlay ?? false;
  const accessibilitySupported = capabilities?.accessibility ?? false;
  useEffect(() => {
    const root = document.documentElement;
    root.classList.toggle('voice-settings-scrolling', page === 'settings');
    return () => root.classList.remove('voice-settings-scrolling');
  }, [page]);
  const [entries, setEntries] = useState<HistoryItem[]>([]);
  const [notice, setNotice] = useState('');
  const [saving, setSaving] = useState(false);
  const [geminiKey, setGeminiKey] = useState('');
  const [groqKey, setGroqKey] = useState('');
  const [openrouterKey, setOpenrouterKey] = useState('');
  const [openRouterModels, setOpenRouterModels] = useState<OpenRouterModel[]>(
    [],
  );
  const [loadingOpenRouterModels, setLoadingOpenRouterModels] = useState(false);
  const [openRouterModelSearch, setOpenRouterModelSearch] = useState('');
  const openRouterModelGroups = useMemo(() => {
    const search = openRouterModelSearch.trim().toLowerCase();
    const models = openRouterModels
      .filter(
        (model) =>
          model.id !== 'poolside/laguna-xs-2.1:free' &&
          !model.name.toLowerCase().includes('(batch)'),
      )
      .filter(
        (model) =>
          !search ||
          model.name.toLowerCase().includes(search) ||
          model.id.toLowerCase().includes(search),
      )
      .sort((a, b) =>
        a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }),
      );
    const groups = new Map<string, OpenRouterModel[]>();
    models.forEach((model) => {
      const letter = model.name.charAt(0).toUpperCase() || '#';
      const group = groups.get(letter) ?? [];
      group.push(model);
      groups.set(letter, group);
    });
    return [...groups.entries()];
  }, [openRouterModels, openRouterModelSearch]);
  const refresh = useCallback(async () => {
    try {
      const value = await voice.status();
      setSettings(value);
      if (value.openSettings) setPage('settings');
    } catch {
      setNotice('Could not load settings. Reopen Odicto to try again.');
    }
    if (onboarding.supported) {
      try {
        const status = await onboarding.status();
        setCapabilities({
          overlay: status.overlaySupported,
          accessibility: status.accessibilitySupported,
        });
      } catch {
        setCapabilities(null);
      }
    }
  }, []);
  useEffect(() => {
    if (!settings?.openrouterKeySet) {
      setOpenRouterModels([]);
      return;
    }
    setSettings((current) =>
      current?.provider === 'openrouter' &&
      (current.model === 'openrouter/free' ||
        current.model.toLowerCase().includes('muse-spark'))
        ? { ...current, model: 'poolside/laguna-xs-2.1:free' }
        : current,
    );
    let current = true;
    setLoadingOpenRouterModels(true);
    void voice
      .openrouterModels()
      .then(({ models }) => {
        if (current) setOpenRouterModels(models);
      })
      .catch(() => {
        if (current) setOpenRouterModels([]);
      })
      .finally(() => {
        if (current) setLoadingOpenRouterModels(false);
      });
    return () => {
      current = false;
    };
  }, [settings?.openrouterKeySet]);
  useEffect(() => {
    void refresh();
    const open = () => {
      setPage('settings');
      void refresh();
    };
    const resume = () => {
      if (!document.hidden) void refresh();
    };
    window.addEventListener('odicto-settings', open);
    document.addEventListener('visibilitychange', resume);
    return () => {
      window.removeEventListener('odicto-settings', open);
      document.removeEventListener('visibilitychange', resume);
    };
  }, [refresh]);
  const save = async (
    patch: Partial<VoiceSettings> & {
      geminiKey?: string;
      groqKey?: string;
      openrouterKey?: string;
    },
  ) => {
    if (saving) return false;
    setSaving(true);
    setNotice('');
    try {
      await voice.save(patch);
      await refresh();
      setNotice('Saved');
      void voice.feedback();
      return true;
    } catch (error) {
      setNotice(
        error instanceof Error ? error.message : 'Could not save. Try again.',
      );
      return false;
    } finally {
      setSaving(false);
    }
  };
  const togglePause = async () => {
    const pausing = !settings?.paused;
    if (!(await save({ paused: pausing }))) return;
    if (!pausing && accessibilitySupported && onboarding.supported) {
      try {
        const status = await onboarding.status();
        if (!status.accessibility) await onboarding.openAccessibilitySettings();
      } catch {
        // Pause is saved either way; the Android setup card still shows the accessibility row.
      }
    }
  };
  const toggleOverlay = async () => {
    if (settings?.enabled) {
      await save({ enabled: false });
      return;
    }
    try {
      const status = await onboarding.status();
      if (!status.overlay) {
        await onboarding.openOverlaySettings();
        setNotice(
          'Allow display over other apps, then return and turn on the floating microphone.',
        );
        return;
      }
    } catch {
      setNotice('Could not check overlay permission. Use Android setup below.');
      return;
    }
    await save({ enabled: true, paused: false });
  };
  const navigate = async (next: typeof page) => {
    setPage(next);
    setNotice('');
    void voice.feedback();
    if (next === 'history')
      try {
        setEntries((await voice.history()).entries);
      } catch {
        setNotice('Could not load local history.');
      }
  };
  return (
    <div className="voice-app">
      <header className="voice-header">
        {page === 'home' ? (
          <span className="voice-brand">
            <span className="voice-brand-mark">
              <AudioLines size={20} />
            </span>{' '}
            odicto<span className="voice-brand-dot">.</span>
          </span>
        ) : (
          <button
            className="voice-icon"
            aria-label="Back to home"
            onClick={() => void navigate('home')}
          >
            <ArrowLeft />
          </button>
        )}
        <button
          className="voice-icon"
          aria-label={page === 'settings' ? 'Open history' : 'Open settings'}
          onClick={() =>
            void navigate(page === 'settings' ? 'history' : 'settings')
          }
        >
          {page === 'settings' ? (
            <History size={21} />
          ) : (
            <Settings2 size={21} />
          )}
        </button>
      </header>
      <main className="voice-main" data-page={page}>
        {page === 'home' && (
          <>
            <p className="voice-eyebrow">A LITTLE LESS TYPING</p>
            <h1>
              Your words.
              <br />
              <span>One touch away.</span>
            </h1>
            <p className="voice-intro">
              Dictate with Raw, ask AI to write, or see words as you speak with
              Live.
            </p>
            <div
              className="voice-concept"
              aria-label="Voice controls: AI, Live, and microphone"
            >
              <span className="voice-orbit orbit-one" />
              <span className="voice-orbit orbit-two" />
              <div className="voice-demo-pill">
                <Sparkles size={21} />
                <span className="voice-demo-divider" />
                <AudioLines size={23} />
                <span className="voice-demo-mic">
                  <Mic size={29} />
                </span>
              </div>
              <span className="voice-concept-caption">
                Small on your screen. Big on possibility.
              </span>
            </div>
            <section className="voice-card">
              {overlaySupported && (
                <>
                  <div className="voice-row">
                    <div>
                      <h2>Floating microphone</h2>
                      <p>
                        {settings?.paused
                          ? 'Paused, so the bubble is hidden'
                          : settings?.enabled
                            ? 'On screen over other apps'
                            : 'Keep your voice controls within reach'}
                      </p>
                    </div>
                    <button
                      className="voice-switch"
                      role="switch"
                      aria-checked={settings?.enabled ?? false}
                      aria-label="Floating microphone"
                      disabled={!settings || saving}
                      onClick={() => void toggleOverlay()}
                    >
                      <span />
                    </button>
                  </div>
                  <div className="voice-separator" />
                  <div className="voice-row">
                    <div>
                      <h2>Pause Odicto</h2>
                      <p>
                        {settings?.paused
                          ? 'Paused · banking and other protected apps see nothing'
                          : 'Stops the floating mic and voice typing in other keyboards'}
                      </p>
                    </div>
                    <button
                      className="voice-switch"
                      role="switch"
                      aria-checked={settings?.paused ?? false}
                      aria-label="Pause Odicto"
                      disabled={!settings || saving}
                      onClick={() => void togglePause()}
                    >
                      <span />
                    </button>
                  </div>
                  <div className="voice-separator" />
                </>
              )}
              <p className="voice-label">START IN</p>
              <div
                className="voice-segment"
                role="group"
                aria-label="Default recording mode"
              >
                <button
                  aria-pressed={settings?.mode === 'raw'}
                  disabled={!settings || saving}
                  onClick={() => void save({ mode: 'raw' })}
                >
                  <Mic size={18} />
                  <span>Raw</span>
                </button>
                <button
                  aria-pressed={settings?.mode === 'ai'}
                  disabled={!settings || saving}
                  onClick={() => void save({ mode: 'ai' })}
                >
                  <Sparkles size={18} />
                  <span>AI</span>
                  {settings?.mode === 'ai' && <Check size={15} />}
                </button>
                <button
                  aria-pressed={settings?.mode === 'live'}
                  disabled={!settings || saving}
                  onClick={() => void save({ mode: 'live' })}
                >
                  <AudioLines size={19} />
                  <span>Live</span>
                  {settings?.mode === 'live' && <Check size={15} />}
                </button>
              </div>
            </section>
            <section className="voice-guide">
              <h2>Made for your thumb</h2>
              <div>
                <Mic />
                <p>
                  <strong>Hold. Speak. Release.</strong>
                  <span>
                    Release to insert. Wait for the listening cue before
                    speaking.
                  </span>
                </p>
              </div>
              <div>
                <MoveUpRight />
                <p>
                  <strong>Slide to the lock.</strong>
                  <span>
                    Go hands-free. Tap the mic to finish, or × to cancel.
                  </span>
                </p>
              </div>
              <div>
                <Settings2 />
                <p>
                  <strong>Double-tap for more.</strong>
                  <span>
                    Choose a mode or open settings. Drag the idle bubble to move
                    it.
                  </span>
                </p>
              </div>
            </section>
            <AndroidOnboarding />
            <button
              className="voice-link"
              onClick={() => void navigate('history')}
            >
              <span>Recent words</span>
              <ArrowUpRight size={20} />
            </button>
            <p className="voice-footnote">
              Audio is used for your request. History stays on this device.
              Voice controls hide in protected fields.
            </p>
          </>
        )}
        {page === 'settings' && (
          <>
            <p className="voice-eyebrow">MAKE IT YOURS</p>
            <h1>
              Voice settings<span>.</span>
            </h1>
            <p className="voice-intro">
              A few preferences. Then back to your flow.
            </p>
            <nav className="voice-settings-nav" aria-label="Settings sections">
              <a href="#voice-controls">Controls</a>
              <a href="#voice-connections">Connections</a>
              <a href="#voice-ai">AI answers</a>
              <a href="#voice-polish">Text polish</a>
              <a href="#voice-live">Live</a>
              <a href="#voice-setup">Android setup</a>
            </nav>
            <section
              className="voice-card"
              id="voice-controls"
              aria-labelledby="voice-controls-title"
            >
              <header className="voice-section-heading">
                <h2 id="voice-controls-title">Controls & appearance</h2>
                <p>
                  Make the keyboard and floating microphone feel right for you.
                </p>
              </header>
              <div className="voice-separator" />
              <div className="voice-row voice-row-stacked">
                <div>
                  <h3>Touch feedback</h3>
                  <p>How strongly each key and control vibrates.</p>
                </div>
                <div
                  className="voice-segmented"
                  role="radiogroup"
                  aria-label="Touch feedback strength"
                >
                  {(
                    [
                      [0, 'Off'],
                      [1, 'Light'],
                      [2, 'Medium'],
                      [3, 'Strong'],
                    ] as const
                  ).map(([level, label]) => (
                    <button
                      key={level}
                      type="button"
                      role="radio"
                      aria-checked={(settings?.hapticLevel ?? 2) === level}
                      disabled={!settings || saving}
                      onClick={() => void save({ hapticLevel: level })}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>
              <div className="voice-separator" />
              <div className="voice-row voice-row-stacked">
                <div>
                  <h3>Key size</h3>
                  <p>Height of the keys and the letters on them.</p>
                </div>
                <div
                  className="voice-segmented"
                  role="radiogroup"
                  aria-label="Key size"
                >
                  {(
                    [
                      ['small', 'S'],
                      ['medium', 'M'],
                      ['large', 'L'],
                    ] as const
                  ).map(([size, label]) => (
                    <button
                      key={size}
                      type="button"
                      role="radio"
                      aria-checked={(settings?.keySize ?? 'medium') === size}
                      disabled={!settings || saving}
                      onClick={() => void save({ keySize: size })}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>
              <div className="voice-separator" />
              <div className="voice-row voice-row-stacked">
                <div>
                  <h3>Key font</h3>
                  <p>
                    System is the Android default. Google Sans Flex is the face
                    used on the keys.
                  </p>
                </div>
                <div
                  className="voice-segmented"
                  role="radiogroup"
                  aria-label="Key font"
                >
                  {(
                    [
                      ['system', 'System'],
                      ['sansflex', 'Sans Flex'],
                    ] as const
                  ).map(([font, label]) => (
                    <button
                      key={font}
                      type="button"
                      role="radio"
                      aria-checked={(settings?.keyFont ?? 'sansflex') === font}
                      disabled={!settings || saving}
                      onClick={() => void save({ keyFont: font })}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>
              <div className="voice-separator" />
              <div className="voice-row">
                <div>
                  <h3>Show text preview</h3>
                  <p>
                    Show processed words below the voice status. Turn off for
                    smaller controls; words still go to your editor and history.
                  </p>
                </div>
                <button
                  className="voice-switch"
                  role="switch"
                  aria-checked={settings?.showTextPreview ?? true}
                  aria-label="Show text preview"
                  disabled={!settings || saving}
                  onClick={() =>
                    void save({
                      showTextPreview: !(settings?.showTextPreview ?? true),
                    })
                  }
                >
                  <span />
                </button>
              </div>
            </section>
            <form
              className="voice-settings-grid"
              onSubmit={(e) => {
                e.preventDefault();
                void save({
                  provider: settings?.provider,
                  model: settings?.model,
                  liveModel: settings?.liveModel,
                  systemPrompt: settings?.systemPrompt,
                  polishModel: settings?.polishModel,
                  polishSystemPrompt: settings?.polishSystemPrompt,
                  ...(groqKey.trim() ? { groqKey: groqKey.trim() } : {}),
                  ...(geminiKey.trim() ? { geminiKey: geminiKey.trim() } : {}),
                  ...(openrouterKey.trim()
                    ? { openrouterKey: openrouterKey.trim() }
                    : {}),
                }).then((saved) => {
                  if (saved) {
                    setGeminiKey('');
                    setGroqKey('');
                    setOpenrouterKey('');
                  }
                });
              }}
            >
              <section
                className="voice-card voice-form voice-connections"
                id="voice-connections"
                aria-labelledby="voice-connections-title"
              >
                <header className="voice-section-heading">
                  <h2 id="voice-connections-title">API connections</h2>
                  <p>
                    Your phone connects directly to Groq, Gemini, and
                    OpenRouter. Add a key once, then leave its field empty to
                    keep it saved. No computer or Odicto server is needed.
                  </p>
                </header>
                <div className="voice-api-key">
                  <label>
                    <span className="voice-field-title">
                      Groq key
                      {settings?.groqKeySet && (
                        <span className="voice-key-status">Saved securely</span>
                      )}
                    </span>
                    <input
                      type="password"
                      value={groqKey}
                      onChange={(e) => setGroqKey(e.target.value)}
                      placeholder={
                        settings?.groqKeySet
                          ? 'Enter a new key to replace saved key'
                          : 'Required for Raw and AI speech'
                      }
                      autoComplete="off"
                      autoCapitalize="none"
                      spellCheck={false}
                    />
                  </label>
                  {settings?.groqKeySet && (
                    <button
                      type="button"
                      className="voice-text-button"
                      disabled={saving}
                      onClick={() => void save({ groqKey: '' })}
                    >
                      Remove saved Groq key
                    </button>
                  )}
                  <p>
                    Raw uses Groq Whisper. AI uses Groq for speech, then your
                    selected answer provider.
                  </p>
                </div>
                <div className="voice-api-key">
                  <label>
                    <span className="voice-field-title">
                      Gemini key
                      {settings?.geminiKeySet && (
                        <span className="voice-key-status">Saved securely</span>
                      )}
                    </span>
                    <input
                      type="password"
                      value={geminiKey}
                      onChange={(e) => setGeminiKey(e.target.value)}
                      placeholder={
                        settings?.geminiKeySet
                          ? 'Enter a new key to replace saved key'
                          : 'Required for Live and Gemini AI'
                      }
                      autoComplete="off"
                      autoCapitalize="none"
                      spellCheck={false}
                    />
                  </label>
                  {settings?.geminiKeySet && (
                    <button
                      type="button"
                      className="voice-text-button"
                      disabled={saving}
                      onClick={() => void save({ geminiKey: '' })}
                    >
                      Remove saved Gemini key
                    </button>
                  )}
                  <p>Gemini powers Live transcription and Gemini AI answers.</p>
                </div>
                <div className="voice-api-key">
                  <label>
                    <span className="voice-field-title">
                      OpenRouter key
                      {settings?.openrouterKeySet && (
                        <span className="voice-key-status">Saved securely</span>
                      )}
                    </span>
                    <input
                      type="password"
                      value={openrouterKey}
                      onChange={(e) => setOpenrouterKey(e.target.value)}
                      placeholder={
                        settings?.openrouterKeySet
                          ? 'Enter a new key to replace saved key'
                          : 'Only needed for OpenRouter AI'
                      }
                      autoComplete="off"
                      autoCapitalize="none"
                      spellCheck={false}
                    />
                  </label>
                  {settings?.openrouterKeySet && (
                    <button
                      type="button"
                      className="voice-text-button"
                      disabled={saving}
                      onClick={() => void save({ openrouterKey: '' })}
                    >
                      Remove saved OpenRouter key
                    </button>
                  )}
                  <p className="voice-footnote">
                    Keys are stored using Android Keystore and sent only for
                    your selected provider over HTTPS. Audio stays only for the
                    current request.
                  </p>
                </div>
              </section>
              <section
                className="voice-card voice-form"
                id="voice-ai"
                aria-labelledby="voice-ai-title"
              >
                <header className="voice-section-heading">
                  <h2 id="voice-ai-title">AI answers</h2>
                  <p>
                    AI selects the whole field unless you have chosen a range.
                    That selected text is sent to your AI provider with your
                    spoken instruction. Keep the selection unchanged until the
                    result replaces it; otherwise the result is saved to copy.
                  </p>
                </header>
                <label>
                  AI system prompt
                  <textarea
                    value={settings?.systemPrompt ?? ''}
                    onChange={(e) =>
                      setSettings((s) =>
                        s ? { ...s, systemPrompt: e.target.value } : s,
                      )
                    }
                    maxLength={8000}
                    rows={5}
                    placeholder="Example: Keep my tone, use British English, and return only the revised text."
                  />
                </label>
                <p>
                  Saved on this device and sent only with AI requests. Leave
                  blank for the default prompt. Maximum 8,000 characters;
                  selected text is limited to 20,000 characters.
                </p>
                <label>
                  Provider
                  <select
                    value={settings?.provider ?? 'gemini'}
                    disabled={!settings}
                    onChange={(e) =>
                      setSettings((s) =>
                        s
                          ? {
                              ...s,
                              provider: e.target
                                .value as VoiceSettings['provider'],
                              model:
                                e.target.value === 'openrouter'
                                  ? 'poolside/laguna-xs-2.1:free'
                                  : '',
                            }
                          : s,
                      )
                    }
                  >
                    <option value="gemini">Gemini</option>
                    <option value="openrouter">OpenRouter</option>
                  </select>
                </label>
                <label>
                  Model
                  {settings?.provider === 'openrouter' ? (
                    <div className="voice-model-picker">
                      <input
                        type="search"
                        value={openRouterModelSearch}
                        disabled={!settings || loadingOpenRouterModels}
                        onChange={(e) =>
                          setOpenRouterModelSearch(e.target.value)
                        }
                        placeholder="Search models"
                        aria-label="Search OpenRouter models"
                        autoCapitalize="none"
                        spellCheck={false}
                      />
                      <select
                        value={settings.model || 'poolside/laguna-xs-2.1:free'}
                        disabled={!settings || loadingOpenRouterModels}
                        onChange={(e) =>
                          setSettings((s) =>
                            s ? { ...s, model: e.target.value } : s,
                          )
                        }
                      >
                        <option value="poolside/laguna-xs-2.1:free">
                          Auto · Laguna XS (free)
                        </option>
                        {openRouterModelGroups.map(([letter, models]) => (
                          <optgroup key={letter} label={letter}>
                            {models.map((model) => (
                              <option key={model.id} value={model.id}>
                                {model.name}
                                {model.free ? ' · free' : ''}
                              </option>
                            ))}
                          </optgroup>
                        ))}
                      </select>
                      {!loadingOpenRouterModels &&
                        openRouterModelSearch.trim() &&
                        openRouterModelGroups.length === 0 && (
                          <span className="voice-model-empty">
                            No matching models
                          </span>
                        )}
                    </div>
                  ) : (
                    <input
                      value={settings?.model ?? ''}
                      onChange={(e) =>
                        setSettings((s) =>
                          s ? { ...s, model: e.target.value } : s,
                        )
                      }
                      placeholder="gemini-3.5-flash-lite"
                      autoCapitalize="none"
                      spellCheck={false}
                    />
                  )}
                </label>
                {settings?.provider === 'openrouter' && (
                  <p>
                    {loadingOpenRouterModels
                      ? 'Loading the current OpenRouter model catalog…'
                      : openRouterModels.length > 0
                        ? 'Models are loaded from OpenRouter. Requests prefer the lowest-latency provider with low thinking enabled.'
                        : settings.openrouterKeySet
                          ? 'Could not load OpenRouter models. Save a valid key and try again.'
                          : 'Save your OpenRouter key to load its live model catalog.'}
                  </p>
                )}
              </section>
              <section
                className="voice-card voice-form"
                id="voice-polish"
                aria-labelledby="voice-polish-title"
              >
                <header className="voice-section-heading">
                  <h2 id="voice-polish-title">Text polish</h2>
                  <p>
                    Tap Polish beside the keyboard mic to correct the whole text
                    field with OpenRouter. Typing alone sends nothing. Password
                    and protected fields are refused.
                  </p>
                </header>
                <p>
                  {settings?.openrouterKeySet
                    ? 'Your OpenRouter key is saved securely above.'
                    : 'Save an OpenRouter key in API connections above to use text polish.'}
                </p>
                <label>
                  OpenRouter model ID
                  <input
                    value={settings?.polishModel ?? 'poolside/laguna-xs-2.1'}
                    onChange={(e) =>
                      setSettings((s) =>
                        s ? { ...s, polishModel: e.target.value } : s,
                      )
                    }
                    maxLength={200}
                    placeholder="poolside/laguna-xs-2.1"
                    autoCapitalize="none"
                    spellCheck={false}
                  />
                </label>
                <label>
                  Choose from OpenRouter catalog
                  <select
                    value={
                      openRouterModels.some(
                        (model) => model.id === settings?.polishModel,
                      )
                        ? settings?.polishModel
                        : ''
                    }
                    disabled={
                      !settings ||
                      loadingOpenRouterModels ||
                      openRouterModels.length === 0
                    }
                    onChange={(e) =>
                      setSettings((s) =>
                        s ? { ...s, polishModel: e.target.value } : s,
                      )
                    }
                  >
                    <option value="" disabled>
                      Enter a model ID above
                    </option>
                    {openRouterModels.map((model) => (
                      <option key={model.id} value={model.id}>
                        {model.name}
                        {model.free ? ' · free' : ''}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Text polish system prompt
                  <textarea
                    value={settings?.polishSystemPrompt ?? ''}
                    onChange={(e) =>
                      setSettings((s) =>
                        s ? { ...s, polishSystemPrompt: e.target.value } : s,
                      )
                    }
                    maxLength={8000}
                    rows={5}
                    placeholder="Blank uses the built-in grammar, spelling, and capitalization instruction."
                  />
                </label>
                <p>
                  Leave the prompt blank to preserve the built-in correction
                  rules, including names as written. A model can still make
                  mistakes; review its result. Fields over 20,000 characters are
                  refused rather than partially rewritten.
                </p>
              </section>
              <section
                className="voice-card voice-form"
                id="voice-live"
                aria-labelledby="voice-live-title"
              >
                <header className="voice-section-heading">
                  <h2 id="voice-live-title">Live transcription</h2>
                  <p>
                    Live streams speech directly to Gemini and updates words as
                    you speak.
                  </p>
                </header>
                <label>
                  Gemini Live model
                  <input
                    value={settings?.liveModel ?? ''}
                    onChange={(e) =>
                      setSettings((s) =>
                        s ? { ...s, liveModel: e.target.value } : s,
                      )
                    }
                    placeholder="gemini-3.5-transcribe-live"
                    autoCapitalize="none"
                    spellCheck={false}
                  />
                </label>
              </section>
              <button
                className="voice-primary voice-save"
                disabled={!settings || saving}
              >
                {saving ? 'Saving…' : 'Save preferences'}
              </button>
            </form>
            <div id="voice-setup">
              <AndroidOnboarding />
            </div>
          </>
        )}
        {page === 'history' && (
          <>
            <p className="voice-eyebrow">ONLY ON THIS DEVICE</p>
            <h1>
              Recent words<span>.</span>
            </h1>
            <p className="voice-intro">
              Your latest 30 recordings. Copy a result whenever you need it.
            </p>
            {entries.length === 0 ? (
              <section className="voice-card voice-empty">
                <AudioLines size={32} />
                <h2>A little quiet here</h2>
                <p>Your first dictation will appear here.</p>
              </section>
            ) : (
              entries.map((item) => (
                <article className="voice-card" key={item.id}>
                  <div className="voice-row">
                    <p>
                      {new Date(item.createdAt).toLocaleString()} ·{' '}
                      {item.outcome === 'inserted'
                        ? 'Inserted'
                        : item.outcome === 'incomplete'
                          ? 'Incomplete'
                          : 'Saved'}
                    </p>
                    <button
                      className="voice-icon"
                      aria-label="Copy transcript"
                      onClick={() =>
                        void copyToClipboard(item.text)
                          .then((copied) =>
                            setNotice(
                              copied ? 'Copied' : 'Could not copy. Try again.',
                            ),
                          )
                          .catch(() => setNotice('Could not copy. Try again.'))
                      }
                    >
                      <Copy size={18} />
                    </button>
                  </div>
                  <p className="voice-history-text">{item.text}</p>
                </article>
              ))
            )}
          </>
        )}
      </main>
      {notice && (
        <div
          className="voice-notice"
          role="status"
          onClick={() => setNotice('')}
        >
          {notice}
        </div>
      )}
    </div>
  );
}
