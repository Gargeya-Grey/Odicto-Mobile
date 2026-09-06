import { useCallback, useEffect, useState } from 'react';
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
import { voice, type VoiceSettings, type HistoryItem } from '../native/voice';
import { AndroidOnboarding } from './AndroidOnboarding';
import { copyToClipboard } from '../lib/clipboard';
import '../voice.css';

export function VoiceHome() {
  const [settings, setSettings] = useState<VoiceSettings | null>(null);
  const [page, setPage] = useState<'home' | 'settings' | 'history'>('home');
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
  const refresh = useCallback(async () => {
    try {
      const value = await voice.status();
      setSettings(value);
      if (value.openSettings) setPage('settings');
    } catch {
      setNotice('Could not load settings. Reopen Odicto to try again.');
    }
  }, []);
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
              <div className="voice-row">
                <div>
                  <h2>Floating microphone</h2>
                  <p>
                    {settings?.enabled
                      ? 'Enabled · ready in supported text fields'
                      : 'Keep your voice controls within reach'}
                  </p>
                </div>
                <button
                  className="voice-switch"
                  role="switch"
                  aria-checked={settings?.enabled ?? false}
                  aria-label="Floating microphone"
                  disabled={!settings || saving}
                  onClick={() => void save({ enabled: !settings?.enabled })}
                >
                  <span />
                </button>
              </div>
              <div className="voice-separator" />
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
              <div className="voice-row">
                <div>
                  <h3>Touch feedback</h3>
                  <p>Haptics for keyboard buttons and voice controls.</p>
                </div>
                <button
                  className="voice-switch"
                  role="switch"
                  aria-checked={settings?.haptics ?? true}
                  aria-label="Touch feedback"
                  disabled={!settings || saving}
                  onClick={() => void save({ haptics: !settings?.haptics })}
                >
                  <span />
                </button>
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
                    OpenRouter. No computer or Odicto server is needed.
                  </p>
                </header>
                <label>
                  Groq key{' '}
                  {settings?.groqKeySet && (
                    <span className="voice-key-status">· Saved securely</span>
                  )}
                  <input
                    type="password"
                    value={groqKey}
                    onChange={(e) => setGroqKey(e.target.value)}
                    placeholder="Required for Raw and AI speech"
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
                <label>
                  Gemini key
                  {settings?.geminiKeySet && (
                    <span className="voice-key-status">Saved securely</span>
                  )}
                  <input
                    type="password"
                    value={geminiKey}
                    onChange={(e) => setGeminiKey(e.target.value)}
                    placeholder={
                      settings?.geminiKeySet
                        ? 'Leave empty to keep saved key'
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
                <label>
                  OpenRouter key
                  {settings?.openrouterKeySet && (
                    <span className="voice-key-status">Saved securely</span>
                  )}
                  <input
                    type="password"
                    value={openrouterKey}
                    onChange={(e) => setOpenrouterKey(e.target.value)}
                    placeholder={
                      settings?.openrouterKeySet
                        ? 'Leave empty to keep saved key'
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
                  Keys are stored using Android Keystore and sent only for your
                  selected provider over HTTPS. Audio stays only for the current
                  request.
                </p>
              </section>
              <section
                className="voice-card voice-form"
                id="voice-ai"
                aria-labelledby="voice-ai-title"
              >
                <header className="voice-section-heading">
                  <h2 id="voice-ai-title">AI answers</h2>
                  <p>
                    Select text before holding the mic to edit it with your
                    spoken instruction. Only that selection is sent to your AI
                    provider. Keep the selection unchanged until the result
                    replaces it; otherwise the result is saved for you to copy.
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
                              model: '',
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
                  <input
                    value={settings?.model ?? ''}
                    onChange={(e) =>
                      setSettings((s) =>
                        s ? { ...s, model: e.target.value } : s,
                      )
                    }
                    placeholder={
                      settings?.provider === 'openrouter'
                        ? 'Enter an OpenRouter model ID'
                        : 'gemini-3.5-flash-lite'
                    }
                    autoCapitalize="none"
                    spellCheck={false}
                  />
                </label>
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
