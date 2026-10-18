import { useCallback, useEffect, useState } from 'react';
import { voice, type HistoryItem } from '../native/voice';
import { onboarding } from '../native/onboarding';
import { HomePage } from './voice/HomePage';
import { SettingsPage } from './voice/SettingsPage';
import { HistoryPage } from './voice/HistoryPage';
import { X } from 'lucide-react';
import { menuHandled } from '../lib/menuHistory';
import {
  ArrowLeft,
  CircleAlert,
  History,
  ICON_STROKE,
  Info,
  Settings,
} from './voice/icons';
import { ThemeToggle } from './voice/ThemeToggle';
import { useAppearance } from './voice/useAppearance';
import { useOpenRouterModels } from './voice/useOpenRouterModels';
import { useSettingsDraft } from './voice/useSettingsDraft';
import { useUpdate } from './voice/useUpdate';
import { tap, useVoiceSettings, type Notice } from './voice/useVoiceSettings';
import '../voice.css';

type Page = 'home' | 'settings' | 'history';

/** One history entry for whichever inner page is open; Home has none. */
function enterHistory(next: Page) {
  if ((window.history.state as { odicto?: Page } | null)?.odicto)
    window.history.replaceState({ odicto: next }, '');
  else window.history.pushState({ odicto: next }, '');
}

/** Errors stay until dismissed; other notices go away after 4 s. */
function NoticeToast({
  notice,
  onDismiss,
}: {
  notice: Notice | null;
  onDismiss: () => void;
}) {
  useEffect(() => {
    if (!notice || notice.tone === 'error') return;
    const timer = window.setTimeout(onDismiss, 4000);
    return () => window.clearTimeout(timer);
  }, [notice, onDismiss]);
  if (!notice) return null;
  return (
    <div
      key={notice.id}
      className="voice-notice"
      data-tone={notice.tone}
      role={notice.tone === 'error' ? 'alert' : 'status'}
    >
      {notice.tone === 'error' ? <CircleAlert size={18} /> : <Info size={18} />}
      <span>{notice.text}</span>
      <button
        type="button"
        className="voice-icon-btn voice-notice-close"
        aria-label="Dismiss"
        onClick={onDismiss}
      >
        <X size={16} strokeWidth={ICON_STROKE} aria-hidden="true" />
      </button>
    </div>
  );
}

const PAGE_TEXT = {
  settings: {
    title: 'Settings',
    caption:
      'Switches and choices apply right away. Text and keys save when you tap Save changes.',
  },
  history: {
    title: 'Recent words',
    caption: 'Your latest 30 recordings. Stored only on this device.',
  },
} as const;

export function VoiceHome() {
  const {
    settings,
    onboardingStatus,
    notice,
    notify,
    clearNotice,
    saving,
    refresh,
    refreshOnboarding,
    update,
    removeKey,
    saveForm,
    openSettingsRequested,
  } = useVoiceSettings();
  const appUpdate = useUpdate(notify);
  const appearance = useAppearance(notify);
  const [page, setPage] = useState<Page>('home');
  const [entries, setEntries] = useState<HistoryItem[] | null>(null);
  const draft = useSettingsDraft(settings, saveForm);
  const { models, loading: loadingModels } = useOpenRouterModels(
    page === 'settings',
    settings?.openrouterKeySet ?? false,
  );
  const showSaveBar = page === 'settings' && draft.dirty;

  useEffect(() => {
    if (openSettingsRequested > 0) {
      enterHistory('settings');
      setPage('settings');
    }
  }, [openSettingsRequested]);
  useEffect(() => {
    const open = () => {
      enterHistory('settings');
      setPage('settings');
      void refresh();
    };
    window.addEventListener('odicto-settings', open);
    return () => window.removeEventListener('odicto-settings', open);
  }, [refresh]);
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [page]);

  const navigate = useCallback(
    async (next: Page) => {
      setPage(next);
      clearNotice();
      tap();
      if (next === 'history') {
        try {
          setEntries((await voice.history()).entries);
        } catch {
          setEntries((current) => current ?? []);
          notify('Could not load local history.', 'error');
        }
      }
    },
    [clearNotice, notify],
  );

  // Android back returns to Home: each inner page owns one history entry, and MainActivity
  // sends the system back gesture to the web view's history before it lets the app close.
  const go = useCallback(
    (next: Page) => {
      if (next === 'home') {
        if ((window.history.state as { odicto?: Page } | null)?.odicto)
          window.history.back();
        else void navigate('home');
        return;
      }
      enterHistory(next);
      void navigate(next);
    },
    [navigate],
  );
  // An open Select menu owns a history entry of its own (lib/menuHistory). Its pops, and any
  // entry marked `menu`, only close the menu; they never change the page.
  useEffect(() => {
    const pop = (event: PopStateEvent) => {
      const state = event.state as { odicto?: Page; menu?: boolean } | null;
      if (menuHandled(event) || state?.menu) return;
      void navigate(state?.odicto ?? 'home');
    };
    window.addEventListener('popstate', pop);
    return () => window.removeEventListener('popstate', pop);
  }, [navigate]);

  const togglePause = () => void update({ paused: !settings?.paused });
  const toggleOverlay = async () => {
    if (settings?.enabled) {
      void update({ enabled: false });
      return;
    }
    if (onboardingStatus?.overlay !== true) {
      try {
        const status = await onboarding.status();
        if (!status.overlay) {
          await onboarding.openOverlaySettings();
          notify(
            'Allow display over other apps, then return and turn on the floating microphone.',
          );
          return;
        }
      } catch {
        notify(
          'Could not check overlay permission. Use Android setup below.',
          'error',
        );
        return;
      }
    }
    void update({ enabled: true, paused: false });
  };
  const copyFailed = useCallback(
    () => notify('Could not copy. Try again.', 'error'),
    [notify],
  );
  const saveChanges = async () => {
    if (await draft.save()) void refresh();
  };

  const text = page === 'home' ? null : PAGE_TEXT[page];
  return (
    <div className="voice-app" data-savebar={showSaveBar ? 'true' : undefined}>
      <header className="voice-header">
        {page === 'home' ? (
          <h1 className="voice-brand">
            <span className="voice-brand-mark" aria-hidden="true" />
            odicto
            <span className="voice-brand-dot">.</span>
          </h1>
        ) : (
          <button
            type="button"
            className="voice-icon-btn voice-header-btn"
            data-side="start"
            aria-label="Back to home"
            onClick={() => go('home')}
          >
            <ArrowLeft size={20} />
          </button>
        )}
        <div className="voice-header-actions">
          <ThemeToggle
            theme={appearance.theme}
            onToggle={(origin) => {
              tap();
              void appearance.toggle(origin);
            }}
          />
          <button
            type="button"
            className="voice-icon-btn voice-header-btn"
            data-side="end"
            aria-label={page === 'settings' ? 'Open history' : 'Open settings'}
            onClick={() => go(page === 'settings' ? 'history' : 'settings')}
          >
            {page === 'settings' ? (
              <History size={20} />
            ) : (
              <Settings size={20} />
            )}
          </button>
        </div>
      </header>
      <main className="voice-main" data-page={page}>
        <div className="voice-page" key={page}>
          {text && (
            <div className="voice-page-title">
              <h1>{text.title}</h1>
              <p>{text.caption}</p>
            </div>
          )}
          {page === 'home' && (
            <HomePage
              settings={settings}
              status={onboardingStatus}
              onSetMode={(mode) => void update({ mode })}
              onToggleOverlay={() => void toggleOverlay()}
              onTogglePause={togglePause}
              onRefreshStatus={refreshOnboarding}
              onOpenHistory={() => go('history')}
              onCopied={tap}
              onCopyFailed={copyFailed}
              appUpdate={appUpdate.status}
              onDownloadUpdate={() => void appUpdate.download()}
              onDismissUpdate={() => void appUpdate.dismiss()}
            />
          )}
          {page === 'settings' && (
            <SettingsPage
              settings={settings}
              status={onboardingStatus}
              update={update}
              removeKey={removeKey}
              draft={draft}
              models={models}
              loadingModels={loadingModels}
              onRefreshStatus={refreshOnboarding}
              appearance={appearance.appearance}
              onSetAppearance={(value, origin) => {
                tap();
                void appearance.setAppearance(value, origin);
              }}
              appUpdate={appUpdate.status}
              onSetUpdateEnabled={(enabled) =>
                void appUpdate.setEnabled(enabled)
              }
              onCheckUpdate={() => void appUpdate.checkNow()}
              checkingUpdate={appUpdate.checking}
            />
          )}
          {page === 'history' && (
            <HistoryPage
              entries={entries}
              onCopied={tap}
              onCopyFailed={copyFailed}
              onGoHome={() => go('home')}
            />
          )}
        </div>
      </main>
      {showSaveBar && (
        <div
          className="voice-savebar"
          role="region"
          aria-label="Unsaved changes"
        >
          <div className="voice-savebar-inner">
            <button
              type="button"
              className="voice-btn voice-btn-primary"
              disabled={saving}
              onClick={() => void saveChanges()}
            >
              {saving ? 'Saving…' : 'Save changes'}
            </button>
            <button
              type="button"
              className="voice-btn voice-btn-tertiary"
              disabled={saving}
              onClick={draft.discard}
            >
              Discard
            </button>
          </div>
        </div>
      )}
      <NoticeToast notice={notice} onDismiss={clearNotice} />
    </div>
  );
}
