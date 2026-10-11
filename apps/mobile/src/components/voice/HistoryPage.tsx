import type { HistoryItem } from '../../native/voice';
import { Group } from './controls';
import { HistoryEntry, useCopyFeedback, useNow } from './HistoryEntry';

export function HistoryPage({
  entries,
  onCopyFailed,
  onCopied,
  onGoHome,
}: {
  entries: HistoryItem[] | null;
  onCopyFailed: () => void;
  onCopied: () => void;
  onGoHome: () => void;
}) {
  const now = useNow();
  const { copiedId, copy } = useCopyFeedback(onCopied, onCopyFailed);

  if (entries === null) return <div aria-busy="true" />;
  if (entries.length === 0) {
    return (
      <section className="voice-group voice-empty">
        <h2>No dictations yet</h2>
        <p>
          Your latest 30 recordings appear here, on this device only. Copy a
          result whenever you need it.
        </p>
        <button type="button" className="voice-link" onClick={onGoHome}>
          Back to home
        </button>
      </section>
    );
  }
  return (
    <Group>
      {entries.map((item) => (
        <HistoryEntry
          key={item.id}
          item={item}
          now={now}
          copied={copiedId === item.id}
          onCopy={copy}
          lines={4}
          expandable
        />
      ))}
    </Group>
  );
}
