# Settings and providers

The Settings screen lets a user choose and save separate providers for AI Answers and Text Polish.

## Sub-features

- `providers-independent` keeps the two choices independent.
- `providers-persist` restores the saved choice and model when Settings reopens.
- `providers-legacy` retains an existing OpenRouter Polish configuration after an authorized upgrade.

## How to get to it (user POV)

On Home, choose the button named `Open settings`. Select `AI answers` or `Text polish` in the settings section navigation.

## Driving it with ADB and the visible app

Preconditions: the candidate is running on the isolated target in the main skill, with no key values shown in evidence.

- Open Text polish and choose Gemini in its Provider control. Inspect that its model control changes to a Gemini model. Choose `Save changes` and wait for `Saved`.
- Open a Gemini model menu. Check that bundled names appear immediately, then wait for Google's catalog or a clear failure. Reopen it to exercise a fresh lookup. A listed model is not proof that the project has access or quota.
- Return Home and reopen Settings. Verify Text polish still shows Gemini. Set AI answers to OpenRouter and save; reopen both sections and verify Polish still shows Gemini.
- Repeat with Text polish set to OpenRouter and AI answers set to Gemini. Inspect the saved choice after reopening.
- On a separate authorized upgrade fixture with a known existing OpenRouter Polish model, install the candidate over the old build and confirm its provider and model remain unchanged. Do not do this to a personal installation without authorization.

## Gotchas

- A UI selection before `Save changes` is an unsaved draft. Reopening is the persistence check.
- A mock bridge or browser preview cannot prove Android DataStore persistence.
