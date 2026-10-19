export const CLIPBOARD_CONSENT_TITLE = 'Save copied text and offer Paste';

/** Always visible under the switch: the choice is only informed if the reader can see what it does. */
export const CLIPBOARD_CONSENT_TEXT =
  'When this is on, Odicto saves text you copy while Odicto is your keyboard in a normal text field. It also offers that text as a Paste suggestion for 30 seconds. Odicto keeps 50 recent items plus your pins, only on this phone. They are not backed up, logged or sent anywhere. Copies marked sensitive are skipped, but a secret that is not marked, such as some copied passwords, can still be saved. You can turn this off at any time here or in the keyboard clipboard panel. Turning it off does not delete what is saved.';

export type ClipboardConsentRow = {
  checked: boolean;
  disabled: boolean;
  line: string;
};

/** What the setup row shows. `saving` is null when the saved choice cannot be read. */
export function describeClipboardSaving(
  saving: boolean | null,
  keyboardSelected: boolean,
): ClipboardConsentRow {
  if (saving === null) {
    return {
      checked: false,
      disabled: true,
      line: 'Unavailable right now. Nothing is saved.',
    };
  }
  if (!saving) {
    return {
      checked: false,
      disabled: false,
      line: 'Off. The Paste suggestion is off too.',
    };
  }
  return {
    checked: true,
    disabled: false,
    line: keyboardSelected
      ? 'On. Paste appears after you copy text.'
      : 'On. It starts once Odicto is your current keyboard.',
  };
}
