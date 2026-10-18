import { useCallback, useEffect, useRef, useState } from 'react';
import { createSerialQueue } from '../../lib/serialQueue';
import {
  applyTheme,
  nextAppearanceFromTheme,
  parseAppearance,
  readStoredAppearance,
  requestedTheme,
  resolveTheme,
  storeAppearance,
  systemPrefersDark,
  watchSystemTheme,
  type Appearance,
  type Point,
  type Theme,
} from '../../lib/theme';
import { appearanceStore } from '../../native/appearance';

/**
 * Light or dark, shown now and saved by the phone.
 *
 * - The first paint uses the last choice kept in localStorage (index.html reads it before React);
 *   the phone's saved choice is read when the app opens and wins over it.
 * - A change shows at once and is saved in the order it was made. If saving fails, the screen goes back
 *   to the last choice the phone confirmed, and one error appears. A newer tap always wins over an
 *   older read or save.
 * - `system` follows the phone's own light/dark setting live.
 */
export function useAppearance(
  notify: (text: string, tone?: 'info' | 'error') => void,
) {
  const [appearance, setAppearanceState] = useState<Appearance>(() =>
    readStoredAppearance(),
  );
  const [systemDark, setSystemDark] = useState(systemPrefersDark);
  const theme: Theme = resolveTheme(appearance, systemDark);

  const [enqueue] = useState(createSerialQueue);
  const epoch = useRef(0);
  const pending = useRef(0);
  const confirmed = useRef<Appearance>(appearance);

  // Covers a changed phone setting and a native value that arrives after first paint.
  // A reveal in progress already asked for this theme, so it is left alone.
  useEffect(() => {
    if (requestedTheme() !== theme) applyTheme(theme);
  }, [theme]);

  useEffect(() => watchSystemTheme(setSystemDark), []);

  const refresh = useCallback(async () => {
    setSystemDark(systemPrefersDark());
    if (!appearanceStore.supported) return;
    const started = epoch.current;
    try {
      // The read waits behind any save already sent, so it never sees older state.
      const result = await enqueue(() => appearanceStore.get());
      if (epoch.current !== started || pending.current > 0) return;
      const native = parseAppearance(result?.appearance);
      confirmed.current = native;
      storeAppearance(native);
      setAppearanceState(native);
    } catch {
      // Keep what is on screen; the next resume reads again.
    }
  }, [enqueue]);

  useEffect(() => {
    void refresh();
    const resume = () => {
      if (!document.hidden) void refresh();
    };
    document.addEventListener('visibilitychange', resume);
    return () => document.removeEventListener('visibilitychange', resume);
  }, [refresh]);

  const setAppearance = useCallback(
    (value: Appearance, origin?: Point): Promise<boolean> => {
      epoch.current += 1;
      const mine = epoch.current;
      setAppearanceState(value);
      applyTheme(resolveTheme(value, systemPrefersDark()), {
        origin,
        animate: true,
      });
      if (!appearanceStore.supported) {
        confirmed.current = value;
        storeAppearance(value);
        return Promise.resolve(true);
      }
      pending.current += 1;
      return enqueue(() => appearanceStore.set(value)).then(
        () => {
          pending.current -= 1;
          confirmed.current = value;
          storeAppearance(value);
          return true;
        },
        () => {
          pending.current -= 1;
          if (epoch.current === mine) {
            const back = confirmed.current;
            setAppearanceState(back);
            applyTheme(resolveTheme(back, systemPrefersDark()), {
              origin,
              animate: true,
            });
          }
          notify('Could not change the theme. Try again.', 'error');
          return false;
        },
      );
    },
    [enqueue, notify],
  );

  /** The header button: switch to the mode that is not showing. */
  const toggle = useCallback(
    (origin?: Point) =>
      setAppearance(
        nextAppearanceFromTheme(requestedTheme() ?? 'dark'),
        origin,
      ),
    [setAppearance],
  );

  return { appearance, theme, toggle, setAppearance };
}
