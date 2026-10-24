/**
 * Android back and an open Select menu.
 *
 * An open menu owns one history entry on top of its page entry:
 *
 *   [Home] [Settings] [Settings + menu]
 *
 * - The system back gesture pops the menu entry. The menu closes and the page
 *   stays; the page never sees that pop.
 * - Opening another menu transfers this entry and closes the previous menu.
 * - When the menu closes itself (choice, toggle, Escape, or an outside tap for
 *   a non-searchable picker), one `history.back()` pops its entry. That pop is
 *   counted and swallowed, so the page does not navigate.
 */

export type HistoryLike = {
  readonly state: unknown;
  pushState(state: unknown, unused: string): void;
  back(): void;
};

export type PopLike = { readonly state: unknown };

/** True for an entry that an open menu pushed. */
export function isMenuState(state: unknown): boolean {
  return (
    typeof state === 'object' &&
    state !== null &&
    (state as { menu?: unknown }).menu === true
  );
}

/** The state for a menu entry: the page entry's state plus `menu: true`. */
export function menuEntryState(state: unknown): Record<string, unknown> {
  const base =
    typeof state === 'object' && state !== null
      ? { ...(state as Record<string, unknown>) }
      : {};
  return { ...base, menu: true };
}

export type MenuEntry = {
  /** Pop the entry when the menu closed by itself. No effect after a back press. */
  release(): void;
};

export function createMenuHistory(history: HistoryLike) {
  /** `history.back()` calls of ours whose pop has not arrived yet. */
  let ownPops = 0;
  let active: { onBack: () => void; done: boolean } | null = null;

  return {
    /** Own the menu entry. `onBack` closes it on Back or another menu opening. */
    open(onBack: () => void): MenuEntry {
      const entry = { onBack, done: false };
      const previous = active;
      active = entry;
      if (previous) {
        previous.done = true;
        previous.onBack();
      } else {
        history.pushState(menuEntryState(history.state), '');
      }
      return {
        release() {
          if (entry.done) return;
          entry.done = true;
          if (active === entry) active = null;
          ownPops += 1;
          history.back();
        },
      };
    },
    /**
     * Call for every popstate. Returns true when the pop belonged to a menu
     * (ours, or the user's back press on an open menu); the page must ignore it.
     */
    handlePop(_event: PopLike): boolean {
      if (ownPops > 0) {
        ownPops -= 1;
        return true;
      }
      if (active) {
        const entry = active;
        active = null;
        entry.done = true;
        entry.onBack();
        return true;
      }
      return false;
    },
  };
}

const events = new WeakSet<object>();

/** True when the menu layer already took this popstate event. */
export function menuHandled(event: object): boolean {
  return events.has(event);
}

let shared: ReturnType<typeof createMenuHistory> | null = null;

/**
 * The window-wide instance. It listens in the capture phase and registers when
 * this module loads, ahead of the page's own popstate listener.
 */
export function menuHistory() {
  if (!shared) {
    shared = createMenuHistory(window.history);
    window.addEventListener(
      'popstate',
      (event) => {
        if (shared?.handlePop(event)) {
          events.add(event);
          event.stopImmediatePropagation();
        }
      },
      true,
    );
  }
  return shared;
}

// Register now, before any page listener, so the page cannot see a menu pop first.
if (typeof window !== 'undefined') menuHistory();
