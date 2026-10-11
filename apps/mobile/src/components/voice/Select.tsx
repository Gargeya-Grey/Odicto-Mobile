import {
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
} from 'react';
import { createPortal } from 'react-dom';
import {
  clampIndex,
  edgeIndex,
  nextTypeaheadBuffer,
  stepIndex,
  typeaheadMatch,
} from '../../lib/listbox';
import { menuHistory, type MenuEntry } from '../../lib/menuHistory';
import { Check, ChevronDown } from './icons';

export type SelectOption = { value: string; label: string; disabled?: boolean };
export type SelectGroup = { label?: string; options: readonly SelectOption[] };

type Place = {
  left: number;
  width: number;
  top?: number;
  bottom?: number;
  maxHeight: number;
  above: boolean;
  /** False until the menu has been measured; it stays hidden until then. */
  ready: boolean;
};

const GAP = 6;
const EDGE = 12;
const MAX_MENU = 288; // 18rem
const MIN_MENU = 96;

const printable = (event: KeyboardEvent) =>
  event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey;

/**
 * An accessible listbox in place of the native select popup. The menu is drawn by
 * the app, so it matches the screen; it flips above the trigger near the
 * bottom edge and stays clear of the status and navigation bars.
 *
 * - With `search`, the menu starts with a search field and the parent filters
 *   `groups` (a combobox). Focus stays in the field; the options can still be
 *   tapped, and a polite live region says how many match. Closing the menu
 *   clears the search, so the next open shows every option again.
 * - Without `search`, focus moves to the selected option (roving tabindex), so
 *   TalkBack reads and reaches the options. Focus returns to the trigger on close.
 * - An open menu owns one history entry, so Android back closes the menu and
 *   leaves the page where it is.
 */
export function Select({
  id,
  groups,
  value,
  valueLabel,
  onChange,
  labelledBy,
  ariaLabel,
  describedBy,
  placeholder,
  disabled,
  search,
  emptyText = 'No matches',
}: {
  id?: string;
  groups: readonly SelectGroup[];
  value: string;
  /** Text for the closed trigger when `value` is not in `groups` (for example, filtered out). */
  valueLabel?: string;
  onChange: (next: string) => void;
  labelledBy?: string;
  ariaLabel?: string;
  describedBy?: string;
  placeholder?: string;
  disabled?: boolean;
  search?: {
    value: string;
    onChange: (next: string) => void;
    label: string;
    placeholder: string;
  };
  emptyText?: string;
}) {
  const uid = useId();
  const listId = `${uid}-list`;
  const optionId = (index: number) => `${uid}-o${index}`;
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const searchField = useRef<HTMLInputElement>(null);
  const typed = useRef({ buffer: '', at: 0 });
  const historyEntry = useRef<MenuEntry | null>(null);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [place, setPlace] = useState<Place | null>(null);
  const [host, setHost] = useState<Element | null>(null);
  const ready = place?.ready ?? false;
  const hasSearch = !!search;

  const flat = useMemo(
    () => groups.flatMap((group) => group.options),
    [groups],
  );
  const labels = useMemo(() => flat.map((option) => option.label), [flat]);
  const enabled = (index: number) => !flat[index]?.disabled;
  const selected = flat.findIndex((option) => option.value === value);
  const current =
    open && flat.length > 0 ? clampIndex(active, flat.length) : -1;
  const searchValue = search?.value;

  const shown =
    selected >= 0
      ? flat[selected].label
      : value === '' && placeholder
        ? placeholder
        : (valueLabel ?? (value || placeholder || ''));
  const showingPlaceholder = selected < 0 && value === '' && !!placeholder;

  const openMenu = (edge?: 'first' | 'last', start?: number) => {
    if (disabled || historyEntry.current) return;
    setActive(
      start !== undefined && start >= 0
        ? start
        : edge
          ? edgeIndex(edge, flat.length, enabled)
          : selected >= 0
            ? selected
            : edgeIndex('first', flat.length, enabled),
    );
    // Hidden at first, at the trigger's width, so the first measure sees the real text wrap.
    setPlace({
      left: 0,
      top: 0,
      width: trigger.current?.getBoundingClientRect().width ?? 0,
      maxHeight: MAX_MENU,
      above: false,
      ready: false,
    });
    setHost(trigger.current?.closest('.voice-app') ?? document.body);
    historyEntry.current = menuHistory().open(() => {
      // The system back gesture popped the menu's entry: close, and stay on the page.
      historyEntry.current = null;
      typed.current = { buffer: '', at: 0 };
      search?.onChange('');
      setOpen(false);
      trigger.current?.focus({ preventScroll: true });
    });
    setOpen(true);
  };
  const closeMenu = (returnFocus = true) => {
    historyEntry.current?.release();
    historyEntry.current = null;
    typed.current = { buffer: '', at: 0 };
    search?.onChange('');
    setOpen(false);
    if (returnFocus) trigger.current?.focus({ preventScroll: true });
  };
  // A menu that unmounts while open gives its history entry back.
  useEffect(
    () => () => {
      historyEntry.current?.release();
      historyEntry.current = null;
    },
    [],
  );
  const commit = (index: number) => {
    const option = flat[index];
    if (!option || option.disabled) return;
    closeMenu();
    if (option.value !== value) onChange(option.value);
  };
  const findByTyping = (key: string, from: number) => {
    const now = Date.now();
    typed.current = {
      buffer: nextTypeaheadBuffer(
        typed.current.buffer,
        key,
        now,
        typed.current.at,
      ),
      at: now,
    };
    return typeaheadMatch(labels, typed.current.buffer, from, enabled);
  };

  const onKeyDown = (event: KeyboardEvent) => {
    if (disabled) return;
    const inSearch = event.target === searchField.current;
    const key = event.key;
    if (!open) {
      if (key === 'ArrowDown' || key === 'Enter' || key === ' ') {
        event.preventDefault();
        openMenu();
      } else if (key === 'ArrowUp') {
        event.preventDefault();
        openMenu('last');
      } else if (printable(event)) {
        // The typed text stays in the buffer, so the next keys continue it in the open menu.
        event.preventDefault();
        openMenu(undefined, findByTyping(key, selected));
      }
      return;
    }
    if (key === 'ArrowDown' || key === 'ArrowUp') {
      event.preventDefault();
      setActive(
        stepIndex(current, key === 'ArrowDown' ? 1 : -1, flat.length, enabled),
      );
    } else if ((key === 'Home' || key === 'End') && !inSearch) {
      event.preventDefault();
      setActive(
        edgeIndex(key === 'Home' ? 'first' : 'last', flat.length, enabled),
      );
    } else if (key === 'Enter' || (key === ' ' && !inSearch)) {
      event.preventDefault();
      commit(current);
    } else if (key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      closeMenu();
    } else if (key === 'Tab') {
      closeMenu();
    } else if (!inSearch && printable(event)) {
      event.preventDefault();
      const index = findByTyping(key, current);
      if (index >= 0) setActive(index);
    }
  };

  // Put the menu under the trigger, or above it when there is no room below. It reads
  // sizes and never writes the menu's styles, so a placement that did not change
  // cannot leave the height limit off. Page scrolls and resizes are throttled to one
  // measure per frame; scrolling the option list itself never measures.
  useLayoutEffect(() => {
    if (!open || !host) return;
    // The safe-area insets arrive as CSS values, so a probe turns them into pixels.
    const probe = document.createElement('div');
    probe.setAttribute('aria-hidden', 'true');
    probe.style.cssText =
      'position:absolute;visibility:hidden;pointer-events:none;height:0;overflow:hidden;' +
      'padding-top:var(--v-safe-top,0px);padding-bottom:var(--v-safe-bottom,0px)';
    host.appendChild(probe);
    const measure = () => {
      const button = trigger.current;
      const popup = menu.current;
      const box = list.current;
      if (!button || !popup || !box) return;
      const rect = button.getBoundingClientRect();
      const viewport = window.innerHeight;
      const insets = getComputedStyle(probe);
      const minY = (parseFloat(insets.paddingTop) || 0) + EDGE;
      const maxY = viewport - (parseFloat(insets.paddingBottom) || 0) - EDGE;
      const cap = Math.min(MAX_MENU, Math.max(0, maxY - minY));
      // The height the menu would have with every option showing.
      const natural = popup.offsetHeight - box.clientHeight + box.scrollHeight;
      const below = maxY - (rect.bottom + GAP);
      const above = rect.top - GAP - minY;
      const flip = below < Math.min(natural, cap) && above > below;
      const next: Place = {
        left: rect.left,
        width: rect.width,
        maxHeight: Math.min(cap, Math.max(MIN_MENU, flip ? above : below)),
        above: flip,
        ready: true,
        ...(flip
          ? { bottom: viewport - rect.top + GAP }
          : { top: rect.bottom + GAP }),
      };
      setPlace((prev) =>
        prev &&
        prev.ready &&
        prev.left === next.left &&
        prev.width === next.width &&
        prev.top === next.top &&
        prev.bottom === next.bottom &&
        prev.maxHeight === next.maxHeight &&
        prev.above === next.above
          ? prev
          : next,
      );
    };
    let frame = 0;
    const schedule = () => {
      if (frame) return;
      frame = window.requestAnimationFrame(() => {
        frame = 0;
        measure();
      });
    };
    const onScroll = (event: Event) => {
      if (event.target instanceof Node && menu.current?.contains(event.target))
        return;
      schedule();
    };
    measure();
    window.addEventListener('resize', schedule);
    window.visualViewport?.addEventListener('resize', schedule);
    window.addEventListener('scroll', onScroll, true);
    return () => {
      window.cancelAnimationFrame(frame);
      window.removeEventListener('resize', schedule);
      window.visualViewport?.removeEventListener('resize', schedule);
      window.removeEventListener('scroll', onScroll, true);
      probe.remove();
    };
  }, [open, host, flat.length, hasSearch]);

  // The menu is hidden until measured, and hidden elements cannot take focus.
  // With search the keys go to the field; without it focus follows the active option.
  useLayoutEffect(() => {
    if (open && ready && hasSearch)
      searchField.current?.focus({ preventScroll: true });
  }, [open, ready, hasSearch]);
  useLayoutEffect(() => {
    const box = list.current;
    if (!open || !ready || !box || current < 0) return;
    const element = box.querySelector<HTMLElement>(`[data-index="${current}"]`);
    if (!element) return;
    const top = element.offsetTop;
    const bottom = top + element.offsetHeight;
    if (top < box.scrollTop + 6) box.scrollTop = Math.max(0, top - 6);
    else if (bottom > box.scrollTop + box.clientHeight - 6)
      box.scrollTop = bottom - box.clientHeight + 6;
    if (!hasSearch) element.focus({ preventScroll: true });
  }, [open, ready, current, hasSearch]);

  // A new search starts at the top of the matches.
  useEffect(() => {
    if (open) setActive(edgeIndex('first', flat.length, enabled));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchValue]);

  // A tap outside closes the menu.
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      const target = event.target as Node;
      if (menu.current?.contains(target) || trigger.current?.contains(target))
        return;
      closeMenu(false);
    };
    document.addEventListener('pointerdown', outside, true);
    return () => document.removeEventListener('pointerdown', outside, true);
  }, [open]);

  const style: CSSProperties =
    place?.ready === true
      ? {
          left: place.left,
          width: place.width,
          top: place.top,
          bottom: place.bottom,
          maxHeight: place.maxHeight,
        }
      : { left: 0, top: 0, width: place?.width, visibility: 'hidden' };

  const starts = groups.map((_, i) =>
    groups.slice(0, i).reduce((sum, g) => sum + g.options.length, 0),
  );
  // Said aloud for the search menu: how many options match, or that none do.
  const spoken = !hasSearch
    ? ''
    : flat.length === 0
      ? emptyText
      : `${flat.length} ${flat.length === 1 ? 'result' : 'results'}`;
  return (
    <>
      <button
        ref={trigger}
        id={id}
        type="button"
        className="voice-select"
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-labelledby={labelledBy}
        aria-label={labelledBy ? undefined : ariaLabel}
        aria-describedby={describedBy}
        disabled={disabled}
        onClick={() => (open ? closeMenu(false) : openMenu())}
        onKeyDown={onKeyDown}
      >
        <span
          className="voice-select-label"
          data-placeholder={showingPlaceholder ? 'true' : undefined}
        >
          {shown}
        </span>
        <ChevronDown size={14} />
      </button>
      {open &&
        host &&
        createPortal(
          <div
            ref={menu}
            className="voice-menu"
            data-above={place?.above ? 'true' : undefined}
            style={style}
            onKeyDown={hasSearch ? undefined : onKeyDown}
            onMouseDown={(event) => {
              if (event.target !== searchField.current) event.preventDefault();
            }}
          >
            {search && (
              <div
                className="voice-menu-search"
                onClick={() => searchField.current?.focus()}
              >
                <input
                  ref={searchField}
                  type="search"
                  role="combobox"
                  className="voice-menu-input"
                  aria-label={search.label}
                  aria-expanded="true"
                  aria-controls={listId}
                  aria-autocomplete="list"
                  aria-activedescendant={
                    current >= 0 ? optionId(current) : undefined
                  }
                  placeholder={search.placeholder}
                  value={search.value}
                  onChange={(event) => search.onChange(event.target.value)}
                  onKeyDown={onKeyDown}
                  autoCapitalize="none"
                  autoComplete="off"
                  spellCheck={false}
                />
              </div>
            )}
            <div
              ref={list}
              id={listId}
              role="listbox"
              className="voice-menu-list"
              aria-labelledby={labelledBy}
              aria-label={labelledBy ? undefined : ariaLabel}
            >
              {groups.map((group, groupIndex) => (
                <div
                  key={group.label ?? groupIndex}
                  role="group"
                  aria-label={group.label}
                >
                  {group.label && (
                    <div className="voice-menu-group" aria-hidden="true">
                      {group.label}
                    </div>
                  )}
                  {group.options.map((option, optionIndex) => {
                    const mine = starts[groupIndex] + optionIndex;
                    return (
                      <div
                        key={option.value}
                        id={optionId(mine)}
                        role="option"
                        className="voice-option"
                        tabIndex={mine === current && !hasSearch ? 0 : -1}
                        data-index={mine}
                        data-active={mine === current ? 'true' : undefined}
                        data-selected={mine === selected ? 'true' : undefined}
                        aria-selected={mine === selected}
                        aria-disabled={option.disabled || undefined}
                        onMouseMove={() => {
                          if (mine !== current && !option.disabled)
                            setActive(mine);
                        }}
                        onClick={() => commit(mine)}
                      >
                        <span>{option.label}</span>
                        {mine === selected && <Check size={14} />}
                      </div>
                    );
                  })}
                </div>
              ))}
            </div>
            {flat.length === 0 && (
              <p
                className="voice-menu-empty"
                aria-hidden={hasSearch ? true : undefined}
              >
                {emptyText}
              </p>
            )}
            <div className="voice-sr" role="status" aria-live="polite">
              {spoken}
            </div>
          </div>,
          host,
        )}
    </>
  );
}
