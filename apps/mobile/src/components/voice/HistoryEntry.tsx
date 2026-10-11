import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
} from 'react';
import type { HistoryItem } from '../../native/voice';
import { copyToClipboard } from '../../lib/clipboard';
import { formatRelativeTime } from '../../lib/relativeTime';
import { Check, Copy } from './icons';

const COPIED_MS = 1200;

const OUTCOME: Record<string, string> = {
  inserted: 'Inserted',
  incomplete: 'Incomplete',
};
const outcomeLabel = (outcome: string) => OUTCOME[outcome] ?? 'Saved';

/** A clock that ticks every 30 s while a history list is on screen. */
export function useNow() {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);
  return now;
}

/** Copy one entry and show a check on its button for 1.2 s. */
export function useCopyFeedback(
  onCopied: () => void,
  onCopyFailed: () => void,
) {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  const copy = useCallback(
    (item: HistoryItem) => {
      void copyToClipboard(item.text)
        .then((copied) => {
          if (!copied) {
            onCopyFailed();
            return;
          }
          onCopied();
          setCopiedId(item.id);
          window.clearTimeout(timer.current);
          timer.current = window.setTimeout(() => setCopiedId(null), COPIED_MS);
        })
        .catch(onCopyFailed);
    },
    [onCopied, onCopyFailed],
  );
  return { copiedId, copy };
}

/** One history entry: when and how it ended, the text, and a copy button. */
export function HistoryEntry({
  item,
  now,
  copied,
  onCopy,
  lines,
  expandable,
}: {
  item: HistoryItem;
  now: number;
  copied: boolean;
  onCopy: (item: HistoryItem) => void;
  /** Lines shown before the text is cut. */
  lines: number;
  /** Offer "Show more" when the text is cut. */
  expandable?: boolean;
}) {
  const text = useRef<HTMLParagraphElement>(null);
  const [expanded, setExpanded] = useState(false);
  const [cut, setCut] = useState(false);
  useLayoutEffect(() => {
    const element = text.current;
    if (!element || expanded) return;
    const measure = () =>
      setCut(element.scrollHeight > element.clientHeight + 1);
    measure();
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, [item.text, expanded, lines]);

  const outcome = outcomeLabel(item.outcome);
  return (
    <li className="voice-entry">
      <p className="voice-entry-meta">
        <time
          dateTime={new Date(item.createdAt).toISOString()}
          title={new Date(item.createdAt).toLocaleString()}
        >
          {formatRelativeTime(item.createdAt, now)}
        </time>
        {' · '}
        <span data-outcome={item.outcome}>{outcome}</span>
      </p>
      <button
        type="button"
        className="voice-icon-btn voice-copy"
        data-copied={copied ? 'true' : undefined}
        aria-label={copied ? 'Copied' : 'Copy transcript'}
        onClick={() => onCopy(item)}
      >
        <span className="voice-copy-icon" data-icon="copy">
          <Copy size={20} />
        </span>
        <span className="voice-copy-icon" data-icon="check">
          <Check size={20} />
        </span>
      </button>
      <p
        ref={text}
        className="voice-entry-text"
        data-clamped={expanded ? undefined : 'true'}
        style={{ '--lines': lines } as CSSProperties}
      >
        {item.text}
      </p>
      {expandable && (cut || expanded) && (
        <button
          type="button"
          className="voice-link voice-entry-more"
          aria-expanded={expanded}
          onClick={() => setExpanded((value) => !value)}
        >
          {expanded ? 'Show less' : 'Show more'}
        </button>
      )}
      <span className="voice-sr" role="status">
        {copied ? 'Copied' : ''}
      </span>
    </li>
  );
}
