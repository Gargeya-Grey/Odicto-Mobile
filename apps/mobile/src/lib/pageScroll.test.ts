import { describe, expect, it } from 'vitest';
import { watchPageScrolled, type ScrollTarget } from './pageScroll';

const page = (start = 0) => {
  const listeners = new Set<() => void>();
  let y = start;
  const target: ScrollTarget = {
    get scrollY() {
      return y;
    },
    addEventListener: (_type, listener) => void listeners.add(listener),
    removeEventListener: (_type, listener) => void listeners.delete(listener),
  };
  const scrollTo = (next: number) => {
    y = next;
    for (const listener of listeners) listener();
  };
  return { target, scrollTo, listeners };
};

describe('watchPageScrolled', () => {
  it('reports the starting position once', () => {
    const calls: boolean[] = [];
    watchPageScrolled((scrolled) => calls.push(scrolled), page(0).target);
    watchPageScrolled((scrolled) => calls.push(scrolled), page(80).target);
    expect(calls).toEqual([false, true]);
  });

  it('calls back only when the page leaves or returns to the top', () => {
    const { target, scrollTo } = page();
    const calls: boolean[] = [];
    watchPageScrolled((scrolled) => calls.push(scrolled), target);
    for (const y of [1, 40, 900, 12, 0, 0, 3]) scrollTo(y);
    expect(calls).toEqual([false, true, false, true]);
  });

  it('stops listening after unsubscribe', () => {
    const { target, scrollTo, listeners } = page();
    const calls: boolean[] = [];
    const stop = watchPageScrolled((scrolled) => calls.push(scrolled), target);
    stop();
    scrollTo(50);
    expect(calls).toEqual([false]);
    expect(listeners.size).toBe(0);
  });
});
