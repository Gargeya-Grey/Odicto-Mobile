import { describeUpdate } from '../../lib/updateCard';
import type { UpdateStatus } from '../../native/update';
import { Group, Row } from './controls';

/** "Odicto 1.2.0 is available": a card at the top of Home with Download and Not now. Nothing when up to date. */
export function UpdateCard({
  status,
  onDownload,
  onDismiss,
}: {
  status: UpdateStatus | null;
  onDownload: () => void;
  onDismiss: () => void;
}) {
  const text = describeUpdate(status);
  if (!text) return null;
  return (
    <section className="voice-section" aria-label="Update available">
      <Group>
        <Row
          title={text.title}
          secondary={text.line}
          below={() => (
            <div className="voice-actions">
              <button
                type="button"
                className="voice-btn voice-btn-primary"
                onClick={onDownload}
              >
                Download
              </button>
              <button
                type="button"
                className="voice-btn voice-btn-tertiary"
                onClick={onDismiss}
              >
                Not now
              </button>
            </div>
          )}
        />
      </Group>
    </section>
  );
}
