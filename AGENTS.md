# Odicto agent guide

## Workflow

1. Read `docs/architecture.md` before changing boundaries between React, Kotlin, or the API.
2. Preserve the single npm lockfile and workspace package boundaries.
3. Run the narrow package checks while editing, then `npm run verify` before completion.
4. For Android work, run `gradlew.bat testDebugUnitTest assembleDebug` from `apps/mobile/android` with JDK 21 and SDK 35. Record physical-device checks separately; an emulator is not proof of overlay/IME reliability.

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
- Every insertion revalidates the active editor session and `EditorPolicy`. Focus loss saves to local history/clipboard instead of inserting elsewhere.
- Voice controls remain hidden in password, phone, numeric/payment-like, no-personalized-learning, and unsupported fields.
- Use the keyboard microphone when overlay behavior is unavailable. AccessibilityService is outside the product architecture.

## Branch references

- Android permissions, IME, overlay, foreground recording, or device tests: read `docs/android-development.md` and `docs/research/mobile-platform-constraints.md`.
- API configuration, deployment, providers, database, or quotas: read `docs/environment.md`.
- Data handling, BYOK, logging, disclosures, or billing: read `docs/privacy-and-security.md`.
