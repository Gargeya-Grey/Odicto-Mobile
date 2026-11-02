## What's changed in 1.9.0

- Number, PIN, and phone fields open directly to a large numeric keypad. Decimal, signed, and phone symbols follow the field's requirements.
- Vocabulary hints keep the keyboard open during editing and tag creation. The settings screen no longer offers its fields to Android autofill services, preventing password-manager popups from interrupting typing.
- Held Backspace recovers when a typed character and its deletion leave the cursor unchanged. Character deletion in Odicto's own fields also continues past the word-acceleration threshold.
- The floating microphone's lock target responds visibly while listening.
- Existing release keys, settings, and history are preserved by an in-place update. Debug remains a separate app.

## Install

1. On your Android phone (Android 6.0 or newer), download the `.apk` file below.
2. Open it. If Android asks, allow your browser or file manager to install unknown apps.
3. Open Odicto and follow the setup: enable the Odicto keyboard and grant the microphone permission.
4. Add your own [Groq API key](https://console.groq.com/keys) in **Settings → Connections**. Gemini and OpenRouter keys are optional for AI answers and Text polish. Live uses a Gemini or OpenAI key, depending on the provider you select.

Existing release users can install this APK as an update and keep their settings, keys and history. Current debug builds install separately as **Odicto Debug**. Older self-built debug versions may have used the release package name; Android refuses an update signed with a different key. Do not uninstall an existing app without backing up anything you need, since uninstalling deletes its saved data.

Keys stay on your phone in Android Keystore. Voice requests go straight from the phone to the provider you pick. Odicto has no server and collects nothing. The one request it makes on its own is asking GitHub, at most once an hour when you open the app, whether a newer release exists, so it can show an update card; it sends nothing about you, and you can turn it off in **Settings → Updates**.

Optional: check the download against the `.sha256` file (`certutil -hashfile Odicto-*.apk SHA256` on Windows, `sha256sum` elsewhere).

## Feedback

Found a bug or have an idea? [Open an issue](https://github.com/Gargeya-Grey/Odicto-Mobile/issues/new/choose). Include your phone model and Android version.

This is a beta. Physical-device testing so far covers one Samsung Galaxy S24 Ultra.
