# Mobile Platform Constraints for System-Wide Voice Input

Verified against first-party platform documentation on 2026-09-01.

## Android

- Android's input-method framework explicitly supports alternative system-wide input methods, including speech input. An app implements this by providing an `InputMethodService`, and inserts text through the active `InputConnection`. This is the safest foundation for writing into arbitrary text fields.
- Only one input method is active at a time. Users must enable Odicto in system settings and select it as their keyboard/input method.
- A real microphone foreground service must declare the `microphone` service type, `FOREGROUND_SERVICE_MICROPHONE`, and `RECORD_AUDIO`. A WebView notification or wake lock is not a foreground service.
- Android restricts starting microphone foreground services from the background. User interaction, being the current input method, notifications, widgets, and certain other cases can permit service startup, but microphone access still has while-in-use restrictions that must be tested across supported Android versions.
- Google Play permits AccessibilityService use, but general voice assistants are not automatically accessibility tools. Non-accessibility tools require declarations, prominent disclosure, affirmative consent, and narrow deterministic behavior. Building core text insertion on AccessibilityService creates avoidable review, privacy, and trust risk.
- Recommended Android foundation: an Odicto IME owns cursor insertion; a native floating control communicates with the IME/recording service. Accessibility-based insertion should not be required for the primary product.

## iOS

- A custom keyboard can insert text through `UITextDocumentProxy`, but it is unavailable in secure fields, phone-pad fields, apps that reject custom keyboards, and other protected contexts.
- Apple states that custom keyboard extensions do not have access to the device microphone, so direct dictation from an iOS keyboard extension is not available.
- Custom keyboards cannot draw outside their keyboard view. iOS therefore cannot provide an Android-style always-on floating bubble over arbitrary apps through public App Store APIs.
- Enabling `RequestsOpenAccess` gives a keyboard extension network access and access to a shared container, but increases the privacy disclosure and trust burden; it does not grant microphone access.
- Exact Android feature parity is not technically available on iOS under the documented public extension model. The iOS product needs a deliberately different interaction, such as an in-app recorder plus keyboard/clipboard handoff, or an AI keyboard that transforms text produced by Apple's own dictation.

## Architecture Implications

- Capacitor remains suitable for onboarding, settings, account, history, subscriptions, and shared UI.
- The always-on execution path must be native: Kotlin `InputMethodService` plus native recording/foreground-service code on Android; Swift keyboard/app-extension code on iOS.
- Provider orchestration, authentication, metering, subscriptions, and prompt behavior should live behind a shared backend API. Native clients should not duplicate provider-specific business logic.
- BYOK is an advanced mode. Keys must be stored using platform-backed encrypted storage, never plain Capacitor Preferences.
- Product documentation must promise "system-wide" only within platform limits and must list protected/unsupported fields explicitly.

## First-Party Sources

- [Android: Create an input method](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)
- [Android: Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android: Restrictions on starting foreground services from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Google Play: Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
- [Apple: App Extension Programming Guide — Custom Keyboard](https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/CustomKeyboard.html)
