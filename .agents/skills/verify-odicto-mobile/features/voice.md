# Voice dictation

Raw, AI, and Live use the keyboard microphone; the floating microphone is optional when overlay access is available.

## Sub-features

- `voice-raw` transcribes speech with Groq.
- `voice-ai` uses the selected answer provider after transcription.
- `voice-live` uses Gemini Live.
- `voice-overlay` offers dictation while Odicto remains the active keyboard.

## How to get to it (user POV)

Complete Home setup, select Odicto in a synthetic editor, choose a mode, and hold and release the microphone. Enable the floating microphone only for its separate journey.

## Driving it with ADB and the visible app

Preconditions: a dedicated unlocked target, microphone permission, an eligible synthetic field, and the relevant keys entered privately. Real provider requests may consume quota.

- Use Raw to dictate a short synthetic phrase. Observe the final field or the app's explicit unverified/recovery state.
- Choose AI and provide a spoken instruction on synthetic selected text; verify the displayed result and destination. Repeat with each configured answer provider when keys are available.
- Choose Live and dictate a short synthetic phrase. Check visible output and cancellation separately.
- If overlay permission is available, repeat from the floating microphone with the Odicto keyboard active, and verify the keyboard microphone remains available.

## Gotchas

- Keep protected fields and personal conversations out of test captures.
- A physical microphone, actual host insertion, and live provider service require their own evidence; emulator or mock results do not prove them.
