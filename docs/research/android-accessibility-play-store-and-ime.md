# Android accessibility warnings, Play distribution, and Odicto IME

Date: 2026-09-25

## Conclusion

Publishing Odicto on Google Play is worthwhile, but it is **not a guaranteed fix** for a banking app's warning that an accessibility-enabled app is active. The warning can come from the banking app's own security policy rather than Android, Google Play, or Play Protect. Google does not publish a rule saying that Play installation makes an AccessibilityService trusted.

The most reliable product direction is to make the Odicto IME the privacy-preferred path and remove or make AccessibilityService strictly optional. Odicto already has an `InputMethodService`; it currently provides voice controls and a small editing row, not a complete keyboard. Expanding it is compatible with Android's IME model, but building a Samsung Keyboard replacement is unnecessary. A voice-first keyboard with basic typing, clipboard, emoji, cursor gestures, and a one-tap switch back to Samsung is a more focused product.

## What publishing changes

- A Play-distributed app is known to Google Play Protect and normally installed/upgraded through Google Play rather than an internet-sideloading source. Google specifically classifies internet-sideloaded applications that declare sensitive APIs such as Accessibility as higher risk in some Play Protect enforcement paths.
- Play publication does not change Android's permission model and does not make an AccessibilityService invisible to banking apps.
- A banking app may inspect enabled accessibility services, package identity, signing identity, installer source, device integrity, or its own allowlist. Each bank's implementation and policy are private. No bank-specific behavior should be assumed without testing the exact released build with the exact bank app.
- An existing sideloaded Odicto installation must normally be replaced by the Play version with the same application ID and signing key. A differently signed build cannot update it in place. This is Android package-management behavior, not a Play trust guarantee.
- If Odicto is not an accessibility tool, it must keep `isAccessibilityTool="false"`, complete Google Play's AccessibilityService declaration, and present a separate prominent in-app disclosure and affirmative consent. Google permits AccessibilityService for a wide range of app functionality but requires this process and prohibits autonomous, open-ended automation.

## Why the current warning may remain

Odicto already routes dictated text through `InputConnection` when its IME owns the editor. The accessibility service is required only when Samsung Keyboard or another third-party IME is active. While the Odicto IME is active, the current accessibility service merely stands down; Android still reports it as enabled, so the banking app can still display its warning.

Automatically treating selected financial package names as an exception would be fragile and could look like bypassing a financial application's security control. It also would not guarantee that the bank's initial warning is suppressed.

## Product options

### 1. Keep accessibility and publish on Play

Use this only if seamless floating-microphone insertion while Samsung Keyboard is active is more important than avoiding accessibility warnings. Strengthen disclosures and explain why the service exists. Contact each affected bank after publication and request its official compatibility or allowlisting process. The result is bank-specific and must be verified physically.

### 2. Make Odicto a voice-first IME and remove AccessibilityService

This is the cleanest answer to the reported warning. Android gives the selected IME an `InputConnection` to the focused editor, so Odicto can dictate, type, paste, insert emoji, and move the cursor without AccessibilityService. Banking apps can still impose their own third-party-keyboard restrictions in sensitive fields, so Odicto should provide a visible switch-to-next-keyboard action and avoid claiming universal compatibility.

### 3. Keep accessibility as an explicitly optional advanced mode

This preserves seamless insertion over Samsung Keyboard, but it will continue to produce warnings in banks that reject any enabled accessibility service. It should not be the default path if avoiding that warning is a core product requirement.

## Building the keyboard

Do not attempt to reproduce Samsung Keyboard's prediction, autocorrect, handwriting, and language ecosystem initially. Build the smallest keyboard that makes Odicto comfortable and dependable:

- basic QWERTY, shift, numbers/symbols, backspace, enter, and switch keyboard;
- dictation through the existing IME target;
- an explicit clipboard panel that reads clipboard content only when the user opens it and never uploads or logs it;
- an emoji panel using Android Emoji2/Unicode support;
- a spacebar gesture recognizer where a tap inserts a space and deliberate movement drives cursor movement or selection through `InputConnection.setSelection()`;
- editor-aware behavior for password, phone, numeric, and no-personalized-learning fields, separating ordinary manual input from voice eligibility;
- protected-field restrictions and no clipboard, typed text, selection, transcripts, audio, or credentials in telemetry.

Cursor gestures must have touch slop, cancellation, multi-touch handling, and a non-gesture fallback. They should be disabled or made non-destructive while an AI selection is captured or Live composing is active so that cursor movement cannot leave stale composing or selection state.

## Odicto implementation fit

The existing `OdictoImeService`, `DictationCoordinator`, and `DictationTarget` already provide the correct foundation:

- IME ownership is selected when Odicto is attached to the current editor.
- Accessibility is the fallback target when another keyboard is active.
- IME insertion uses `InputConnection.commitText()` and `setComposingText()`.
- Overlay and accessibility should not be broadened to become a general keyboard backend.

A full keyboard should live in an IME-specific editor/controller layer, with pure gesture and clipboard-policy tests plus instrumentation coverage for real `InputConnection` behavior, editor changes, selections, protected fields, and keyboard switching.

## Implemented outcome (September 25)

Adopted: a voice-first, accessibility-free `store` flavor plus a full keyboard.

- `store` flavor: no AccessibilityService, no floating overlay, no `SYSTEM_ALERT_WINDOW`. Verified with `aapt2` against the built APK and again on the device via `dumpsys package`.
- `legacy` flavor (`applicationIdSuffix ".legacy"`, not for distribution): keeps the existing overlay, pause tile, and narrow accessibility service so the pre-Play path stays testable beside the new build.
- The IME is now a real keyboard: letters, shift, numbers, symbols, emoji, an explicit clipboard panel, and a spacebar that types a space on a tap or moves the cursor when dragged.
- `EditorPolicy` now answers per capability. Typing works in numeric and phone fields; voice, emoji, and clipboard stay refused in password, numeric, phone, and no-personalized-learning fields.

Play distribution is not current. The store flavor is a clean, capability-free build that is ready for it, but no Play release, signing key, or store listing exists yet, and the migration UX for a differently signed install is deliberately not built until it is needed.

The unresolved item is the one this document predicted: whether the banking app actually stops warning. The store build removes the capability the warning names, but the bank's own policy is private and must be observed on a real device. The physical checklist is recorded in `docs/s24-ultra-testing.md`.

## Sources

- [Google Play: Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
- [Google Play Protect: Developer guidance for warnings](https://developers.google.com/android/play-protect/warning-dev-guidance)
- [Android Developers: Create an accessibility service](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Android Developers: Create an input method](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)
- [Android Developers: `InputConnection`](https://developer.android.com/reference/android/view/inputmethod/InputConnection)
- [Android Developers: Copy and paste](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)
- [Android Developers: Support modern emoji with Emoji2](https://developer.android.com/develop/ui/views/text-and-emoji/emoji2)
