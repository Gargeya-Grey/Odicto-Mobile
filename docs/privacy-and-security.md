# Privacy and security

- Android voice uses the user's own Groq, Gemini, and OpenRouter keys directly; keys are not embedded in builds. Any managed backend keys remain in backend secret storage and are not copied to the phone.
- Android installation refresh credentials and BYOK values use Keystore-backed encrypted preferences.
- Android sends keys only to their matching official provider over HTTPS/WSS. Redirects are disabled. Capacitor bridge logging is disabled so key-entry arguments are not logged.
- Android audio is buffered only in request memory and released on finish/cancel/error. No audio files are written by the direct voice transport.
- Transcript history remains in the device Room database. Server conversation history does not exist.
- In AI mode only, explicitly selected target-app text is captured at recording start and sent to the selected Gemini/OpenRouter answer provider alongside the spoken instruction. No surrounding document text is requested or sent; selection context is not sent to Groq, logged, or stored in history as a separate field. The snapshot is request-scoped. Raw and Live do not capture target-app context.
- The optional AI system prompt is stored locally in DataStore and sent only with AI answer requests. Blank restores the default. Selection and prompt limits are 20,000 and 8,000 UTF-16 code units respectively. History still stores the resulting output and spoken transcript, which can naturally contain excerpts from selected text.
- Logs exclude bodies, audio, transcripts, prompts, context, access/refresh tokens, and credentials. Operational fields are request ID, status, latency, provider/model, retry count, and metered units.

Android blocks voice controls in password, phone, numeric/payment-like, and no-personalized-learning fields. Because Android does not reliably identify payment semantics through `EditorInfo`, all numeric input classes are conservatively blocked.

Play disclosures must cover microphone foreground service, optional overlay, network processing by selected AI providers, device-only history, anonymous quota identity, account/billing processing, and optional BYOK. AccessibilityService is not requested.

On iOS, Odicto will not claim a floating microphone or microphone access from a custom keyboard. The planned experience is an in-app recorder plus an AI keyboard that transforms text entered through Apple dictation.
