import { useId, useRef } from 'react';
import type { VocabularyDraft } from '../../lib/vocabularyHints';

export function VocabularyTags({
  id,
  describedBy,
  state,
  disabled,
  onInput,
  onCommit,
  onRemove,
  onClear,
}: {
  id: string;
  describedBy?: string;
  state: VocabularyDraft;
  disabled: boolean;
  onInput: (text: string, composing?: boolean) => void;
  onCommit: (text?: string) => void;
  onRemove: (index: number) => void;
  onClear: () => void;
}) {
  const errorId = useId();
  const input = useRef<HTMLInputElement>(null);
  const composing = useRef(false);
  return (
    <div className="voice-vocabulary">
      {state.tags.length > 0 && (
        <ul className="voice-vocabulary-tags" aria-label="Vocabulary hints">
          {state.tags.map((term, index) => (
            <li className="voice-vocabulary-tag" key={term}>
              <span dir="auto">{term}</span>
              <button
                type="button"
                disabled={disabled}
                aria-label={`Remove ${term}`}
                onClick={() => {
                  onRemove(index);
                  input.current?.focus();
                }}
              >
                <span aria-hidden="true">×</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <input
        ref={input}
        id={id}
        className="voice-input"
        value={state.input}
        disabled={disabled}
        placeholder="For example: Wi-Fi, Bluetooth, New York"
        aria-describedby={
          [describedBy, state.error ? errorId : ''].filter(Boolean).join(' ') ||
          undefined
        }
        aria-invalid={state.error ? true : undefined}
        autoCapitalize="none"
        autoComplete="off"
        spellCheck={false}
        enterKeyHint="done"
        onChange={(event) => onInput(event.target.value, composing.current)}
        onCompositionStart={() => {
          composing.current = true;
        }}
        onCompositionEnd={(event) => {
          composing.current = false;
          onInput(event.currentTarget.value);
        }}
        onKeyDown={(event) => {
          if (
            composing.current ||
            event.nativeEvent.isComposing ||
            event.keyCode === 229
          )
            return;
          if (
            event.key === 'Enter' ||
            event.key === ',' ||
            event.key === '，'
          ) {
            event.preventDefault();
            onCommit();
          }
        }}
        onPaste={(event) => {
          const text = event.clipboardData.getData('text');
          if (!/[,，\r\n]/u.test(text)) return;
          event.preventDefault();
          const target = event.currentTarget;
          const start = target.selectionStart ?? state.input.length;
          const end = target.selectionEnd ?? start;
          onCommit(state.input.slice(0, start) + text + state.input.slice(end));
        }}
      />
      <p className="voice-vocabulary-note">
        Type a comma or press Enter to add a tag. Spaces keep a phrase together.
        Save changes to keep your hints.
      </p>
      <p
        className="voice-vocabulary-error"
        id={errorId}
        role="status"
        aria-live="polite"
      >
        {state.error}
      </p>
      {(state.tags.length > 0 || state.input) && (
        <button
          type="button"
          className="voice-link voice-vocabulary-clear"
          disabled={disabled}
          onClick={() => {
            onClear();
            input.current?.focus();
          }}
        >
          Clear all hints
        </button>
      )}
    </div>
  );
}
