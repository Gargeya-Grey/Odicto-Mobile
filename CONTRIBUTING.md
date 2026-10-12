# Contributing to Odicto

Thank you for testing and improving Odicto.

## Report a bug or send feedback

Use [GitHub Issues](https://github.com/Gargeya-Grey/Odicto-Mobile/issues/new/choose). Include your Odicto version, phone model and Android version. Never paste API keys or private text.

## Change the code

1. Read [AGENTS.md](AGENTS.md) and [docs/architecture.md](docs/architecture.md). They state the runtime boundaries and the security rules that every change must keep.
2. Set up the tools listed in the [README](README.md#development): Node.js 22+, JDK 21, Android SDK 35.
3. Before you open a pull request, run:

   ```bash
   npm run verify
   ```

   For Android changes, also run from `apps/mobile/android`:

   ```bash
   ./gradlew :app:testDebugUnitTest :app:assembleDebug
   ```

4. Say in the pull request which phone you tested on. Automated checks do not prove keyboard, overlay or insertion behaviour on a real device.

By contributing, you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).

## Releases (maintainer)

1. One time only: run `tools/release/create-signing-key.ps1`. It creates the release key outside the repository and stores it as GitHub Actions secrets. Back up the folder it prints. Every release must use the same key, or installed copies cannot update.
2. Increase `versionCode` and `versionName` (MAJOR.MINOR.PATCH) in `apps/mobile/android/app/build.gradle` and merge to `main`.
3. Run **Actions → Release APK → Run workflow** on `main`. It tests, builds and signs the APK, then publishes a GitHub Release tagged `v<versionName>` with the APK and its SHA-256 file. Install notes come from `tools/release/release-notes.md`.
