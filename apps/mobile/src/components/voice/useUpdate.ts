import { useCallback, useEffect, useRef, useState } from 'react';
import { updates, type UpdateStatus } from '../../native/update';

/**
 * The "new version" card and the "Check for updates" setting.
 *
 * What the phone already knows shows at once; the check itself runs when the app opens or comes back to the
 * front, and the phone makes the actual request at most once a day. A failed check changes nothing on screen.
 * A tap always wins over a check that began before it.
 */
export function useUpdate(
  notify: (text: string, tone?: 'info' | 'error') => void,
) {
  const [status, setStatus] = useState<UpdateStatus | null>(null);
  const epoch = useRef(0);

  useEffect(() => {
    if (!updates.supported) return;
    let active = true;
    const load = async () => {
      const started = epoch.current;
      const apply = (next: UpdateStatus) => {
        if (active && epoch.current === started) setStatus(next);
      };
      try {
        apply(await updates.status());
        apply(await updates.check());
      } catch {
        // Nothing to say: no card is the right answer when the check cannot run.
      }
    };
    void load();
    const resume = () => {
      if (!document.hidden) void load();
    };
    document.addEventListener('visibilitychange', resume);
    return () => {
      active = false;
      document.removeEventListener('visibilitychange', resume);
    };
  }, []);

  const dismiss = useCallback(async () => {
    epoch.current += 1;
    setStatus((current) =>
      current ? { ...current, available: false } : current,
    );
    try {
      setStatus(await updates.dismiss());
    } catch {
      notify('Could not hide the update.', 'error');
      try {
        setStatus(await updates.status());
      } catch {
        // The card stays hidden until the next read.
      }
    }
  }, [notify]);

  const download = useCallback(async () => {
    try {
      await updates.openDownload();
    } catch (error) {
      notify(
        error instanceof Error && error.message
          ? error.message
          : 'Could not open the download page.',
        'error',
      );
    }
  }, [notify]);

  const setEnabled = useCallback(
    async (enabled: boolean) => {
      epoch.current += 1;
      setStatus((current) =>
        current
          ? { ...current, enabled, available: enabled && current.available }
          : current,
      );
      try {
        setStatus(await updates.setEnabled(enabled));
        // Turning it back on looks right away, so the answer is there when the user returns to Home.
        if (enabled) setStatus(await updates.check());
      } catch {
        notify('Could not save the update setting.', 'error');
        try {
          setStatus(await updates.status());
        } catch {
          // Keep what is on screen.
        }
      }
    },
    [notify],
  );

  /** "Check now": ignores the hourly pause. Says what it found, so the user is never left guessing. */
  const [checking, setChecking] = useState(false);
  const checkNow = useCallback(async () => {
    epoch.current += 1;
    setChecking(true);
    try {
      const next = await updates.check(true);
      setStatus(next);
      if (next.failed) notify('Could not reach GitHub. Try again.', 'error');
      else if (next.available) notify(`Odicto ${next.version} is available.`);
      else notify('You have the latest version.');
    } catch {
      notify('Could not check for updates.', 'error');
    } finally {
      setChecking(false);
    }
  }, [notify]);

  return { status, checking, checkNow, dismiss, download, setEnabled };
}
