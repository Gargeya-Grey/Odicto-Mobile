import { useId, useState, type CSSProperties, type ReactNode } from 'react';
import { Info } from './icons';

/** Switch: a 40 x 24 track inside a 56 x 48 touch target. Only transform and opacity move. */
export function Switch({
  checked,
  onChange,
  label,
  describedBy,
  disabled,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  label: string;
  describedBy?: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      className="voice-switch"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      aria-describedby={describedBy}
      disabled={disabled}
      onClick={() => onChange(!checked)}
    >
      <span className="voice-switch-track" aria-hidden="true">
        <span className="voice-switch-thumb" />
      </span>
    </button>
  );
}

export type SegmentedOption<T extends string | number> = {
  value: T;
  label: ReactNode;
  /** Spoken name when the visible label is short, such as "S". */
  name?: string;
};

/**
 * One choice from a few. `radio` groups use radiogroup semantics; `pressed`
 * groups keep the toggle-button semantics the home screen has always had.
 */
export function Segmented<T extends string | number>({
  options,
  value,
  onChange,
  label,
  kind = 'radio',
  disabled,
  describedBy,
}: {
  options: readonly SegmentedOption<T>[];
  value: T;
  onChange: (next: T) => void;
  label: string;
  kind?: 'radio' | 'pressed';
  disabled?: boolean;
  describedBy?: string;
}) {
  const index = options.findIndex((option) => option.value === value);
  const style = {
    '--n': options.length,
    '--i': Math.max(index, 0),
  } as CSSProperties;
  return (
    <div
      className="voice-segmented"
      role={kind === 'radio' ? 'radiogroup' : 'group'}
      aria-label={label}
      aria-describedby={describedBy}
      style={style}
    >
      <span
        className="voice-segmented-thumb"
        data-hidden={index < 0 ? 'true' : undefined}
        aria-hidden="true"
      />
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <button
            key={String(option.value)}
            type="button"
            role={kind === 'radio' ? 'radio' : undefined}
            aria-checked={kind === 'radio' ? selected : undefined}
            aria-pressed={kind === 'pressed' ? selected : undefined}
            aria-label={option.name}
            disabled={disabled}
            onClick={() => {
              if (!selected) onChange(option.value);
            }}
          >
            <span>{option.label}</span>
          </button>
        );
      })}
    </div>
  );
}

export function Chip({ children }: { children: ReactNode }) {
  return <span className="voice-chip">{children}</span>;
}

/** The "i" button that shows or hides a quiet explanation. 24 px glyph, 48 px touch target. */
function InfoButton({
  open,
  onToggle,
  controls,
  label,
}: {
  open: boolean;
  onToggle: () => void;
  controls: string;
  label: string;
}) {
  return (
    <button
      type="button"
      className="voice-info"
      aria-expanded={open}
      aria-controls={controls}
      aria-label={label}
      onClick={onToggle}
    >
      <span>
        <Info size={18} />
      </span>
    </button>
  );
}

/** Section label above a group, with an optional hidden explanation. */
export function SectionLabel({
  id,
  title,
  info,
}: {
  id: string;
  title: string;
  info?: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const infoId = `${id}-info`;
  return (
    <>
      <div className="voice-label">
        <h2 id={id}>{title}</h2>
        {info && (
          <InfoButton
            open={open}
            onToggle={() => setOpen((value) => !value)}
            controls={infoId}
            label={`About ${title}`}
          />
        )}
      </div>
      {info && (
        <p
          id={infoId}
          className="voice-info-text voice-info-section"
          hidden={!open}
        >
          {info}
        </p>
      )}
    </>
  );
}

/** An inset grouped list. Its children are rows. */
export function Group({
  children,
  labelledBy,
}: {
  children: ReactNode;
  labelledBy?: string;
}) {
  return (
    <ul className="voice-group" aria-labelledby={labelledBy}>
      {children}
    </ul>
  );
}

/** Quiet text under a group. */
export function Footer({ children }: { children: ReactNode }) {
  return <p className="voice-footer">{children}</p>;
}

export type RowIds = { id: string; labelId: string; describedBy?: string };
type Slot = ReactNode | ((ids: RowIds) => ReactNode);
const fill = (slot: Slot, ids: RowIds) =>
  typeof slot === 'function' ? slot(ids) : slot;

/**
 * One row of a group. Title and one short line on the left, a control on the
 * right (`trailing`) or underneath (`below`). Text longer than a line goes in
 * `info`, which opens from an "i" button. `asLabel` makes the title the label
 * of the control that gets `ids.id`.
 */
export function Row({
  title,
  asLabel,
  badge,
  action,
  secondary,
  info,
  trailing,
  below,
  hint,
}: {
  title: string;
  asLabel?: boolean;
  badge?: ReactNode;
  action?: ReactNode;
  secondary?: ReactNode;
  info?: ReactNode;
  trailing?: Slot;
  below?: Slot;
  /** Live status text that must stay visible, such as "Loading models…". */
  hint?: ReactNode;
}) {
  const base = useId();
  const [open, setOpen] = useState(false);
  const secondaryId = secondary ? `${base}-s` : undefined;
  const infoId = info ? `${base}-i` : undefined;
  const hintId = hint ? `${base}-h` : undefined;
  const ids: RowIds = {
    id: `${base}-c`,
    labelId: `${base}-l`,
    describedBy:
      [secondaryId, infoId, hintId].filter(Boolean).join(' ') || undefined,
  };
  return (
    <li className="voice-row" data-stacked={below ? 'true' : undefined}>
      <div className="voice-row-main">
        <div className="voice-row-text">
          <div className="voice-row-titleline">
            {asLabel ? (
              <label id={ids.labelId} htmlFor={ids.id}>
                {title}
              </label>
            ) : (
              <h3 id={ids.labelId}>{title}</h3>
            )}
            {badge}
          </div>
          {secondary && <p id={secondaryId}>{secondary}</p>}
        </div>
        {action}
        {info && (
          <InfoButton
            open={open}
            onToggle={() => setOpen((value) => !value)}
            controls={infoId!}
            label={`About ${title}`}
          />
        )}
        {trailing !== undefined && fill(trailing, ids)}
      </div>
      {below !== undefined && (
        <div className="voice-row-below">{fill(below, ids)}</div>
      )}
      {hint && (
        <p className="voice-row-hint" id={hintId}>
          {hint}
        </p>
      )}
      {info && (
        <p id={infoId} className="voice-info-text" hidden={!open}>
          {info}
        </p>
      )}
    </li>
  );
}
