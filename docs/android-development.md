# Android development

## Requirements

Install Android Studio, its JDK 21 runtime (required by Capacitor 7), Android SDK/platform 35, and platform tools. Open `apps/mobile/android` after running `npm run android:sync`.

## Activation test

1. Install the debug APK on a physical device.
2. Grant microphone and notification permission.
3. Enable Odicto under system keyboard settings, then select it as the current keyboard.
4. Optionally grant draw-over-other-apps permission.
5. Focus a normal text field. Test the keyboard microphone, then the overlay.
6. Confirm password, phone, number/payment-like, and protected fields show no voice control.
7. Change apps while processing and confirm text is not inserted into the new target.

Test Android 12, 14, and the current Play target on Pixel, Samsung, and Xiaomi hardware. Exercise overlay denial, offline requests, timeouts, keyboard switching, screen lock, process death, restart, and OEM battery restrictions.

## Direct provider connections

Android voice calls Groq, Gemini, and OpenRouter directly over the phone's internet connection. Enter keys in Voice settings; they are stored using Android Keystore. No `npm run dev`, USB forwarding, or hosted Odicto API is needed for dictation. USB is only used to install/debug the APK. See [S24 Ultra testing](s24-ultra-testing.md).

## Build

```powershell
npm run build --workspace @odicto/mobile
npm run android:sync
cd apps/mobile/android
.\gradlew.bat testDebugUnitTest assembleDebug
```

The keyboard microphone is the approved fallback if OEM restrictions make background overlay recording unreliable. Do not add AccessibilityService.
