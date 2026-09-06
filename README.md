# Odicto Mobile

Odicto is an Android voice input app with a native keyboard microphone, an optional floating control, and React/Capacitor settings. Voice requests go directly from the phone to the selected provider using keys stored with Android Keystore. No computer or Odicto backend is required for Android dictation.

- **Raw:** Groq Whisper transcribes speech after release.
- **AI:** Groq transcribes the instruction, then Gemini or OpenRouter answers or edits explicitly selected text.
- **Live:** Gemini Live streams words into the original editor without pasting a second copy at completion.

Settings includes touch feedback, an optional text preview, provider configuration, and local history. The floating control hides while the Odicto keyboard is visible. Settings and native controls support portrait and landscape layouts.

## Stack

- `apps/mobile`: React, Vite, Capacitor, Kotlin Android IME/overlay/recording
- `apps/api`: separate Fastify web/backend implementation; not used by Android voice
- `packages/contracts`: versioned Zod API contracts
- `packages/domain`: provider-neutral filters and dictation state rules

## Development

Requirements: Node.js 22+, npm, and Android Studio with JDK 21 and Android SDK 35 for native work.

```bash
npm ci --include=dev --include=optional
npm run verify
```

For the separate web/backend implementation, copy `apps/api/.env.example` to `apps/api/.env`, configure it as described in [Environment](docs/environment.md), and run the API and web UI in separate terminals:

```bash
npm run dev
npm run dev:mobile
```

Android:

```bash
npm run build --workspace @odicto/mobile
npm run android:sync
npm run android:open
```

From `apps/mobile/android`, run `gradlew.bat testDebugUnitTest assembleDebug` on Windows, or `./gradlew testDebugUnitTest assembleDebug` elsewhere, with JDK 21 and SDK 35 configured. Install `app/build/outputs/apk/debug/app-debug.apk` on the phone, enter provider keys in Settings → API connections, and complete Android setup to enable the keyboard and permissions. USB is only needed for installation and debugging.

Keep the root `package-lock.json` as the single npm lockfile. Local environment files, signing keys, generated builds, and diagnostic screenshots are excluded from Git. Device testing details and known validation limits are recorded in [Android development](docs/android-development.md) and [S24 Ultra checks](docs/s24-ultra-testing.md).

## Product limits

Dictation requires Odicto as the active input method and a permitted text editor. Password, phone, numeric/payment-like, and protected fields block voice controls. Insertion revalidates the original editor; changed focus saves the result locally instead of inserting elsewhere. Audio is request-scoped and unretained; history stays on the device. Overlay permission is optional, and the keyboard microphone is the fallback. AccessibilityService is not used.

iOS, accounts, billing, and managed production services are deferred. Tablet viewport checks do not establish physical-tablet microphone or IME reliability.

See `docs/architecture.md`, `docs/android-development.md`, `docs/environment.md`, and `docs/privacy-and-security.md`.
