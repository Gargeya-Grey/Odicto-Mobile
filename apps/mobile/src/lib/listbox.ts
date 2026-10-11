/** Pure helpers for the Odicto listbox: index limits and type-to-find. */

/** Keep an index inside 0..count-1. An empty list has no valid index (-1). */
export function clampIndex(index: number, count: number): number {
  if (count <= 0) return -1;
  if (Number.isNaN(index)) return 0;
  return Math.min(Math.max(Math.trunc(index), 0), count - 1);
}

/**
 * Move from `current` by `delta` and stop at the ends (no wrap). Disabled
 * entries are skipped. Returns the new index, or `current` when nothing
 * can take focus in that direction.
 */
export function stepIndex(
  current: number,
  delta: 1 | -1,
  count: number,
  isEnabled: (index: number) => boolean = () => true,
): number {
  if (count <= 0) return -1;
  let next = current < 0 ? (delta > 0 ? 0 : count - 1) : current + delta;
  while (next >= 0 && next < count) {
    if (isEnabled(next)) return next;
    next += delta;
  }
  return current;
}

/** The first or last enabled index, or -1 when there is none. */
export function edgeIndex(
  edge: 'first' | 'last',
  count: number,
  isEnabled: (index: number) => boolean = () => true,
): number {
  if (edge === 'first') {
    for (let i = 0; i < count; i += 1) if (isEnabled(i)) return i;
  } else {
    for (let i = count - 1; i >= 0; i -= 1) if (isEnabled(i)) return i;
  }
  return -1;
}

/**
 * Type-to-find. `from` is the active index. One typed letter, or a repeated
 * letter ("bb"), moves to the next entry that starts with that letter, even
 * when the active entry already matches (WAI-ARIA listbox). Longer text
 * finds the first entry that starts with it, beginning at `from` and
 * wrapping, so the active entry stays while it still matches. Returns -1
 * when nothing matches.
 */
export function typeaheadMatch(
  labels: readonly string[],
  query: string,
  from: number,
  isEnabled: (index: number) => boolean = () => true,
): number {
  const count = labels.length;
  const needle = query.toLowerCase();
  if (count === 0 || needle.length === 0) return -1;
  const repeated =
    needle.length > 1 && [...needle].every((c) => c === needle[0]);
  const text = repeated ? needle[0] : needle;
  const next = repeated || needle.length === 1 || from < 0;
  const first = next ? from + 1 : from;
  for (let step = 0; step < count; step += 1) {
    const index = (((first + step) % count) + count) % count;
    if (isEnabled(index) && labels[index].toLowerCase().startsWith(text))
      return index;
  }
  return -1;
}

/** The search text after one more key. Text older than `resetMs` is dropped. */
export function nextTypeaheadBuffer(
  buffer: string,
  key: string,
  now: number,
  lastAt: number,
  resetMs = 600,
): string {
  return (now - lastAt > resetMs ? '' : buffer) + key;
}
