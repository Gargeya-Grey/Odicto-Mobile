const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * Short, calm time labels for history: "Just now", "2 min ago", "3 h ago",
 * then a date. Timestamps in the future (clock changes) read as "Just now".
 */
export function formatRelativeTime(
  timestamp: number,
  now: number,
  locale?: string,
): string {
  const age = now - timestamp;
  if (age < MINUTE) return 'Just now';
  if (age < HOUR) return `${Math.floor(age / MINUTE)} min ago`;
  if (age < DAY) return `${Math.floor(age / HOUR)} h ago`;
  const date = new Date(timestamp);
  const sameYear = date.getFullYear() === new Date(now).getFullYear();
  return date.toLocaleString(locale, {
    month: 'short',
    day: 'numeric',
    ...(sameYear ? {} : { year: 'numeric' }),
    hour: 'numeric',
    minute: '2-digit',
  });
}
