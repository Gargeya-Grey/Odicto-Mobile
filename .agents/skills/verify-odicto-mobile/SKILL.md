---
name: verify-odicto-mobile
description: Verify Odicto Mobile Android settings, text polish, keyboard, and voice on the actual app and an isolated test device.
---

# Verify Odicto Mobile

This guide describes checks, not evidence of a pass. Follow the relevant steps and report their actual outcomes, including unavailable hardware or credentials. Read [features/README.md](features/README.md) and the relevant feature before driving the app.

## Build and launch

Use JDK 21, Android SDK 35, and an unlocked, dedicated test emulator or authorized device. Obtain exclusive control before interacting. A debug build uses package `app.odicto.mobile.debug`; preserve any existing installation and its data. Do not clear, uninstall, overwrite, change the current keyboard, or enter credentials on a personal device without authorization. Do not use personal text in screenshots or provider requests.

From the repository root in PowerShell, require each command to succeed before continuing. The two workspace builds provide the package entry points needed by the API tests on a clean checkout.

```powershell
npm run build --workspace @odicto/contracts
npm run build --workspace @odicto/domain
npm run verify
npm run android:sync
Push-Location apps/mobile/android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
Pop-Location
adb devices -l
```

Select one authorized device serial. Inspect `adb -s $serial shell pm path app.odicto.mobile.debug` before installing. If the package already exists, stop unless replacing that installation is authorized. On an empty dedicated target, install `apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk` with `adb -s $serial install <apk-path>`, then launch it with `adb -s $serial shell am start -n app.odicto.mobile.debug/app.odicto.mobile.MainActivity`. Use the Home setup rows to enable and select the keyboard if the journey needs it. The app is ready when Home renders and the setup state is visible. Provider journeys also require the user to enter the appropriate key privately in Settings > Connections; never read or record its value.

## Doctor

Run `adb -s $serial get-state`, `adb -s $serial shell pm path app.odicto.mobile.debug`, and `adb -s $serial shell pidof app.odicto.mobile.debug`. Confirm the expected device, installed debug package, foreground screen, and candidate APK before interaction. Compare the installed APK and candidate hashes when verifying a particular build. If the device is locked, unavailable, or owned by another session, stop and report the unmet precondition.

## Drive and evidence

Drive the visible app and keyboard using the feature map. Use accessible names or inspect the current test-device screen before taps; do not reuse coordinates across layouts. `adb -s $serial shell uiautomator dump /sdcard/window.xml` and `adb -s $serial exec-out cat /sdcard/window.xml` can inspect a dedicated synthetic test screen. Capture images only after ensuring no keys or personal content are visible. Never claim a host edit or live provider response from unit tests or a successful API return alone.

Record revision, APK hash, device, entry point, action, observed result, and skipped checks under `work/gc-artifacts/<task-id>/`. Register actual files and record a real handoff using `C:/Users/Gargeya/Documents/Codex/harness-gc/AGENT-WORKFLOW.md`. Separate native unit, emulator, physical-device, and live-provider results.

## Cleanup

Stop only processes and emulator instances started by the verification run. Keep proof artifacts and user data. Restore any authorized device setting changed for the run. Do not uninstall an existing package or clear its data. The keyboard trial fixture has its own usage and preservation rules in `tools/keyboard-interaction-host/README.md`.
