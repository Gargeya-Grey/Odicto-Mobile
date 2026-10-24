## What's changed in 1.7.0

- Polish now confirms replacement when an editor returns its previous text briefly after accepting the change, including Samsung Notes. Unconfirmed delivery still offers explicit Copy recovery without automatically writing again.
- Completed Polish results use a compact, themed card with an explanation and Copy/Dismiss controls. There is no visible countdown or forced keyboard expansion.
- Toolbar Undo requests the focused app's text-edit history, including typing, backspace, selected-text deletion, paste and Polish. Availability and grouping depend on the app.
- Searchable AI and Polish model lists stay open during outside taps and page scrolling. Select a model or use the expand/collapse arrow to close them.
- Odicto Debug installs alongside the release app and keeps its own settings and keys.

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
