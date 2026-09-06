# Architecture

## Runtime flow

Android IME/overlay -> AudioCaptureService -> direct external provider APIs -> session-checked InputConnection insertion.

- Raw: recorded PCM is sent as WAV directly to Groq Whisper after release.
- AI: Groq transcribes speech, then Gemini or OpenRouter receives the spoken instruction, any explicitly selected text, and the device-saved custom system prompt (or the default when blank).
- Live: PCM streams directly to Gemini Live; provisional words update one native composing range in the original editor. Finalization finishes composition without pasting a second copy. Cursor/content verification prevents subsequent writes after the target changes. Cancel/error retains already typed words.

Provider calls run in Kotlin. User API keys are stored in Android Keystore-backed encrypted preferences and used only with the selected provider. There is no local model, PC service, installation-token exchange, or Odicto gateway in the Android voice path. Modes are selected before recording.

React/Capacitor hosts settings and onboarding. Native Kotlin owns recording, provider transport, editor policy, and insertion. The retained Fastify workspace serves the separate web/backend implementation; Android voice does not call it.

## Earlier backend design (not used by Android voice)

The production target is a Node 22 Fastify container on Google Cloud Run, Supabase Pro Postgres, Upstash Redis, and RevenueCat. Cloud Run supplies managed autoscaling, spend caps, integrated observability, and a published runtime SLA. Supabase is the affordable managed Postgres/account layer; database access stays standard and portable because Supabase Pro itself has no contractual uptime SLA.

Postgres is authoritative for installation credentials, idempotent operations, quota, usage, and entitlements. Redis provides short-window installation/IP rate limits only. RevenueCat verifies Play purchases and sends idempotently processed entitlement events.

## Native safety model

`DictationCoordinator` assigns a monotonic editor-session ID. Recording, processing, and insertion carry that ID. `EditorPolicy` is checked when input starts and immediately before `commitText`. If focus changes, the coordinator rejects insertion and records a local fallback outcome.

The keyboard microphone and optional overlay call the same recording service/state machine. No AccessibilityService exists in the manifest.

AI captures a request-scoped selection snapshot at recording start, including the cursor when nothing is selected. Before committing, native code checks the original editor, selection range, selected text, and observed selection changes. Changed or unreadable selections fall back to local history/copy without moving the cursor or restoring a stale selection. Android 12+ uses `getSurroundingText(0, 0, 0)` for selection-only snapshots; older Android uses IME selection callbacks with `getSelectedText`. Unsupported or oversized snapshots fail closed. See [Android InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection). Raw and Live do not capture selection context.

## Deferred

Billing/accounts, production Redis integration, Play Integrity, RevenueCat webhooks, and iOS are gated after physical Android POC acceptance. iOS cannot provide an Android-style overlay or microphone-enabled custom keyboard.
