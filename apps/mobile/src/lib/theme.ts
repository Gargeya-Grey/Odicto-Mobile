/**
 * Light and dark theme. The phone's setting (`Appearance`) is saved natively;
 * this file turns it into what is on screen (`Theme`) and swaps it with a
 * circular reveal. No React in here: index.html runs the same resolve step
 * before the first paint, from the same localStorage key.
 */

export type Appearance = 'system' | 'light' | 'dark';
export type Theme = 'light' | 'dark';
export type Point = { x: number; y: number };

export const APPEARANCE_STORAGE_KEY = 'odicto-appearance';

/** Browser chrome and canvas colours; they match --v-canvas in voice.css. */
export const THEME_COLORS = { dark: '#17191c', light: '#f3f6f2' } as const;

const DARK_QUERY = '(prefers-color-scheme: dark)';
const MOTION_OK_QUERY = '(prefers-reduced-motion: no-preference)';
/**
 * The circle reveal covers the whole screen, so it takes longer and eases out more gently than the small
 * transitions.dev "smooth-out" curve (0.22, 1, 0.36, 1): that one finishes most of a full-screen sweep in the first
 * ~170 ms, which reads as a flash. This is ease-out-cubic: the wipe starts at once and settles over 600 ms.
 */
const REVEAL_EASING = 'cubic-bezier(0.33, 1, 0.68, 1)';
const REVEAL_MS = 600;
/** The fade itself lasts 250 ms (voice.css); the class stays a little longer. */
const FADE_CLASS_MS = 300;
const FADE_CLASS = 'theme-fading';

type StorageLike = Pick<Storage, 'getItem' | 'setItem'>;

export function parseAppearance(value: unknown): Appearance {
  return value === 'light' || value === 'dark' || value === 'system'
    ? value
    : 'system';
}

export function resolveTheme(
  appearance: Appearance,
  systemDark: boolean,
): Theme {
  if (appearance === 'system') return systemDark ? 'dark' : 'light';
  return appearance;
}

/** The header button always picks an explicit mode: the one not on screen. */
export function nextAppearanceFromTheme(theme: Theme): Appearance {
  return theme === 'dark' ? 'light' : 'dark';
}

function defaultStorage(): StorageLike | undefined {
  try {
    return typeof window === 'undefined' ? undefined : window.localStorage;
  } catch {
    return undefined;
  }
}

export function readStoredAppearance(
  storage: StorageLike | undefined = defaultStorage(),
): Appearance {
  try {
    return parseAppearance(storage?.getItem(APPEARANCE_STORAGE_KEY));
  } catch {
    return 'system';
  }
}

export function storeAppearance(
  appearance: Appearance,
  storage: StorageLike | undefined = defaultStorage(),
): boolean {
  try {
    if (!storage) return false;
    storage.setItem(APPEARANCE_STORAGE_KEY, appearance);
    return true;
  } catch {
    return false;
  }
}

function matches(query: string): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia(query).matches
  );
}

export const systemPrefersDark = () => matches(DARK_QUERY);

/** Calls back with the phone's dark flag whenever it changes. Returns the unsubscribe. */
export function watchSystemTheme(
  callback: (systemDark: boolean) => void,
): () => void {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function')
    return () => undefined;
  const query = window.matchMedia(DARK_QUERY);
  const listener = (event: MediaQueryListEvent) => callback(event.matches);
  if (typeof query.addEventListener === 'function') {
    query.addEventListener('change', listener);
    return () => query.removeEventListener('change', listener);
  }
  query.addListener(listener);
  return () => query.removeListener(listener);
}

type ViewTransitionLike = {
  ready: Promise<unknown>;
  finished: Promise<unknown>;
  skipTransition(): void;
};
type ViewTransitionDocument = {
  startViewTransition?: (update: () => void) => ViewTransitionLike;
};

let requested: Theme | undefined;
let running: ViewTransitionLike | undefined;
let reveal: Animation | undefined;
let fadeTimer: number | undefined;

function writeTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', THEME_COLORS[theme]);
}

/** The theme last asked for, even if a reveal has not written it to the page yet. */
export function requestedTheme(): Theme | undefined {
  if (requested) return requested;
  const current = document.documentElement.dataset.theme;
  return current === 'light' || current === 'dark' ? current : undefined;
}

/** Ends a reveal or fade in progress so the next change starts clean. */
function settle() {
  reveal?.cancel();
  reveal = undefined;
  running?.skipTransition();
  running = undefined;
  window.clearTimeout(fadeTimer);
  document.documentElement.classList.remove(FADE_CLASS);
}

/**
 * Shows `theme`. With `animate`, the new theme grows from `origin` (default
 * top right) as a circle; browsers without view transitions get a short
 * colour fade, and reduced motion changes at once.
 */
export function applyTheme(
  theme: Theme,
  options: { origin?: Point; animate?: boolean } = {},
) {
  const root = document.documentElement;
  const changing = requestedTheme() !== theme;
  requested = theme;
  settle();
  if (!options.animate || !changing || !matches(MOTION_OK_QUERY)) {
    writeTheme(theme);
    return;
  }

  const doc = document as unknown as ViewTransitionDocument;
  if (typeof doc.startViewTransition !== 'function') {
    root.classList.add(FADE_CLASS);
    writeTheme(theme);
    fadeTimer = window.setTimeout(
      () => root.classList.remove(FADE_CLASS),
      FADE_CLASS_MS,
    );
    return;
  }

  const { x, y } = options.origin ?? { x: window.innerWidth, y: 0 };
  const radius = Math.hypot(
    Math.max(x, window.innerWidth - x),
    Math.max(y, window.innerHeight - y),
  );
  // The callback writes the latest request, so a skipped transition cannot leave a stale theme.
  const transition = doc.startViewTransition(() => writeTheme(requested!));
  running = transition;
  transition.ready
    .then(() => {
      if (running !== transition) return;
      reveal = root.animate(
        {
          clipPath: [
            `circle(0px at ${x}px ${y}px)`,
            `circle(${radius}px at ${x}px ${y}px)`,
          ],
        },
        {
          duration: REVEAL_MS,
          easing: REVEAL_EASING,
          pseudoElement: '::view-transition-new(root)',
        },
      );
    })
    .catch(() => undefined);
  transition.finished
    .catch(() => undefined)
    .then(() => {
      if (running !== transition) return;
      running = undefined;
      reveal = undefined;
    });
}
