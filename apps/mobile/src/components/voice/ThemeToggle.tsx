import { useRef } from 'react';
import type { Point, Theme } from '../../lib/theme';
import { Moon, Sun } from './icons';

/**
 * The navbar control: one tap switches between light and dark. It names the mode a tap
 * switches to, and the new theme grows from the middle of this button.
 */
export function ThemeToggle({
  theme,
  onToggle,
}: {
  theme: Theme;
  onToggle: (origin: Point) => void;
}) {
  const button = useRef<HTMLButtonElement>(null);
  const target = theme === 'dark' ? 'light' : 'dark';
  return (
    <button
      ref={button}
      type="button"
      className="voice-theme-toggle"
      data-target={target}
      aria-label={`Switch to ${target} mode`}
      onClick={() => {
        const rect = button.current?.getBoundingClientRect();
        onToggle(
          rect
            ? { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }
            : { x: window.innerWidth, y: 0 },
        );
      }}
    >
      <span className="voice-theme-icon" aria-hidden="true">
        <Sun size={18} data-glyph="light" />
        <Moon size={18} data-glyph="dark" />
      </span>
      <span className="voice-theme-text" aria-hidden="true">
        <span data-glyph="light">Light</span>
        <span data-glyph="dark">Dark</span>
      </span>
    </button>
  );
}
