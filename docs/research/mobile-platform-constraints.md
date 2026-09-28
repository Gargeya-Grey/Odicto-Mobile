# Mobile Platform Constraints for System-Wide Voice Input

Verified against first-party platform documentation on 2026-09-01.

## Android

- Android's input-method framework explicitly supports alternative system-wide input methods, including speech input. An app implements this by providing an `InputMethodService`, and inserts text through the active `InputConnection`. This is the safest foundation for writing into arbitrary text fields.
- Only one input method is active at a time. Users must enable Odicto in system settings and select it as their keyboard/input method.
- A real microphone foreground service must declare the `microphone` service type, `FOREGROUND_SERVICE_MICROPHONE`, and `RECORD_AUDIO`. A WebView notification or wake lock is not a foreground service.
- Android restricts starting microphone foreground services from the background. User interaction, being the current input method, notifications, widgets, and certain other cases can permit service startup, but microphone access still has while-in-use restrictions that must be tested across supported Android versions.
- Google Play permits AccessibilityService use, but general voice assistants are not automatically accessibility tools. Non-accessibility tools require declarations, prominent disclosure, affirmative consent, and narrow deterministic behavior. Building core text insertion on AccessibilityService creates avoidable review, privacy, and trust risk.
- Recommended Android foundation: an Odicto IME owns cursor insertion, including ordinary typing, emoji, clipboard, and cursor control. The published store build uses only this path.
- An `InputConnection` success return is API acceptance, not proof of visible text in every host app. Native delivery therefore distinguishes confirmed, accepted-but-unverified, and rejected/stale outcomes, with explicit Copy and local history recovery instead of automatic retries. X behavior still requires physical testing; source changes alone do not establish a host-specific fix.
- Android clipboard access is conditional, not a guaranteed historical stream. Odicto's approved automatic local capture is gated by default-IME identity, eligible editor, preference, and platform access; marked-sensitive clips are skipped. Copies missed while stopped/inaccessible cannot be recovered from a platform history API. See [local clipboard policy](../privacy-and-security.md#local-clipboard-and-editor-reads) for retention and disable controls.
- Editors may expose incomplete, stale, or unreadable text/selection snapshots. Selected-only Polish still requires transient local complete-field guard reads; only its target substring is transmitted. Unsupported snapshots fail closed rather than silently polishing a partial field.
- Keyboard geometry follows usable width, not orientation alone: split rows activate at >=600dp, while narrow windows retain compact layouts. Navigation insets and the IME window bound the controls; panels and hold choices stay inside that window. The compact IME redesign does not change floating overlay appearance or gestures.
- When a different keyboard is the active input method, no IME-owned `InputConnection` exists and a normal app cannot programmatically switch to its own IME. Seamless insertion into the focused field therefore requires an AccessibilityService (`ACTION_SET_TEXT`/`ACTION_PASTE` on the focused node) or a manual clipboard paste. Odicto keeps a narrow accessibility service in its `legacy` flavor for that case, behind a Play disclosure and consent flow, and omits the service entirely from the published store build.
- Publishing on Play does not remove a banking app's accessibility warning; the warning comes from that app's own policy. The only reliable fix is to ship without the capability, which is why the store flavor drops both the accessibility service and the overlay.
- Android does not clear a previously enabled accessibility service when an update removes it, so users must switch it off in system settings after migrating.
- OEM cached-app freezers (observed: Samsung One UI "Freecess", Android 16, S24 Ultra) suspend a plain backgrounded service's process while its overlay window stays visible but stops responding to touches. A foreground service keeps the process perceptible and exempt from that freezer; Odicto's overlay therefore runs as a `specialUse` foreground service (`FOREGROUND_SERVICE_SPECIAL_USE` plus `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`). Services started with `startService` are not restarted after an APK update or reboot — only system-bound services (accessibility, IME) come back — so the accessibility binding also resurrects the overlay service.

## iOS

- A custom keyboard can insert text through `UITextDocumentProxy`, but it is unavailable in secure fields, phone-pad fields, apps that reject custom keyboards, and other protected contexts.
- Apple states that custom keyboard extensions do not have access to the device microphone, so direct dictation from an iOS keyboard extension is not available.
- Custom keyboards cannot draw outside their keyboard view. iOS therefore cannot provide an Android-style always-on floating bubble over arbitrary apps through public App Store APIs.
- Enabling `RequestsOpenAccess` gives a keyboard extension network access and access to a shared container, but increases the privacy disclosure and trust burden; it does not grant microphone access.
- Exact Android feature parity is not technically available on iOS under the documented public extension model. The iOS product needs a deliberately different interaction, such as an in-app recorder plus keyboard/clipboard handoff, or an AI keyboard that transforms text produced by Apple's own dictation.

## Architecture Implications

- Capacitor remains suitable for onboarding, settings, account, history, subscriptions, and shared UI.
- The always-on execution path must be native: Kotlin `InputMethodService` plus native recording/foreground-service code on Android; Swift keyboard/app-extension code on iOS.
- Current Android voice and explicit Polish use native direct-provider transport with device-encrypted BYOK; they do not use the retained web/backend API. Backend billing/account architecture remains separate; see [Architecture](../architecture.md).
- BYOK is an advanced mode. Keys must be stored using platform-backed encrypted storage, never plain Capacitor Preferences.
- Product documentation must promise "system-wide" only within platform limits and must list protected/unsupported fields explicitly.

## First-Party Sources

- [Android: Create an input method](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)
- [Android: Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android: Restrictions on starting foreground services from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Google Play: Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
- [Apple: App Extension Programming Guide — Custom Keyboard](https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/CustomKeyboard.html)
