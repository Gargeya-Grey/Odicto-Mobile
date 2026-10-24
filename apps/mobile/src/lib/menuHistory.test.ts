import { describe, expect, it } from 'vitest';
import { createMenuHistory, isMenuState, menuEntryState } from './menuHistory';

/** A session history stack. `back()` pops later, like a browser, then fires popstate. */
function fakeBrowser(initial: unknown[]) {
  const stack = [...initial];
  let index = stack.length - 1;
  const queue: (() => void)[] = [];
  const pops: unknown[] = [];
  const menus = createMenuHistory({
    get state() {
      return stack[index];
    },
    pushState(state) {
      stack.length = index + 1;
      stack.push(state);
      index += 1;
    },
    back() {
      queue.push(() => {
        index -= 1;
        // The page listener sees the pop only when the menu layer lets it through.
        if (!menus.handlePop({ state: stack[index] })) pops.push(stack[index]);
      });
    },
  });
  return {
    menus,
    /** Run the queued traversals, like the browser between tasks. */
    settle() {
      while (queue.length) queue.shift()?.();
    },
    /** The user's back press. */
    press() {
      queue.push(() => {
        index -= 1;
        if (!menus.handlePop({ state: stack[index] })) pops.push(stack[index]);
      });
      this.settle();
    },
    stack: () => stack.slice(0, index + 1),
    pops,
  };
}

const settings = { odicto: 'settings' };

describe('menu entry state', () => {
  it('keeps the page and marks the menu', () => {
    expect(menuEntryState(settings)).toEqual({
      odicto: 'settings',
      menu: true,
    });
    expect(menuEntryState(null)).toEqual({ menu: true });
    expect(isMenuState(menuEntryState(settings))).toBe(true);
    expect(isMenuState(settings)).toBe(false);
    expect(isMenuState(null)).toBe(false);
  });
});

describe('menu history order', () => {
  it('closes the menu on back and keeps the page', () => {
    const b = fakeBrowser([null, settings]);
    let closed = 0;
    b.menus.open(() => (closed += 1));
    expect(b.stack()).toHaveLength(3);
    b.press();
    expect(closed).toBe(1);
    expect(b.stack()).toEqual([null, settings]);
    expect(b.pops).toEqual([]);
  });

  it('pops the entry once when the menu closes by itself', () => {
    const b = fakeBrowser([null, settings]);
    let closed = 0;
    const entry = b.menus.open(() => (closed += 1));
    entry.release();
    entry.release();
    b.settle();
    expect(closed).toBe(0);
    expect(b.stack()).toEqual([null, settings]);
    expect(b.pops).toEqual([]);
  });

  it('lets the next back press reach the page', () => {
    const b = fakeBrowser([null, settings]);
    const entry = b.menus.open(() => undefined);
    entry.release();
    b.settle();
    b.press();
    expect(b.stack()).toEqual([null]);
    expect(b.pops).toEqual([null]);
  });

  it('does not pop again after a back press already closed the menu', () => {
    const b = fakeBrowser([null, settings]);
    const entry = b.menus.open(() => undefined);
    b.press();
    entry.release();
    b.settle();
    expect(b.stack()).toEqual([null, settings]);
    expect(b.pops).toEqual([]);
  });

  it('keeps a quick close and a page back in order', () => {
    const b = fakeBrowser([null, settings]);
    const entry = b.menus.open(() => undefined);
    entry.release(); // choice closes the menu: back #1 is queued
    b.press(); // the user also goes back before it ran
    expect(b.stack()).toEqual([null]);
    expect(b.pops).toEqual([null]); // only the second pop reaches the page
  });

  it('works again for a second menu', () => {
    const b = fakeBrowser([null, settings]);
    b.menus.open(() => undefined).release();
    b.settle();
    let closed = 0;
    b.menus.open(() => (closed += 1));
    b.press();
    expect(closed).toBe(1);
    expect(b.stack()).toEqual([null, settings]);
    expect(b.pops).toEqual([]);
  });

  it('switches the open menu without adding an extra back step', () => {
    const b = fakeBrowser([null, settings]);
    let firstClosed = 0;
    let secondClosed = 0;
    const first = b.menus.open(() => (firstClosed += 1));
    b.menus.open(() => (secondClosed += 1));

    expect(firstClosed).toBe(1);
    expect(secondClosed).toBe(0);
    expect(b.stack()).toHaveLength(3);
    first.release();
    b.settle();
    expect(b.stack()).toHaveLength(3);

    b.press();
    expect(secondClosed).toBe(1);
    expect(b.stack()).toEqual([null, settings]);
    expect(b.pops).toEqual([]);
    b.press();
    expect(b.pops).toEqual([null]);
  });

  it('releases the shared entry when the replacement menu closes itself', () => {
    const b = fakeBrowser([null, settings]);
    const first = b.menus.open(() => undefined);
    const second = b.menus.open(() => undefined);

    first.release();
    second.release();
    second.release();
    b.settle();

    expect(b.stack()).toEqual([null, settings]);
    expect(b.pops).toEqual([]);
    b.press();
    expect(b.pops).toEqual([null]);
  });

  it('ignores pops when no menu is open', () => {
    const b = fakeBrowser([null, settings]);
    b.press();
    expect(b.pops).toEqual([null]);
  });
});
