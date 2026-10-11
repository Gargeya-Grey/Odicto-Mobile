import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { onboarding, type OnboardingStatus } from '../../native/onboarding';
import {
  voice,
  type HistoryItem,
  type VoiceSettings,
} from '../../native/voice';
import {
  describeHomeStatus,
  floatingMicLine,
  MODE_HINT,
  pauseLine,
} from '../../lib/homeStatus';
import { summarizeReadiness } from '../../lib/voiceStatus';
import { AndroidOnboarding } from '../AndroidOnboarding';
import { Group, Row, SectionLabel, Segmented, Switch } from './controls';
import { HistoryEntry, useCopyFeedback, useNow } from './HistoryEntry';
import { ChevronRight, Lock, Mic, Settings2 } from './icons';

const MODES = [
  { value: 'raw', label: 'Raw' },
  { value: 'ai', label: 'AI' },
  { value: 'live', label: 'Live' },
] as const;

const GESTURES: { icon: ReactNode; title: string; line: string }[] = [
  {
    icon: <Mic size={18} />,
    title: 'Hold, speak, release',
    line: 'Wait for the cue, then speak. Release to insert.',
  },
  {
    icon: <Lock size={18} />,
    title: 'Slide up to lock',
    line: 'Hands-free. Tap the mic to finish, × to cancel.',
  },
  {
    icon: <Settings2 size={18} />,
    title: 'Double-tap for options',
    line: 'Modes and settings. Drag the idle bubble to move.',
  },
];

function scrollToSetup() {
  document.getElementById('voice-setup')?.scrollIntoView({
    behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
      ? 'auto'
      : 'smooth',
    block: 'start',
  });
}

export function HomePage({
  settings,
  status,
  onSetMode,
  onToggleOverlay,
  onTogglePause,
  onRefreshStatus,
  onOpenHistory,
  onCopied,
  onCopyFailed,
}: {
  settings: VoiceSettings | null;
  status: OnboardingStatus | null;
  onSetMode: (mode: VoiceSettings['mode']) => void;
  onToggleOverlay: () => void;
  onTogglePause: () => void;
  onRefreshStatus: () => Promise<void> | void;
  onOpenHistory: () => void;
  onCopied: () => void;
  onCopyFailed: () => void;
}) {
  const overlaySupported = status?.overlaySupported ?? false;
  const home = describeHomeStatus(summarizeReadiness(settings, status));
  const [recent, setRecent] = useState<HistoryItem[] | null>(null);
  const [recentFailed, setRecentFailed] = useState(false);
  const [checking, setChecking] = useState(false);
  const loadId = useRef(0);
  const now = useNow();
  const { copiedId, copy } = useCopyFeedback(onCopied, onCopyFailed);

  // History stays on the device. Read it when Home opens (this page mounts each time Home is
  // shown) and again when the app comes back to the front. Only the newest read counts.
  const loadRecent = useCallback(() => {
    loadId.current += 1;
    const mine = loadId.current;
    voice
      .history()
      .then(({ entries }) => {
        if (mine !== loadId.current) return;
        setRecentFailed(false);
        setRecent(
          [...entries].sort((a, b) => b.createdAt - a.createdAt).slice(0, 2),
        );
      })
      .catch(() => {
        if (mine !== loadId.current) return;
        // Keep a list already on screen; otherwise say the read failed, not that it is empty.
        setRecent((current) => (current?.length ? current : []));
        setRecentFailed(true);
      });
  }, []);
  useEffect(() => {
    loadRecent();
    const resume = () => {
      if (document.visibilityState === 'visible') loadRecent();
    };
    document.addEventListener('visibilitychange', resume);
    return () => {
      loadId.current += 1;
      document.removeEventListener('visibilitychange', resume);
    };
  }, [loadRecent]);

  // Settings load together with the setup status, so loaded settings with no status mean the
  // status read failed (Android only).
  const statusFailed =
    onboarding.supported && !!settings && !status && !checking;
  const retryStatus = () => {
    setChecking(true);
    void Promise.resolve(onRefreshStatus()).finally(() => setChecking(false));
  };

  return (
    <>
      <section className="voice-section" aria-label="Status">
        <Group>
          {home ? (
            <li className="voice-row voice-status" data-tone={home.tone}>
              <div className="voice-row-main">
                <span className="voice-dot" aria-hidden="true" />
                <p className="voice-status-line" role="status">
                  {home.line}
                </p>
                {home.action && (
                  <button
                    type="button"
                    className="voice-link"
                    onClick={
                      home.action === 'resume' ? onTogglePause : scrollToSetup
                    }
                  >
                    {home.action === 'resume' ? 'Resume' : 'Fix'}
                  </button>
                )}
              </div>
            </li>
          ) : statusFailed ? (
            <li className="voice-row voice-status" data-tone="setup">
              <div className="voice-row-main">
                <span className="voice-dot" aria-hidden="true" />
                <p className="voice-status-line" role="status">
                  Could not check setup
                </p>
                <button
                  type="button"
                  className="voice-link"
                  onClick={retryStatus}
                >
                  Retry
                </button>
              </div>
            </li>
          ) : (
            <li className="voice-row voice-status" aria-busy="true">
              <span className="voice-skeleton voice-skeleton-line" />
            </li>
          )}
        </Group>
      </section>

      <section className="voice-section" aria-labelledby="home-mode-title">
        <SectionLabel id="home-mode-title" title="Default mode" />
        <Group>
          <li className="voice-row voice-row-block">
            <Segmented
              kind="pressed"
              label="Default recording mode"
              options={MODES}
              value={settings?.mode as VoiceSettings['mode']}
              onChange={onSetMode}
              disabled={!settings}
              describedBy="home-mode-hint"
            />
            <p className="voice-row-hint" id="home-mode-hint">
              {settings ? MODE_HINT[settings.mode] : ' '}
            </p>
          </li>
        </Group>
      </section>

      {overlaySupported && (
        <section className="voice-section" aria-label="Floating microphone">
          <Group>
            <Row
              title="Floating microphone"
              secondary={floatingMicLine(settings, status?.overlay)}
              trailing={({ describedBy }) => (
                <Switch
                  checked={settings?.enabled ?? false}
                  onChange={onToggleOverlay}
                  label="Floating microphone"
                  describedBy={describedBy}
                  disabled={!settings}
                />
              )}
            />
            <Row
              title="Pause Odicto"
              secondary={pauseLine(settings?.paused ?? false)}
              trailing={({ describedBy }) => (
                <Switch
                  checked={settings?.paused ?? false}
                  onChange={onTogglePause}
                  label="Pause Odicto"
                  describedBy={describedBy}
                  disabled={!settings}
                />
              )}
            />
          </Group>
        </section>
      )}

      <section className="voice-section" aria-labelledby="home-recent-title">
        <SectionLabel id="home-recent-title" title="Recent" />
        <Group>
          {recent === null && (
            <li className="voice-row" aria-busy="true">
              <span className="voice-skeleton voice-skeleton-line" />
            </li>
          )}
          {recent?.length === 0 && (
            <li className="voice-row">
              <p
                className="voice-row-note"
                role={recentFailed ? 'alert' : undefined}
              >
                {recentFailed
                  ? 'Could not load recent dictations.'
                  : 'Your dictations appear here.'}
              </p>
            </li>
          )}
          {recent?.map((item) => (
            <HistoryEntry
              key={item.id}
              item={item}
              now={now}
              copied={copiedId === item.id}
              onCopy={copy}
              lines={2}
            />
          ))}
          {recent !== null && recent.length > 0 && (
            <li>
              <button
                type="button"
                className="voice-rowbtn voice-seeall"
                onClick={onOpenHistory}
              >
                <span>See all</span>
                <ChevronRight size={18} />
              </button>
            </li>
          )}
        </Group>
      </section>

      <section className="voice-section" aria-labelledby="home-tips-title">
        <SectionLabel id="home-tips-title" title="Gestures" />
        <Group>
          {GESTURES.map((gesture) => (
            <li key={gesture.title} className="voice-row voice-gesture">
              <span className="voice-gesture-icon">{gesture.icon}</span>
              <div className="voice-row-text">
                <h3>{gesture.title}</h3>
                <p>{gesture.line}</p>
              </div>
            </li>
          ))}
        </Group>
      </section>

      <AndroidOnboarding
        status={status}
        onRefresh={onRefreshStatus}
        note={false}
      />
      <p className="voice-footnote">
        Audio is used only for your request. History stays on this device. Voice
        input is disabled in protected fields.
      </p>
    </>
  );
}
