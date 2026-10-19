import { describe, expect, it } from 'vitest';
import {
  CLIPBOARD_CONSENT_TEXT,
  describeClipboardSaving,
} from './clipboardConsent';

describe('describeClipboardSaving', () => {
  it('fails closed and disables the switch when the choice cannot be read', () => {
    expect(describeClipboardSaving(null, true)).toEqual({
      checked: false,
      disabled: true,
      line: 'Unavailable right now. Nothing is saved.',
    });
  });

  it('says off, and that Paste is off too', () => {
    expect(describeClipboardSaving(false, true)).toEqual({
      checked: false,
      disabled: false,
      line: 'Off. The Paste suggestion is off too.',
    });
  });

  it('says Paste works when it is on and Odicto is the keyboard', () => {
    expect(describeClipboardSaving(true, true)).toEqual({
      checked: true,
      disabled: false,
      line: 'On. Paste appears after you copy text.',
    });
  });

  it('says it waits for the keyboard when it is on but Odicto is not selected', () => {
    expect(describeClipboardSaving(true, false).line).toBe(
      'On. It starts once Odicto is your current keyboard.',
    );
  });
});

describe('CLIPBOARD_CONSENT_TEXT', () => {
  it('states what is kept, where, for how long, and the limits', () => {
    for (const part of [
      '50 recent items',
      'only on this phone',
      'not backed up',
      'marked sensitive',
      'not marked',
      'turn this off at any time',
      'does not delete',
    ]) {
      expect(CLIPBOARD_CONSENT_TEXT.toLowerCase()).toContain(part);
    }
  });
});
