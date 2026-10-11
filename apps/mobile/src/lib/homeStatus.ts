import type { VoiceSettings } from '../native/voice';
import type { Readiness } from './voiceStatus';

export type HomeStatus = {
  tone: Readiness['tone'];
  line: string;
  /** Which small text button the row shows: scroll to setup, or resume. */
  action: 'fix' | 'resume' | null;
};

/** The one short line for the Home status row. */
export function describeHomeStatus(
  readiness: Readiness | null,
): HomeStatus | null {
  if (!readiness) return null;
  if (readiness.tone === 'paused')
    return { tone: 'paused', line: 'Paused', action: 'resume' };
  if (readiness.tone === 'ready')
    return { tone: 'ready', line: 'Ready to dictate', action: null };
  const keyboard = readiness.items.find((item) => item.key === 'keyboard');
  const line =
    keyboard && !keyboard.ok
      ? keyboard.state === 'Not enabled'
        ? 'Enable the Odicto keyboard'
        : 'Select Odicto as your keyboard'
      : 'Allow microphone access';
  return { tone: 'setup', line, action: 'fix' };
}

/** One line under "Floating microphone". `overlayGranted` is unknown until the status loads. */
export function floatingMicLine(
  settings: Pick<VoiceSettings, 'enabled' | 'paused'> | null,
  overlayGranted: boolean | undefined,
): string {
  if (settings?.paused) return 'Paused, so the bubble is hidden';
  if (settings?.enabled && overlayGranted === false)
    return 'Needs overlay permission. See setup.';
  if (settings?.enabled) return 'On screen over other apps';
  return 'Keep voice controls within reach';
}

/** One line under "Pause Odicto". */
export function pauseLine(paused: boolean): string {
  return paused
    ? 'Paused. Protected apps see nothing.'
    : 'Stops the mic and voice typing elsewhere';
}

export const MODE_HINT: Record<VoiceSettings['mode'], string> = {
  raw: 'Types exactly what you say.',
  ai: 'Writes or edits from your instruction.',
  live: 'Shows words while you speak.',
};
