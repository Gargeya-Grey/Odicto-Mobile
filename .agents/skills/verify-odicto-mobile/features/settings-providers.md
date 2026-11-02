# Settings and providers

The Settings screen lets a user choose and save separate providers for AI Answers and Text Polish.

## Sub-features

- `providers-independent` keeps the two choices independent.
- `providers-persist` restores the saved choice and model when Settings reopens.
- `providers-legacy` retains an existing OpenRouter Polish configuration after an authorized upgrade.
- `spoken-language` saves one input language for Raw and AI speech, with Automatic as the default.
- `vocabulary-tags` adds/removes explicitly saved Groq spelling hints, including unfinished input at Save.
- `live-provider` keeps Gemini/OpenAI Live provider and model choices separate; OpenAI becomes available after saving its key.
- `live-model-picker` selects Gemini transcription by readable name in the shared searchable listbox and retains any previously saved custom model.

## How to get to it (user POV)

On Home, choose the button named `Open settings`. Select `AI answers` or `Text polish` in the settings section navigation.

## Driving it with ADB and the visible app

Preconditions: the candidate is running on the isolated target in the main skill, with no key values shown in evidence.

- Open Text polish and choose Gemini in its Provider control. Inspect that its model control changes to a Gemini model. Choose `Save changes` and wait for `Saved`.
- Open a Gemini model menu. Check that bundled names appear immediately, then wait for Google's catalog or a clear failure. Reopen it to exercise a fresh lookup. A listed model is not proof that the project has access or quota.
- Return Home and reopen Settings. Verify Text polish still shows Gemini. Set AI answers to OpenRouter and save; reopen both sections and verify Polish still shows Gemini.
- Repeat with Text polish set to OpenRouter and AI answers set to Gemini. Inspect the saved choice after reopening.
- On a separate authorized upgrade fixture with a known existing OpenRouter Polish model, install the candidate over the old build and confirm its provider and model remain unchanged. Do not do this to a personal installation without authorization.
- Under Connections, open Spoken language. Search for Hindi, choose it, and verify the immediate save. Return Home, reopen Settings, and restart the app to confirm persistence. Choose Automatic and confirm it persists too. Check the helper text explains that this applies to Raw and AI, and recommends Automatic when switching languages. Verify Back closes the menu and focus returns to its trigger.
- Under Connections, type `Odicto,New Delhi,` in Vocabulary hints. Check that two tags appear and spaces remain inside the second tag. Remove one with its named remove button. Paste a comma/newline list, then use Hindi IME composition and verify no premature split. Save a pending term without a final comma, reopen/restart, and confirm it became a saved tag. Verify Discard and Clear all followed by Save. An oversized list must show an error and retain rejected text. Check focus, TalkBack labels, wrapping, and remove targets in light/dark themes and at large font sizes.
- With no OpenAI key, verify Live offers Gemini and explains how to enable OpenAI. Enter a key privately and Save. Verify OpenAI appears, choose it and a model, Save, and reopen. Switch to Gemini and back; both model choices should remain. Remove the OpenAI key; an already selected OpenAI remains visible but disabled, and recording refuses with a clear missing-key notice instead of falling back.
- With Gemini selected under Live, open the model dropdown and search `Transcribe`. Select `Gemini 3.5 Transcribe Live`, Save, and reopen to confirm persistence. Back closes the menu and returns focus. If a custom model was saved previously, it remains under Current model. Search text does not create arbitrary model IDs. Verify OpenAI's model dropdown remains available after switching providers.

## Gotchas

- A UI selection before `Save changes` is an unsaved draft. Reopening is the persistence check.
- A mock bridge or browser preview cannot prove Android DataStore persistence.

- Vocabulary keyboard continuity: expand the keyboard in Vocabulary hints, type at least 20 characters, add a comma, and type another phrase. The grid must remain expanded through input-view refreshes. Move to a new editor and verify it starts collapsed. Record physical-device evidence separately from lifecycle unit tests.

- With Android autofill enabled (including NordPass), focus Vocabulary hints, type two letters and then at least 20 characters, add a comma to create a tag, and continue a second phrase. Verify no autofill popup takes focus or hides the keyboard. The settings WebView does not expose virtual credential fields to Android autofill services; API keys are managed explicitly by the app. Discard the synthetic draft afterward.
