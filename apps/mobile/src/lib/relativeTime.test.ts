import { describe, expect, it } from 'vitest';
import { formatRelativeTime } from './relativeTime';

const now = new Date(2026, 9, 11, 12, 0, 0).getTime();
const minutes = (n: number) => n * 60_000;

describe('formatRelativeTime', () => {
  it('says "Just now" under a minute and for future timestamps', () => {
    expect(formatRelativeTime(now - 5_000, now)).toBe('Just now');
    expect(formatRelativeTime(now + 5_000, now)).toBe('Just now');
  });

  it('counts minutes and hours', () => {
    expect(formatRelativeTime(now - minutes(2), now)).toBe('2 min ago');
    expect(formatRelativeTime(now - minutes(59), now)).toBe('59 min ago');
    expect(formatRelativeTime(now - minutes(60), now)).toBe('1 h ago');
    expect(formatRelativeTime(now - minutes(60 * 23), now)).toBe('23 h ago');
  });

  it('switches to a date after a day', () => {
    const label = formatRelativeTime(now - minutes(60 * 30), now, 'en-US');
    expect(label).toMatch(/^Oct 10, /);
  });

  it('adds the year for another year', () => {
    const old = new Date(2025, 2, 3, 9, 5).getTime();
    expect(formatRelativeTime(old, now, 'en-US')).toMatch(/2025/);
  });
});
