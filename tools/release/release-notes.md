## What's changed in 1.7.1

- Text polish can use Gemini or OpenRouter. Its provider is independent of AI answers and uses the matching key saved under Connections.
- AI answers and Text polish now offer searchable Gemini model names, with higher model versions first. Opening the menu loads Google's current catalog and keeps prior or bundled choices if the lookup fails; model access depends on your Google project.
- Existing Text polish settings stay on OpenRouter with their saved model until you change them.

## Install

1. On your Android phone (Android 6.0 or newer), download the `.apk` file below.
2. Open it. If Android asks, allow your browser or file manager to install unknown apps.
3. Open Odicto and follow the setup: enable the Odicto keyboard and grant the microphone permission.
4. Add your own [Groq API key](https://console.groq.com/keys) in **Settings → Connections**. Gemini and OpenRouter keys are optional (AI and Live modes, Text polish).

Existing release users can install this APK as an update and keep their settings, keys and history. Current debug builds install separately as **Odicto Debug**. Older self-built debug versions may have used the release package name; Android refuses an update signed with a different key. Do not uninstall an existing app without backing up anything you need, since uninstalling deletes its saved data.

Keys stay on your phone in Android Keystore. Voice requests go straight from the phone to the provider you pick. Odicto has no server and collects nothing. The one request it makes on its own is asking GitHub, at most once an hour when you open the app, whether a newer release exists, so it can show an update card; it sends nothing about you, and you can turn it off in **Settings → Updates**.

Optional: check the download against the `.sha256` file (`certutil -hashfile Odicto-*.apk SHA256` on Windows, `sha256sum` elsewhere).

## Feedback

Found a bug or have an idea? [Open an issue](https://github.com/Gargeya-Grey/Odicto-Mobile/issues/new/choose). Include your phone model and Android version.

This is a beta. Physical-device testing so far covers one Samsung Galaxy S24 Ultra.
