# Keyboard and protected fields

The Odicto keyboard starts in its voice-first layout, can expand for typing, and refuses sensitive actions in protected fields.

## Sub-features

- `keyboard-typing` inserts synthetic text in an eligible host field.
- `keyboard-emoji` inserts repeated emoji without closing the panel.
- `keyboard-protected` refuses voice, emoji, and clipboard in protected fields while allowing ordinary typing where supported.
- `keyboard-numpad` opens numeric and phone fields directly to large keys. Integers show digits; signed and decimal fields add their requested symbols; phone fields add +, * and #. Backspace and the editor action remain available. The collapse toggle is disabled while the keypad is required.

## How to get to it (user POV)

Enable and select Odicto from the Home setup rows, then focus an eligible test field.

## Driving it with ADB and the visible app

Preconditions: the dedicated target is unlocked and the debug keyboard is selected. Build and install the separate keyboard trial host only under `tools/keyboard-interaction-host/README.md` and its device-preservation rules.

- Run the fixture's `voicefirst` and `typing` scenarios with `imePackage app.odicto.mobile.debug`; inspect the instrumentation `status` and summary rather than ADB's exit code alone.
- On a dedicated synthetic editor, focus a password and then a numeric field. Check the visible refusal for voice, emoji, and clipboard; confirm ordinary numeric typing works in the numeric field.
- Focus integer, signed, decimal, signed-decimal, numeric-password and phone fixtures. Confirm the keypad opens without expanding it manually, has no alphabetic page switch, and exposes only the appropriate symbols. Type synthetic values and verify exact host contents, Backspace, and Next/Done. Check portrait and landscape, light and dark, and large font settings. Return to a text field and confirm the compact start and normal letter keyboard. These device checks require an authorized isolated host and are separate from Robolectric coverage.
- The trial host's `numpad` scenario automates immediate presentation, symbol sets, real typing, exact host contents and selection, Backspace, balanced edits, and return to compact text and expanded letters. It supports both Odicto package names. It does not press Next/Done or change orientation, theme or font settings; check those separately. See the [trial host instructions](../../../../../tools/keyboard-interaction-host/README.md).

## Gotchas

- The trial updates the keyboard's recent emoji through real presses. Its own README defines what it may access and which controls it must never press.
- A successful `commitText` call alone is not proof that a host rendered the change.

- In Vocabulary hints, type a character and immediately hold Backspace without lifting/repressing to recover. Repeat with a longer synthetic phrase in both expanded and compact layouts. Verify continued deletion, stopping on release, and preservation of any pre-existing draft. The replay covers a typed character and first delete completing before a cursor report, including a net unchanged cursor.
