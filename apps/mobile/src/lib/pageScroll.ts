export type ScrollTarget = {
  readonly scrollY: number;
  addEventListener(
    type: 'scroll',
    listener: () => void,
    options: AddEventListenerOptions,
  ): void;
  removeEventListener(type: 'scroll', listener: () => void): void;
};

/**
 * Calls back whenever the page moves away from the top or back to it, and once at the start.
 * The light theme shows its status-bar scrim only while scrolled (voice.css). This replaces a CSS
 * scroll-driven animation: Android WebView stalls a touch fling while a scroll timeline runs, so
 * the page froze mid-fling and then jumped. Returns the unsubscribe.
 */
export function watchPageScrolled(
  callback: (scrolled: boolean) => void,
  target: ScrollTarget = window,
): () => void {
  let scrolled: boolean | undefined;
  const check = () => {
    const next = target.scrollY > 0;
    if (next === scrolled) return;
    scrolled = next;
    callback(next);
  };
  check();
  target.addEventListener('scroll', check, { passive: true });
  return () => target.removeEventListener('scroll', check);
}
