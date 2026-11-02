# Voice dictation

Raw, AI, and Live use the keyboard microphone; the floating microphone is optional when overlay access is available.

## Sub-features

- `voice-raw` transcribes speech with Groq.
- `voice-ai` uses the selected answer provider after transcription.
- `voice-live` uses Gemini Live.
- `voice-overlay` offers dictation while Odicto remains the active keyboard.
- `voice-overlay-lock-listening` draws a voice-level halo that expands slightly beyond the stationary floating lock disc during unlocked recording.
- `voice-language-hint` passes the saved input language only to Groq in Raw and AI; Automatic omits the hint and Live remains independent.
- `voice-vocabulary` passes saved vocabulary tags only as Groq transcription context in Raw and AI.
- `voice-openai-live` streams through the explicitly chosen OpenAI Live model and key, with the same guarded composing/insertion lifecycle.

## How to get to it (user POV)

Complete Home setup, select Odicto in a synthetic editor, choose a mode, and hold and release the microphone. Enable the floating microphone only for its separate journey.

## Driving it with ADB and the visible app

Preconditions: a dedicated unlocked target, microphone permission, an eligible synthetic field, and the relevant keys entered privately. Real provider requests may consume quota.

- Use Raw to dictate a short synthetic phrase. Observe the final field or the app's explicit unverified/recovery state.
- Choose AI and provide a spoken instruction on synthetic selected text; verify the displayed result and destination. Repeat with each configured answer provider when keys are available.
- Choose Live and dictate a short synthetic phrase. Check visible output and cancellation separately.
- If overlay permission is available, repeat from the floating microphone with the Odicto keyboard active, and verify the keyboard microphone remains available.
- Hold the floating microphone in each voice mode. Once recording begins, keep the thumb over the microphone and verify the visible lock target has a quiet steady halo during silence and expands/brightens with speech, extending slightly outside the disc without clipping. Slide onto the lock and verify it disappears while the existing microphone feedback continues. Repeat release, cancel, connection failure, and a new recording; no lock halo should remain outside unlocked recording. Check light/dark themes and disabled system animations (a steady halo), and confirm the keyboard lock and all hit targets are unchanged.
- Set Spoken language to Hindi in Connections. In Raw, dictate a synthetic Hindi phrase, then repeat in AI with a synthetic instruction. Switch to Automatic and repeat with mixed Hindi/English speech. Record the actual transcripts separately from request-wiring tests; the hint does not guarantee script or code-switching accuracy. Verify Live still works independently of this setting.
- Save a synthetic product name in Vocabulary hints and repeat Raw/AI dictation, observing actual spelling separately from request-wiring tests. Verify removing all hints still permits dictation.
- For OpenAI Live, privately save a suitable key and select each offered model in turn. Dictate into an isolated host from both keyboard and overlay. Verify partial text, release/finalization without duplicate insertion, cancel, quick release, focus/selection changes, missing/removed key, network failure, and timeout with already typed words retained. Confirm no automatic provider retry. Compare Gemini Live after switching back. Missing credentials or physical hardware remain NOT CHECKED.

## Gotchas

- Keep protected fields and personal conversations out of test captures.
- A physical microphone, actual host insertion, and live provider service require their own evidence; emulator or mock results do not prove them.
