# Text Polish

The keyboard's Polish action edits a selected substring, or a whole eligible field when no text is selected, using the configured provider.

## Sub-features

- `polish-selected` changes only the selected text.
- `polish-whole` changes the whole eligible field once.
- `polish-recovery` preserves a completed result for Copy/Dismiss when safe replacement cannot be verified.
- `polish-failure` leaves the field unchanged on missing key, refusal, or provider error.

## How to get to it (user POV)

Choose a provider in Settings > Text polish, save, then focus a synthetic text field with Odicto selected and tap the keyboard's Polish action.

## Driving it with ADB and the visible app

Preconditions: a dedicated unlocked target, eligible synthetic field, selected Odicto keyboard, and the provider key entered privately by its owner. Live requests may use provider quota.

- Test a missing selected-provider key first. The action reports the missing key and leaves the synthetic field unchanged.
- With Gemini selected and its key present, polish a deliberately misspelled synthetic sentence. Observe the field before and after and confirm a single replacement.
- Select only part of a second synthetic sentence and polish it. Verify the surrounding text is unchanged.
- Change the field or focus while a request is pending. Verify no stale overwrite; if a result completes but cannot be applied, check the explicit Copy/Dismiss recovery.
- Repeat the applicable checks with OpenRouter and its own key. Record each provider independently.

## Gotchas

- The keyboard interaction fixture explicitly forbids pressing Polish; use a separate synthetic editor on the dedicated target.
- Generated output can vary. Verify scope, preserved surrounding text, and safe delivery, not a fixed wording chosen by the test.
- Automated fake-provider tests do not establish a live provider request or host-app insertion.
