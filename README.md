# Odicto Mobile

Odicto is an Android voice input app with a native keyboard microphone, an optional floating control, and React/Capacitor settings. Voice requests go directly from the phone to the selected provider using keys stored with Android Keystore. No computer or Odicto backend is required for Android dictation.

- **Raw:** Groq Whisper transcribes speech after release.
- **AI:** Groq transcribes the instruction, then Gemini or OpenRouter answers or edits explicitly selected text.
- **Live:** Gemini Live streams words into the original editor without pasting a second copy at completion.

Odicto follows your phone's light or dark setting. The **Light / Dark** button in the header switches the app, the keyboard and the floating microphone together; **Settings → Appearance → System** goes back to following the phone.

Settings includes touch feedback, an optional text preview, provider configuration, and local history. The floating control hides while the Odicto keyboard is visible. Settings and native controls support portrait and landscape layouts.

## Download and try it

1. On your Android phone (Android 6.0 or newer), open the [latest release](https://github.com/Gargeya-Grey/Odicto-Mobile/releases) and download the `Odicto-v*.apk` file.
2. Open the file and allow the install when Android asks.
3. Open Odicto, enable the Odicto keyboard, and grant the microphone permission.
4. Add your own [Groq API key](https://console.groq.com/keys) in **Settings → Connections**.

**Staying up to date.** When you open Odicto it checks, at most once an hour, whether a newer release exists and shows an **Odicto X is available** card on Home. Tap **Download**, open the file when it finishes, and Android updates Odicto in place; your keys and history stay. This is the only request Odicto makes on its own, and it sends nothing about you. Turn it off in **Settings → Updates**.

Already have a build you installed from Android Studio or USB? Android will refuse this APK because the signing key differs. Uninstalling the old build first deletes its saved keys, settings and history, so note your keys before you do it.

Odicto is in beta. Please [report bugs and send feedback](https://github.com/Gargeya-Grey/Odicto-Mobile/issues/new/choose) with your phone model and Android version. To contribute code, read [CONTRIBUTING.md](CONTRIBUTING.md).

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

From `apps/mobile/android`, run `gradlew.bat testDebugUnitTest assembleDebug` on Windows, or `./gradlew testDebugUnitTest assembleDebug` elsewhere, with JDK 21 and SDK 35 configured. Install `app/build/outputs/apk/debug/app-debug.apk` on the phone, enter provider keys in Settings → Connections, and complete Android setup to enable the keyboard and permissions. USB is only needed for installation and debugging.

Keep the root `package-lock.json` as the single npm lockfile. Local environment files, signing keys, generated builds, and diagnostic screenshots are excluded from Git. Device testing details and known validation limits are recorded in [Android development](docs/android-development.md) and [S24 Ultra checks](docs/s24-ultra-testing.md).

## Product limits

The single `app.odicto.mobile` build inserts text through the Odicto keyboard only. It declares no accessibility service, so Google Play Protect does not block installing it from GitHub. The floating microphone therefore dictates while Odicto is the active keyboard (and says why when it is not). Password, phone, numeric/payment-like, and protected fields block voice actions. Insertion revalidates the original editor; changed focus rejects delivery and keeps explicit Copy recovery, with device-only history when persistence succeeds. Audio is request-scoped and unretained. Overlay permission is optional, and the keyboard microphone is the fallback. OEM overlay behavior requires physical-device verification.

Older `app.odicto.mobile.legacy` installations have separate local data. Installing the unified APK does not migrate their history, settings, clipboard items, or Keystore keys. Do not uninstall or clear an old installation without explicit data-loss approval. Generated flavor APKs left in build directories are obsolete outputs, not supported alternatives.

iOS, accounts, billing, and managed production services are deferred. Tablet viewport checks do not establish physical-tablet microphone or IME reliability.

See `docs/architecture.md`, `docs/android-development.md`, `docs/environment.md`, and `docs/privacy-and-security.md`.

## License

[Apache License 2.0](LICENSE).
