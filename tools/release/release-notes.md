## What's changed in 1.8.0

- Choose a spoken language for Raw and AI transcription, or leave it on Automatic when switching languages.
- Add vocabulary hints for names and specialist terms. A comma or Enter creates a removable tag; pasted lists and multiword phrases work too. Save changes to keep the hints. Hints guide Groq transcription and do not guarantee exact wording.
- Live transcription can use Gemini or OpenAI. Save an optional OpenAI key under Connections, then choose OpenAI and its model under Live. Each provider remembers its own model. OpenAI offers GPT Live Transcribe and GPT Realtime Whisper; access and quota depend on your OpenAI project.
- Gemini Live now has a searchable model dropdown with readable names, so you can select a transcription model without typing its ID.
- Existing keys, settings, and history are preserved when updating. Gemini remains the default Live provider; spoken language defaults to Automatic and vocabulary hints start empty.

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

The owner confirmed physical smoke tests on the exact signed 1.8.0 APK installed on a Samsung Galaxy S24 Ultra: Raw dictation inserted the phrase once correctly; Gemini Live showed words while speaking and finished without duplication; OpenAI Live passed the same test. Automated protocol, audio-conversion, credential, and settings checks also passed. These are focused smoke tests on one device, not exhaustive coverage of every model or host app.
