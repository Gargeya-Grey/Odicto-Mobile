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
- `DictationCoordinator` routes insertion to the Odicto IME while it is the active keyboard. The build is a single version and ships no accessibility service: Google Play Protect blocks an APK installed from a browser or file manager when it declares that permission, so the Odicto IME is the only insertion channel. Do not add `BIND_ACCESSIBILITY_SERVICE`, SMS or notification-listener permissions. The floating microphone dictates only while Odicto is the active keyboard.
- Clipboard capture is off on a new install until the user turns it on in the setup row or the clipboard panel (the Paste suggestion needs it). When on, it is automatically local only while Odicto is the default IME in an eligible field and Android permits access; honor the disable switch and skip marked-sensitive clips. Keep 50 exact-text unpinned entries plus persistent pins in the dedicated DataStore. Read `docs/privacy-and-security.md` before changing capture, retention, or editor guard reads.
- Cursor gestures stay disabled during voice/AI selection work. Emoji and clipboard panels replace the key grid. Preserve strict key bounds and synchronous configured key haptics; keyboard-only layout changes leave the floating overlay's appearance and gestures unchanged.
- The update check is the only request the app makes on its own: main app open, at most once an hour, only while the user's switch is on, to GitHub's public release list. It sends nothing identifying, reads only version numbers, never downloads or installs, and builds the release-page URL from the validated version, never from the response. Read `docs/android-development.md` ("Update check") before changing it.
- Light and dark are one setting (`AppearanceStore`, default follows the phone) for the app, the keyboard and the floating microphone. Native colours come only from `values/odicto_colors.xml` and `values-night/odicto_colors.xml` (identical names) through a context built by `OdictoTheme.themed`; do not add colour literals to Kotlin or XML, and add every new colour to both files. A theme change rebuilds views, so views read their colours once at construction. Read `docs/architecture.md` before changing it.
- Use the keyboard microphone when overlay behavior is unavailable.

## Branch references

- Android permissions, IME, overlay, foreground recording, or device tests: read `docs/android-development.md` and `docs/research/mobile-platform-constraints.md`.
- API configuration, deployment, providers, database, or quotas: read `docs/environment.md`.
- Data handling, BYOK, logging, disclosures, or billing: read `docs/privacy-and-security.md`.
- Releases, version bumps, or release signing: read `CONTRIBUTING.md#releases-maintainer`.
