# Odicto agent guide

## Workflow

1. Read `docs/architecture.md` before changing boundaries between React, Kotlin, or the API.
2. Preserve the single npm lockfile and workspace package boundaries.
3. Run the narrow package checks while editing, then `npm run verify` before completion.
4. For Android work, run `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` from `apps/mobile/android` with JDK 21 and SDK 35. Record physical-device checks separately; automated checks are not proof of host-app insertion or overlay/IME reliability.

## Runtime boundaries

- Kotlin owns Android IME focus, secure-field policy, overlay, microphone foreground service, Room, DataStore, Keystore, and cursor insertion.
- React owns onboarding, settings, account, usage, and history presentation.
- Fastify owns provider credentials, orchestration, quotas, billing entitlement, and request logs.
- `packages/contracts` is the API schema source; `packages/domain` owns provider-neutral filters/state rules.

## Security invariants

- Mobile bundles contain only the public API base URL.
- Managed keys stay in backend secrets. BYOK values use Android Keystore and are transmitted only for the selected request.
- Audio is request-scoped and unretained. History remains device-only.
- Logs contain request IDs, latency, status, provider/model, and metered units; they exclude audio, transcripts, prompts, context, tokens, and credentials.
- Every insertion revalidates the operation, active editor session/target, selection, and `EditorPolicy`. API acceptance is not confirmed insertion; report delivery separately from local history persistence, keep explicit Copy recovery, and never auto-retry ambiguous delivery.
- Voice input is refused in password, phone, numeric/payment-like, no-personalized-learning, and unsupported fields. Emoji and clipboard follow the same rule. Ordinary typing stays allowed in numeric and phone fields. The floating control stays on screen in protected fields but disabled rather than hidden.
- `DictationCoordinator` routes insertion to the Odicto IME while it is the active keyboard. The build is a single version that ships `OdictoAccessibilityService` for insertion while another keyboard is active; that service only tracks the focused field and inserts text: never screen reading, navigation, gestures, or content collection. Ask `BuildCapability` before touching either service.
- Clipboard capture is automatically local only while Odicto is the default IME in an eligible field and Android permits access; honor the disable switch and skip marked-sensitive clips. Keep 50 exact-text unpinned entries plus persistent pins in the dedicated DataStore. Read `docs/privacy-and-security.md` before changing capture, retention, or editor guard reads.
- Cursor gestures stay disabled during voice/AI selection work. Emoji and clipboard panels replace the key grid. Preserve strict key bounds and synchronous configured key haptics; keyboard-only layout changes leave the floating overlay's appearance and gestures unchanged.
- Use the keyboard microphone when overlay behavior is unavailable.

## Branch references

- Android permissions, IME, overlay, foreground recording, or device tests: read `docs/android-development.md` and `docs/research/mobile-platform-constraints.md`.
- API configuration, deployment, providers, database, or quotas: read `docs/environment.md`.
- Data handling, BYOK, logging, disclosures, or billing: read `docs/privacy-and-security.md`.
