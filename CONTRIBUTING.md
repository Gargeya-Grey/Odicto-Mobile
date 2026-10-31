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
3. Run **Actions → Release APK → Run workflow** on `main`. It tests, builds and signs the APK, then creates a draft GitHub Release tagged `v<versionName>` with the APK and its SHA-256 file. Install notes come from `tools/release/release-notes.md`.
4. Run the physical-device checklist in `docs/android-development.md` on that exact signed APK. After the checks pass and the owner approves, publish the draft release.

Release facts that no config file shows:

- The key lives only in the owner's `~/.odicto-release` (plus their backup) and in the 4 `ODICTO_*` Actions secrets. The script reuses that key on every run; a new key would force every tester to uninstall, and that deletes their data.
- Debug uses `app.odicto.mobile.debug` and the launcher name **Odicto Debug**, so it can be installed alongside the release app (`app.odicto.mobile`) without removing release keys, settings or history. Debug has its own settings and Keystore; enable its keyboard and configure its provider keys separately. Do not uninstall a release app to install debug. Older debug builds used the release package name; Android still refuses a signature mismatch, and uninstalling deletes local data, so get the owner's approval before any uninstall.
- The owner runs the key script, merges pull requests, and approves each release. These are owner-only: they write secrets or publish to everyone.
- Installed apps learn about a release by reading GitHub's release list for the highest tag of the exact form `vMAJOR.MINOR.PATCH` (see `docs/android-development.md`, "Update check"). Keep the workflow tagging `v<versionName>` with a plain three-part version; anything else is invisible to the update card, and a draft release is never offered. Releases marked Pre-release are offered on purpose.
- The workflow uses a Windows runner because `package-lock.json` pins a Windows-only devDependency, so `npm ci` fails on Linux.
- Run the physical-device checklist in `docs/android-development.md` on the release APK before sharing a release.
