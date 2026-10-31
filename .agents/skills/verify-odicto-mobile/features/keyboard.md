# Keyboard and protected fields

The Odicto keyboard starts in its voice-first layout, can expand for typing, and refuses sensitive actions in protected fields.

## Sub-features

- `keyboard-typing` inserts synthetic text in an eligible host field.
- `keyboard-emoji` inserts repeated emoji without closing the panel.
- `keyboard-protected` refuses voice, emoji, and clipboard in protected fields while allowing ordinary typing where supported.

## How to get to it (user POV)

Enable and select Odicto from the Home setup rows, then focus an eligible test field.

## Driving it with ADB and the visible app

Preconditions: the dedicated target is unlocked and the debug keyboard is selected. Build and install the separate keyboard trial host only under `tools/keyboard-interaction-host/README.md` and its device-preservation rules.

- Run the fixture's `voicefirst` and `typing` scenarios with `imePackage app.odicto.mobile.debug`; inspect the instrumentation `status` and summary rather than ADB's exit code alone.
- On a dedicated synthetic editor, focus a password and then a numeric field. Check the visible refusal for voice, emoji, and clipboard; confirm ordinary numeric typing works in the numeric field.

## Gotchas

- The trial updates the keyboard's recent emoji through real presses. Its own README defines what it may access and which controls it must never press.
- A successful `commitText` call alone is not proof that a host rendered the change.
