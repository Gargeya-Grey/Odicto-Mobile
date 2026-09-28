# S24 Ultra voice UI check

## September 26: keyboard responsiveness and explicit text polish

- The keyboard keeps its native touch-down commits, frame-batched editor writes, and direct haptics. Background sentence-correction requests were removed; the top-right Polish button is the only new path that can send typed field text. Unit tests cover complete-field capture without changing the selection, styled/partial/oversized refusals, protected-field no-read typing, stale focus/text/selection results, provider errors and truncation, cancelled space taps, joined emoji cursor movement, and backspace cursor echoes. `npm run verify` and both flavor test/assemble tasks passed after the final source changes.
- OpenRouter's public model catalog listed the exact default `poolside/laguna-xs-2.1` as text-in/text-out with a 32,768-token completion ceiling. Voice AI's separate `:free` default was not changed. The store APK still omits overlay/accessibility; the legacy APK retains them.
- On the S24 Ultra (Android 16, API 36), the latest installed `app.odicto.mobile.legacy` debug APK matched the local SHA-256 (`682783E12C192F644D6C084C0269A407EFF0C67705A1573CFAC7CD1A10DDEC1F`). Settings visibly showed the new Text polish section and exact model ID. On Android Settings search, a one-character Polish tap logged only `pending` then `missing_key`, left the field unchanged, and made no provider request. The app remained alive. The legacy install had no OpenRouter key saved at that check.
- After the key was entered directly in the app (the key value was not read or logged), one paid request on a harmless synthetic Settings-search sentence logged `pending` then `applied`. The result replaced the field once: it changed from the original, started with capital `I`, retained the unusual proper noun `QwErty` exactly once, and had 29 UTF-16 units rather than the 27-unit input. The app stayed alive and the test query was cleared. This proves one real correction on this editor/model/key, not that all names or editors will behave identically.
- A 32-character tap sample in Settings search measured IME-received touch to haptic **invocation** at p50 0.20 ms / p95 0.68 ms, touch to `commitText` return at p50 1.04 ms / p95 3.58 ms, and touch to frame batch close at p50 4.59 ms / p95 8.50 ms. These are content-free debug timings, **not** motor-onset or visible-redraw timings. The prior installed APK had no timing probe, so there is no comparable pre-change p50/p95 and no measured speedup claim.
- Physical layout checks showed a full 304dp voice strip plus Polish button in docked mode, and both controls visible without overlap in right-handed, left-handed, and floating modes. Docked mode was restored. A spacebar drag left a four-character Settings query at length four; a tap increased it to five. The test query was cleared afterward. These checks do not prove behavior in other editors or on other devices.
- Still pending on physical hardware: a protected-field trial, a provider response racing a focus change, actual vibrator onset/visible text timing, and other editors/devices. Unit/fake-editor tests cover the refusal paths but do not replace those checks.

## September 26: interrupted floating-mic recovery

- Device evidence before changes: the installed `app.odicto.mobile` package had no `VoiceOverlayService`, although overlay permission had been granted. The app also crashed at launch in `VoicePreferences.initialize` when `voice_pinned_emoji` was stored as a `Set<String>` but read as a `String` (`ClassCastException` at the pinned-list read).
- `VoicePreferences.readList` now accepts either the legacy string-set or delimited-string representation for emoji and clipboard lists. The regression test fails with the original typed-string cast and passes with the tolerant read.
- Overlay ownership is flavor-specific: the store APK omits overlay permissions/service and reports overlay unsupported; the legacy APK includes the floating service and required permissions. The in-app switch opens overlay-permission settings before enabling when permission is missing.
- Physical S24 Ultra validation: updated the existing store APK in place and confirmed its process stayed alive with existing data; installed legacy alongside it, granted overlay permission, enabled the floating mic, and verified `VoiceOverlayService` is foreground plus a type-2038 `APPLICATION_OVERLAY` window remains present over Android Settings. Installed APK SHA-256 matched the locally built APK for both variants.
- Not yet verified: recording/transcription/insertion from the newly installed legacy package. Its app data is separate from the store package and does not inherit its saved provider credentials.

## September 25: keyboard-first store flavor

### Automated

- `npm run verify` passed (one pre-existing `react-hooks/exhaustive-deps` warning about `settings.model` in `VoiceHome.tsx`, not introduced here).
- `gradlew.bat :app:testStoreDebugUnitTest :app:assembleStoreDebug :app:testLegacyDebugUnitTest :app:assembleLegacyDebug :app:assembleStoreDebugAndroidTest` passed.
- Store APK audit with `aapt2`: package `app.odicto.mobile` versionCode 2 / 1.1; permissions are INTERNET, RECORD_AUDIO, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE only. No `SYSTEM_ALERT_WINDOW`, no `FOREGROUND_SERVICE_SPECIAL_USE`, no accessibility service in the manifest tree. Services are the IME, the capture service, and Room's own. The legacy APK additionally declares the overlay, pause tile, and accessibility service under `app.odicto.mobile.legacy`.
- New unit coverage: `SpaceGestureTest` (tap vs drag, axis lock, no double-reporting, cancel), `CursorNavigatorTest` (grapheme-safe movement, word boundaries, clamping, selection extension), `EmojiCatalogTest` (self-validating data), `EditorPolicyManualTest` (typing allowed in numeric/phone; emoji, clipboard, and voice refused in password and no-personalized-learning), `BuildCapabilityTest` (flavor-aware).
- Bugs these tests caught during implementation: a duplicate and mis-encoded emoji entry, a first-gesture-step double count, character stepping that used word boundaries and could split a surrogate pair, a backward word step that could stall, and password input types built without `TYPE_CLASS_TEXT`.

### Installed on the S24 Ultra (verified)

Device: SM-S928B, Android 16 (API 36), user 0.

- Pre-install state captured first: `enabled_accessibility_services` was already `null` and Samsung Keyboard was the default IME, so no accessibility state was at risk.
- The installed 1.0 APK and the new store APK share the same signing certificate (`SHA-256 660d4495…5a357`), so `adb install -r` upgraded in place. Saved API keys and local history were preserved; `versionCode=2`, `versionName=1.1`.
- Device-side `dumpsys package app.odicto.mobile` lists only `OdictoImeService`. No `OdictoAccessibilityService`, no `VoiceOverlayService`, and no `SYSTEM_ALERT_WINDOW` or `BIND_ACCESSIBILITY_SERVICE`.
- `pm list packages` shows `app.odicto.mobile` and `app.odicto.mobile.legacy` installed side by side.
- The running app showed the keyboard-first setup card ("That is all Odicto needs: it types, and its microphone dictates directly into the field you are using") with no overlay or accessibility row, confirming `BuildCapability` gates the React surface correctly.

### Removed after seeing it on the phone

A "Moving to the Play Store" migration card was built and then removed. Play distribution is not current, and the card was wrong twice over: it appeared for a plain in-place upgrade, and it claimed the old install was signed differently and that keys would be lost, which is false while both builds share the debug key. The `migrationNoticeDismissed` preference, its plugin plumbing, its TypeScript field, and its CSS were all removed rather than left dormant. The migration UX should be designed when a Play release actually exists.

### Still unverified on the phone

1. Select Odicto as the current keyboard and type a sentence.
2. Spacebar: tap inserts a space, horizontal drag moves the cursor, vertical drag jumps to the edges, a cancelled drag inserts nothing.
3. Emoji and clipboard panels insert; grep `logcat` for a pasted marker and confirm it never appears.
4. Password and numeric fields: typing works, while microphone, emoji, and clipboard are refused with a visible reason.
5. Raw, AI, and Live into the store keyboard, including the AI whole-field selection path.
6. Open the banking app that previously warned about an unknown accessibility app, and record the result. This is the check that decides whether the original problem is actually solved; it depends on that app's own policy and is not yet evidence.
7. The legacy build still passes its overlay, pause tile, and accessibility insertion regression checks.

### Panel layout, haptics, and content

Two reported "broken" features were verified working on the phone before any change: the emoji panel built 40+ emoji and the clipboard showed a real copied link. Both appeared broken because `ime_keyboard.xml` stacked the key grid, the function row, and a 190dp panel in one column, pushing the panel below the visible region of the IME window.

Fixed so a panel replaces the key grid (`keyboard.visibility = GONE`) and the function row stays visible as the toggle.

Also in this pass:

- Key haptics were absent entirely: `OdictoKeyboardView` imported `HapticFeedbackConstants` but never called it. Added a tap on every key and on the spacebar.
- Key press feedback: 0.94 scale on touch-down, 90ms settle on release, alongside the existing colour change.
- `hapticLevel` (0-3) replaces the boolean, mapped to `CLOCK_TICK` / `KEYBOARD_TAP` / `LONG_PRESS`, with a four-stop segmented control in Settings. A store written before levels existed still resolves through the old boolean, so a user who had haptics off does not get them back.
- Symbols page now leads with `₹ € £ ¥ ¢`, bullets `• ▪ ‣ ⁃ ◦`, arrows `→ ← ↑ ↓ ↔ ↕ ▶ ◀ ▲ ▼`, and `𝕏`.
- Persistent favourites row at the top of the emoji panel, five slots, long-press to pin or unpin.
- Key size S/M/L (42/48/56dp) and System or Lora. Lora is bundled from `google/fonts` under the SIL Open Font License 1.1 with `OFL.txt` shipped alongside; the bundled file is the variable-weight release, and an unreadable font degrades to a serif rather than crashing.
- Refusal messages now name the field ("Clipboard is off in number fields. You can still type.") instead of a bare "not available", which read like a missing feature.

Encoding was verified by code point: all 26 added symbols are present and no mojibake lead bytes (U+00E2/U+00C2/U+00C3) appear in the source. The earlier `âŒ«` / `â†µ` came from arrow characters written as literal glyphs; those keys are now vector drawables.

### Not verified: the phone locked mid-session

The final device check could not run. The screen went to the lock screen (`mDreamingLockscreen=true`, focus on `Bouncer`) and `wm dismiss-keyguard` cannot clear a PIN-protected screen. Every automated tap after that point was a no-op, so the screenshots taken then show the charging screen rather than the keyboard.

Still to confirm by hand once the phone is unlocked:

1. Emoji panel opens fully visible with the PINNED row and category headers, no scrolling needed to see it.
2. A key press visibly depresses and the device vibrates.
3. Key size S/M/L and the Lora font visibly change the grid.
4. Long-pressing an emoji pins it, and the row survives closing and reopening the panel.
5. Clipboard still shows entries in a plain text field.

## September 6: keyboard, preview, and tablet layout

### Compact status and efficiency follow-up

- Heading refinement: all five settings headings share an 18px title-to-description gap. The accent is a sharp rectangle across the full title, extending 10% past the title edge. Its upper half uses 20% opacity; its lower half retains 50% opacity and sits below the title's line box. This replaces the earlier detached 40×4px underline.

- With Show text preview off, ordinary status text shares the control row (72dp total height, previously 144dp). Longer errors retain readable space. Copy, cancel, lock, and microphone actions remain available.
- Hidden previews receive no transcript writes or transcript-string assembly. Meter-only changes do not rewrite unchanged visible transcripts. Hidden keyboard controls skip rendering; overlay audio-level updates no longer repeat permission and keyguard queries.
- Settings anchors use native smooth scrolling and a 10dvh top offset, with a 40×4px accent underline on section headings. Reduced-motion mode uses immediate scrolling. On the S24 WebView, tested anchors settled at 89px in an 889px viewport, with multiple intermediate scroll positions; reduced-motion behavior was `auto`.
- The synthetic `VoiceControlViewDeviceTest` passed on the S24: 1,000 changing-transcript updates with preview off caused zero preview text writes and zero repeated resizes; 1,000 meter-only updates with preview on caused zero transcript writes and zero resizes. It also checked 72dp connecting/processing/done layouts, preview re-enabling, and readable error space. No microphone or provider calls were used.
- Device-test incident: Gradle's default connected-test cleanup uninstalled the app after the passing test, removing saved credentials, settings, and local history. The app was reinstalled, known UI preferences and previously granted permissions were restored, and the user was informed. API keys and removed history could not be recovered. `gradle.properties` now sets `android.injected.androidTest.leaveApksInstalledAfterRun=true` and disables incompatible-APK uninstallation to prevent that cleanup in future runs.
- Run only the app's focused instrumentation test: `gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=app.odicto.mobile.VoiceControlViewDeviceTest"`. The unscoped connected-test command also attempted the unrelated Capacitor library test APK, whose dependency conflict is outside this change.

### Earlier checks

- Editing keys and the keyboard switch now honor Touch feedback. Keyboard voice controls no longer suppress haptics just because the floating microphone is enabled; hidden views do not produce voice-state haptics.
- The floating control hides while the Odicto IME window is visible and returns when it closes, subject to the existing permission, screen-lock, and protected-field rules. This changes presentation only; it does not cancel the voice session.
- Settings now has Controls & appearance, API connections, AI answers, Live transcription, and Android setup navigation. Show text preview defaults on, persists in DataStore, and applies to the native keyboard and overlay. Turning it off shrinks the result/status panel while retaining status, errors, and copy controls. Live already omits the transcript preview during recording.
- Settings uses two columns at 840 CSS pixels and above. Native editing keys are centered within a maximum 640dp width. The IME does not enter fullscreen landscape mode; preview panels use less height in short windows, and overlay layout refreshes on rotation.
- Physical S24 Ultra checks: installed the debug APK; observed completed IME haptic events in the device vibrator diagnostics; verified no overlay window while the keyboard was visible and one overlay window after it closed; verified the preview switch persisted across an app restart; verified the landscape IME remained non-fullscreen. Temporary test fields and changed voice settings were restored afterward.
- Tablet layout checks used the installed Android WebView at 768×1024, 1024×768, 1280×800, and 800×1280, plus a 393×852 phone viewport. All had no horizontal overflow or clipped form controls. These are viewport checks, not physical-tablet microphone/IME reliability evidence.
- Validation: `npm run verify` passed; JDK 21 / SDK 35 `gradlew.bat testDebugUnitTest assembleDebug` passed (35 Android unit tests, including 12 direct transport tests).
- Gemini: existing transport tests pass, and the manual activity-start/activity-end setup matches the [current official Live transcription guide](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe). Earlier device crash entries show the previously fixed main-thread network cleanup failure. No new Gemini failure was reproduced and no transport rewrite was made. A current live-audio device test remains pending: automatic approval review rejected a simulated microphone press until the user explicitly approves transmitting that captured audio to Gemini.

## Install and connect

The Android voice path now calls official external APIs directly. No PC service, local model, USB forwarding, or Odicto gateway is required.

1. Install the intended flavor: `app-store-debug.apk` is keyboard-only; `app-legacy-debug.apk` includes the floating microphone and accessibility insertion. Keep the legacy package side by side with the store build.
2. Open Voice settings. Save your Groq key for Raw and AI speech. Save your Gemini key for Gemini AI and Live. For OpenRouter AI, save its key and select an explicit model ID. Keys stay in Android Keystore-backed storage.
3. Complete microphone, keyboard, and notification setup. For the legacy build only, grant display-over-other-apps permission and enable Floating microphone. Select Odicto as the current keyboard for direct IME insertion.
4. Use Wi-Fi or mobile data. Remove old forwarding with `adb reverse --remove tcp:8080` if present, or unplug USB, to demonstrate independence from the computer.

Raw uses Groq `whisper-large-v3-turbo`. Gemini AI defaults to `gemini-3.5-flash-lite`; Live defaults to `gemini-3.5-transcribe-live`. Both Gemini model IDs can be changed in settings. Model availability and quota depend on the provider account.

## Five-minute interaction check

- Keyboard polish follow-up: hold Backspace (400 ms delay, then 65 ms repeat); verify release, sliding outside the key, closing the keyboard, and changing fields stop deletion immediately. Editing keys have dark rounded backgrounds, spacing, and a wider Space key; Switch keyboard is a quieter text control.
- Successfully inserted results dismiss after two seconds. Verify a new recording is not cleared by an older timer. A result that could not be inserted stays on screen for twelve seconds so it can be copied, then releases the controls; errors stay until dismissed.

- Live now writes interim text into the focused editor using one composing range, without a transcript preview panel or a second paste. Stop finalizes that range. Cancel/error keeps words already typed. Moving the cursor or switching fields must stop further typing; verify this on the physical phone.
- Live completion regression: a finalized `inputTranscription` after release must complete even without `turnComplete`. If final text arrived before release, a three-second bounded fallback completes it; pending interim-only text is kept with an error rather than an indefinite spinner.
- AI requires no selection: place the cursor, select AI, and ask a question. Selection context, when present, is included as labeled source data in the system prompt. An editor that omits surrounding-text offsets falls back to its IME selection coordinates.
- Keyboard-only editing row: test Backspace on selected text and an emoji, Space, comma, full stop, and newline. Newline must never invoke Send/Post. Keys are disabled during voice requests and absent from the floating mode menu and protected fields.

- In Samsung Notes, hold the mic, wait for Listening and its haptic, say a sentence, then release. Repeat three times without changing focus.
- Select Raw: words should insert once after release through Groq. Select AI: say “Write a short birthday greeting”; Gemini/OpenRouter should insert the greeting, not the request. Select Live: provisional words should update during speech through Gemini and insert once on finish.
- Hold and slide onto Lock. Release your thumb, keep speaking, then tap the mic to finish. Repeat with Cancel; nothing should be inserted.
- Choose Raw/AI/Live before recording. Mode buttons are disabled during a request so audio cannot switch providers mid-recording.
- Double-tap quickly to expand settings/modes; neither tap should accidentally submit audio. Drag an idle bubble to either edge. Check portrait/landscape, larger system text, and gesture navigation.
- In Samsung's sound/vibration settings, enable touch vibration, then compare it disabled. With animation scales disabled in Developer options, verify that controls still work without relying on motion. Assess smoothness at both Standard and Adaptive motion smoothness.
- Start dictation and switch apps before completion. Text must not appear in the new field; retrieve it under Recent words. Test password, phone, numeric, and protected inputs: voice input is refused and the floating control remains visible but disabled.
- Check airplane mode, denied microphone/overlay permission, notification Cancel, screen lock, and changing keyboards. Errors must remain recoverable without an unexpected insertion.

## Limits and recorded validation

### Selection-aware AI and custom prompt (September 6)

- In Voice settings, save an AI system prompt, reopen settings, and confirm it persists. Blank and save to restore the default.
- In Notes with Odicto keyboard selected, select a paragraph, choose AI before holding the mic, and say “Make this more concise.” Only the selected paragraph should be replaced; surrounding text stays unchanged. Repeat with Gemini and OpenRouter.
- Without a selection, ask AI to draft at the cursor. Repeat with a custom prompt such as “Use British English and a formal tone.”
- During processing, move the selection, move it away and back, edit the selected text, or switch fields/apps. The response must be saved for copying without replacement. Cancel must leave the original untouched.
- Unsupported selections and selections over 20,000 characters must fail before recording. Token-truncated AI responses must not replace the original text. Raw/Live behavior is unchanged.
- Automated coverage checks selection safety, context cleanup, provider payload separation, prompt overrides/defaults, size limits, and truncation. Physical selection replacement and prompt persistence checks are pending on the S24.

- Each recording is capped below five minutes. Provider quotas and rate limits apply directly to the supplied keys; Android voice does not use gateway quota reservations.
- Groq determines Raw latency after release; AI adds the selected answer provider's latency. Gemini determines Live latency. Judge touch response/haptics separately.
- The bubble runs as a `specialUse` foreground service with a minimal notification while enabled, and the accessibility service restarts it when the system rebinds after updates, OEM cleanup, or reboot. The keyboard mic remains the fallback if the overlay permission is revoked or the service is force-stopped. An unavailable bubble outside a supported editor can open settings but cannot record.
- September 14 follow-up: Samsung's Frecess freezer suspended the Odicto process whenever another app was in the front (`FZ ... reason: Bg`), leaving the bubble visible but dead to touches, and after an APK update nothing restarted the overlay service at all (only the accessibility service is system-bound). The foreground-service conversion and accessibility resurrection address both. On the S24 final-build check, the accessibility service rebound after install, the overlay was one `specialUse` foreground service, and WhatsApp → Odicto/Home closed the stale target; X-specific and OEM freezer-duration checks remain pending.
- September 17 crash triage: `dumpsys dropbox --print data_app_crash` holds six `RemoteServiceException$ForegroundServiceDidNotStartInTimeException` records from September 14 (19:45:05–19:47:15), all naming `.overlay.VoiceOverlayService`; five restarted the process and died again within ~500 ms, a crash loop. A `startForegroundService()` launch must reach `startForeground()` inside the platform window, and the overlay only promoted from `onStartCommand` after a channel creation that could throw. Fixes: overlay promotion moved to `onCreate` and made idempotent with the channel creation guarded; the audio service promotes before its microphone-permission check; `VoiceSession.finish`/`cancel` wrap service commands so a rejected background start resolves the session instead of throwing on the main thread; `AudioCaptureService.finish` ends a stop-before-capture session instead of leaking a started service; `VoicePreferences` releases its ready gate on a failed read and the DataStore delegate installs a corruption handler; plugin methods guard a null activity; the overlay unregisters its receiver only when registered. Robolectric and androidx.test:core were added for service-level tests: 50 unit tests pass (`VoiceSessionCommandTest`, `AudioCaptureServiceTest`, `VoicePreferencesTest`, `AccessibilityEventPolicyTest` are new).
- September 17 device checks (S24 Ultra, Android 16, debug APK): app launch promotes the overlay (`isForeground=true`, id 18, `specialUse`); force-stop followed by re-enabling accessibility rebinds the service, resurrects the overlay from the background, and leaves the crash buffer empty. Activation cost dropped for heavy editors: selection-change events no longer run the cross-process focus scan while a session is open, and the input-method setting read is cached for 500 ms.
- September 17 open issue: `rootInActiveWindow.findFocus(FOCUS_INPUT)` returned null for every observed event in Chrome (window-state and selection changes alike), so no session opens there; Google Messages' composer behaved the same. Physical Raw/AI/Live dictation retests remain pending after this build.
- September 17 activation and focus follow-up: capture now starts with the request and frames buffer in memory (bounded at 160 frames, about sixteen seconds) until the provider handshake finishes, so the first words are no longer clipped and the control shows Listening immediately; a release before the handshake waits up to ten seconds for it and then flushes the backlog in order instead of discarding it. The accessibility service now resolves the edited field from the window that holds input focus (`flagRetrieveInteractiveWindows`, and only that window's root) rather than the active root alone, which is the case Chrome and other multi-window apps need. Debug builds log `capture started after Xms` and `transport ready after Xms, buffered=N frames` for latency checks. 57 unit tests pass (`AudioBacklogTest` is new).
- September 17 Chrome focus confirmed on the phone: focusing the address bar logs `OdictoA11y: evaluate node=true eligible=true focusKey=385:com.android.chrome:id/url_bar:Rect(168, 97 - 807, 244) session=2`. Before the focused-window fallback the same app logged `node=false` for every event and never opened a session. The Google app's search box resolves the same way (`googleapp_search_box`, `eligible=true`).
- September 17 record-while-connecting confirmed by a dictated session in the Google app: `OdictoCapture: capture started after 71ms` and `transport ready after 76ms, buffered=0 frames`. The microphone is live in 71 ms instead of waiting for the provider handshake, and no frames needed buffering because Raw/AI connect immediately; the backlog path is covered by `AudioBacklogTest` and matters for Live and slow networks. No crash entries.
- September 17 pause control: `VoiceSettings.paused` stops the overlay (`VoiceOverlayPolicy` and the service's `unusable()` check) and the accessibility service calls `disableSelf()` when it observes the pause, so both conditions protected apps check disappear in one tap. Verified on the S24: tapping the in-app "Pause Odicto" switch stopped the overlay service and cleared `enabled_accessibility_services`; tapping it again restarted the overlay and opened the accessibility screen, where Android requires the user to re-enable the service. A `PauseTileService` Quick Settings tile ships with the build and is registered in the shade (`sysui_qs_tiles` lists `custom(app.odicto.mobile/.overlay.PauseTileService)`). No crash entries during the checks; the paused policy has a unit test.
- September 17 Raw insertion failure diagnosed from debug logs: a 6.5 s Raw dictation returned `output=83 chars, inserted=false`. The focused field was `android.widget.EditText` with no resource id, and its key included its screen bounds; when the field re-laid out mid-dictation (the IME shifted it by ~24 px) the service read that as a new editor, moved the session from 15 to 16, and `deliver` refused the finished transcript, which was saved to history instead. Editor identity now comes from the Android 13+ node unique id (falling back to resource name or class) and never from geometry, so a re-layout cannot orphan an in-flight session. Two further fixes went with it: an un-inserted result no longer holds the copy and back controls on screen forever (it expires after twelve seconds; the transcript stays in history), and the error card is readable regardless of the text-preview setting. 62 unit tests pass (`EditorIdentityTest` is new, `PreviewExpiryTest` covers the saved-result timer).
- September 17 "the floating mic never activated" diagnosed: the foreground app was a financial app (`in.indwealth`) and every event logged `focus miss active=in.indwealth ... focused=in.indwealth` followed by `evaluate node=false`, meaning the app exposes no input-focused editor node at all. Other apps in the same log showed `node=true eligible=false` with `close protected=true`, which is the intended numeric/password refusal. Some apps (financial, canvas-rendered, or those that mark their editors as not important) cannot be read through accessibility, so neither the floating mic nor accessibility insertion can work there; the Odicto keyboard is the working channel because an IME receives the input connection directly. Diagnosability added for this class of report: every decision now logs a reason (`ok`, `numeric`, `phone`, `password`, `password-variation`, `not-editable`, `unsupported`, `no-focused-node`, `own-app`), debug builds report `editableInWindow=true/false` when focus resolution fails so "the app hides its editor" is distinguishable from "no editor here", and tapping the microphone where no session can open shows a self-clearing notice instead of doing nothing. A focused text node that reports itself as non-editable is now refused, so a label can no longer open a session that could never be inserted into. 65 unit tests pass (`EditorPolicyTest` gained reason and consistency tests, `PreviewExpiryTest` covers the notice).
- September 17 device note: the phone's system process crashed at 03:00 (`PowerManagerService` NPE reported as a Device Care "silent reset", followed by `DeadSystemException` in Device Care). That is a Samsung system-level event, not an Odicto crash; it is unrelated to the overlay service records above.
- September 5 follow-up: the S24 initially had 15 leaked overlays. After cleanup fixes, one overlay was observed across subsequent recordings and dictated paragraphs appeared in Samsung Notes through the earlier gateway build. The direct-provider build has been installed with USB API forwarding removed. Direct Raw/AI/Live device results are pending key entry and retest; previous gateway results do not prove direct transport.

## Rebuild

### Latest UI / Live follow-up

- September 6 crash follow-up: device crash traces at 00:05:35 and 00:05:47 show `NetworkOnMainThreadException` in `DirectVoiceTransport.cancel` while evicting HTTPS connections after insertion. Network teardown now runs on the request-owned worker executor; cancellation immediately suppresses callbacks and is idempotent. The cleanup-thread regression failed before the fix and passed afterward (16 Android tests pass). Updated APK installed; repeated post-insertion device checks remain pending. UI unchanged.

- Android now accepts Gemini Live JSON in both text and binary WebSocket frames. A regression test first reproduced the missing ready event, then passed after the handler fix.
- Recording/results use a 184dp panel instead of 300dp; cancel, lock, and mic share a nearby row. The keyboard surface follows the actual control height. Double-tap the idle bubble for modes before recording.
- Overlay recovery retries are bounded after window failures or an unlock/keyguard timing race. The device had an enabled service with no overlay window; after reinstall/reopen one visible bubble returned. The original intermittent disappearance is not yet conclusively reproduced or resolved.
- Workspace verification and Android unit tests/build passed; updated APK installed on the S24. Direct Live speech and insertion still require a physical-device retest. Samsung Keyboard was selected at inspection; select Odicto for editor insertion testing.

```powershell
npm run verify
npm run android:sync
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME='C:\Users\Gargeya\AppData\Local\Android\Sdk'
cd apps/mobile/android
.\gradlew.bat testDebugUnitTest assembleDebug
```

Use your own JDK 21 and SDK 35 locations if different.

## September 28 keyboard update

- Built with JDK 21 and SDK 35. `npm run verify` passed; Capacitor assets were synced before Android assembly. Store and legacy each passed 203 unit tests with zero failures/errors/skips, and both debug APKs assembled.
- Verified the actual store APK has the IME and excludes overlay permission/accessibility service. The installed legacy certificate matched the replacement. Updated only `app.odicto.mobile.legacy` with `adb install -r`; no uninstall, data clear, flavor switch, credential export, or saved-key access occurred. The data-directory inode and default IME remained unchanged. Individual saved settings/keys were not inspected.
- Final installed APK SHA-256 matches the local build: `45C238A58280E22865982CB15D730F214138D71164D8DC9F4ACB1648C4F6075D` (version `1.1-legacy`, code 2; use the hash to identify this revision).
- Physically observed compact portrait chrome, accurate ordinary letter taps, and a one-second uppercase hold producing `QweQ` without duplication in the Google search field. A larger cursor drag moved within the synthetic text. A small initial drag did not move; a regression then reproduced activation discarding coalesced travel. The final build consumes only the activation dead zone, and that regression now passes. Final fine-drag/reversal feel remains a physical retest requirement.
- Follow-up restored permanently visible Raw, AI, and Live controls instead of the selected-mode menu. Final keyboard-only screenshot on the S24 confirms all three and the new bespoke bottom-row vectors. The Polish busy drawable fills three stars lilac and softly staggers their opacity, with static filled stars when animations are disabled; its rendering/lifecycle tests pass. No live provider request was initiated to demonstrate the animation.
- No post or message was submitted. Touch automation stopped when the foreground changed to a conversation. X voice-result insertion, selected-text Polish against a real provider, final landscape split geometry, automatic clipboard capture across apps/restart, and floating-overlay interaction regression remain unverified on-device. Accepted Android insertion calls are deliberately reported as unverified, not as confirmed insertion; this is not a claim that X's intermittent insertion failure was reproduced or fixed.
- Next physical checks: use a disposable draft to test small spacebar motion/reversal and rotation; select part of a sentence and tap Polish; verify all three stars breathe lilac and only that selection changes; copy 51 synthetic entries and pin one before clearing; confirm full long-text paste and pin retention after reopening. Keep API credentials on-device and do not publish the draft.

### Held-backspace and status follow-up

- Captured the reported Samsung Notes screen and content-free cursor diagnostics before edits or installation. The status was a crowded right-aligned sentence; Android reported cursor zero with text remaining. This evidence alone does not establish how the original cursor moved. No note content was modified by automation.
- Four destructive-repeat cases were demonstrated failing before their fixes: unrelated collapsed cursor movement, a new selection, replacement InputConnection, and movement to an endpoint matching an older acknowledgment. The delayed-own-acknowledgment preservation test remains passing.
- Holds now remain bound to their original connection, recognize only recorded own deletion endpoints, and stop on other selection changes. Repeats in eligible fields verify the live cursor, and word deletion refreshes its bounded prefix. Unreadable cursor metadata stops repetition instead of guessing; individual taps remain available. Protected-field manual typing keeps its no-text-read behavior.
- In-keyboard completed status now separates a short left-aligned heading from recovery/persistence detail. The full caution remains available by tapping or through accessibility. Floating-overlay formatting is unchanged.
- Both flavor builds passed, with 209 unit tests per flavor and zero failures/errors; `npm run verify` passed. Installed legacy in place with matching local/device SHA-256 `48B4EBB9C523AEF2646BDD0CC3E11FFE30B22B7D8CF7995D2D578C11C9D94172`. No app-data reset or credential access.
- Retest held backspace on disposable Samsung Notes text, including moving the cursor during a hold and releasing/repressing afterward. The exact incident and post-update status rendering are not yet physically reverified; do not use the unit results as proof of the host-app interaction.

## Input-engine continuity trials

The preceding per-repeat-read fix was not sufficient: the next report specifically described **stalls or stops** and uneven rapid typing. The replacement separates observed/predicted editor state, bounds outstanding destructive work, retains a hold while waiting for evidence, uses absolute repeat deadlines, fixes secondary-pointer ownership, caches haptic preparation, and removes frame-spanning ordinary typing batches.

### Physical baseline and comparison

- Device: S24 Ultra, Android 16, existing `app.odicto.mobile.legacy` IME. Baseline APK hash: `48B4EBB9C523AEF2646BDD0CC3E11FFE30B22B7D8CF7995D2D578C11C9D94172`. Input-engine comparison APK hash: `81383FBC310E5DE1EF1742AB29E1CEA18F7F0D40E448644312EED73433B70D82`.
- Used the separately installed, permissionless `tools/keyboard-interaction-host` and its host-only framework runner. Generated text only; no personal notes, clipboard payloads, credentials, or provider calls. Both Odicto replacements used `adb install -r`, with no uninstall/data clear. The installed candidate hash matched its local APK.
- Three adaptive-mode rounds each exercised a four-second hold, a transient-read hold, and 40 overlapping two-thumb characters. One corresponding 60 Hz round also ran. Both display preference overrides were restored to their original unset state after each fixed-rate trial.
- In the transient profile the host returns unavailable context reads from 150 to 750 ms. The baseline consistently deleted one unit then stopped for the remaining hold, even after reads recovered. The candidate resumed without a new finger press. This reproduces a concrete failure mechanism, not proof of Samsung Notes' precise internal behavior.

| Controlled trial                          | Baseline                                                      | Input-engine candidate                                                               |
| ----------------------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| Normal hold, adaptive, three runs         | 96–98 mutations, 568–579 UTF-16 units removed, 191–195 reads  | 109 mutations, 650 units removed, 105 reads                                          |
| Transient-read hold, adaptive, three runs | 1 mutation; terminal no-progress gap 4,487–4,516 ms           | 94 mutations; terminal gap 524–528 ms, including the fixed 500 ms post-release drain |
| Normal hold, 60 Hz                        | 95 mutations, 189 reads                                       | 109 mutations, 105 reads                                                             |
| Transient-read hold, 60 Hz                | 1 mutation; no recovery                                       | 95 mutations; recovery within the same hold                                          |
| Two-thumb typing                          | Exact expected output and end selection in every recorded run | Exact expected output and end selection in every recorded run                        |

The retained text remained the expected original prefix after holds; this checks corruption/position, not an independently predetermined word-deletion count. Batches were balanced, with depth zero at completion. The candidate passed the declared continuity gate: more than ten changes and a last deletion within 400 ms before release, plus exact retained prefix and balanced batches. Old `status=completed` summaries were evaluated by this gate; the fixture now exports an explicit `passed` verdict too.

### Timing interpretation

A later A/B added per-key pre-injection timestamps correlated with TextWatcher and the next editor draw, retaining 40 samples per typing run. Three runs per build/refresh setting produced:

| Injected-key-to-editor-draw p95 | Baseline         | Candidate        |
| ------------------------------- | ---------------- | ---------------- |
| Adaptive, reported 120 Hz       | 27.132–27.652 ms | 27.230–27.627 ms |
| Fixed 60 Hz                     | 27.558–28.347 ms | 26.779–28.216 ms |

There is **no demonstrated material end-to-end typing speedup** in this fixture. The earlier change-to-draw-only metric improved at adaptive refresh but worsened at 60 Hz; it excludes upstream processing and must not be presented as complete keystroke latency. Input injection itself, frame scheduling, and host rendering remain part of these measured intervals; this is not hardware finger-to-photon timing.

Candidate debug traces separated haptic preparation (roughly 0.02 ms p95 in the sampled runs) from the vibration API call (roughly 1.9–2.2 ms p95). They do not measure motor onset or tactile smoothness. Key labels and field contents were absent from exported traces.

The measurable win is sustained held-delete recovery and reduced read work, with exact scripted typing and corrected pointer lifecycles. Replay, evidence, pointer, and feature suites reached 249 passing tests per flavor at the comparison build; additional manual-tap compatibility and clipboard UI changes are verified separately below. `npm run verify` and both flavor APK assemblies passed. Actual Notes/X interaction and subjective haptic feel still need explicit on-device confirmation; ambiguous editors without trustworthy evidence retain a bounded safety pause rather than unlimited speculative deletion.

## Clipboard refinement and final compatibility build

- Split repository-backed clipboard operations from `ClipboardPanelBinding`, enabling screenshots and geometry tests with synthetic state and no reads of saved clips. The debug-only `ClipboardPreviewActivity` is shell-gated by `android.permission.DUMP`; it has no release entry point and its actions mutate only generated in-memory state.
- Replaced the large default controls and always-expanded disclosure with a compact sticky header, local-only count, Auto-save row, explicit privacy disclosure, balanced pinned/recent rows, and clear confirmation. Full paste payloads and 50 recent entries plus persistent pins are unchanged. Fixed scroll anchoring after new clips arrive.
- Device review at 411dp and 280dp widths prompted a second refinement: visible pin glyphs are 16dp inside 48dp touch targets, inset another 8dp inside the row's trailing edge. Persistent pin boxes were removed, the switch track uses the lilac palette, and header/section spacing was tightened. The corrected synthetic screens were read on the S24; no saved clipboard text was exposed or changed.
- Added an explicit native-code-point backspace fallback for individual taps when context is unavailable. Unknown deletion callbacks locate the possible endpoint but do not grant repeat credit; fresh editor evidence remains required before automated continuation. Repeated manual taps, protected no-read behavior, and same-hold recovery have regression coverage.
- Final store/legacy suites each passed 259 tests with zero failures/errors. Both APK builds, `npm run verify`, and standalone fixture `assembleDebug lintDebug` passed. Legacy was updated in place; final local and installed SHA-256: `07C95619D6C2E8C30FD7E3E50C85411075EF1700DBB57220C71A3F65BA57F187`.
- Final physical smoke passed normal hold (109 changes, 105 reads), transient-read hold (94 changes, recovered without lift), and exact 40-character two-thumb input. This final smoke's injected-key-to-draw typing p95 was 29.587 ms; it does not establish a typing-speed improvement over the earlier comparison. No personal document was edited by the tests.
- Synthetic preview/test activities were closed or stopped. Saved settings, clipboard data, pins, and credentials were not reset; no saved key values were inspected. Display overrides were restored. Real Notes/X behavior and physical haptic onset remain separate manual verification requirements.

### Faster uppercase hold

- Reduced the letter hold threshold from 1,000ms to 500ms. The magnified pressed-key preview now changes to the capital at activation instead of disappearing; the highlighted key also shows the capital. Release still invokes the guarded replacement, and cancellation clears the preview.
- The regression covering the 499ms/500ms boundary and two capital draw calls (key plus preview) failed before the change and passed afterward; controller replacement and stale-selection checks also pass at 501ms. Both flavor suites/builds and `npm run verify` passed. No new physical gesture trial was performed for this small update.
- Installed legacy in place without a reset. Local/device SHA-256 match: `DE964E60E8D8F70C6B947F7AFE14951D92E3156F4DA6F8D945094AD18F9A00F4`.

## Keyboard status fix: bounded verification handoff

September 28, 2026. **Final build/install gate: PASS.** This replaces the failed-gate snapshot; it does not imply full physical acceptance of every status state.

### Build gate and final installed APK

- The parent completed the full JDK 21 foreground `gradlew --no-daemon :app:testStoreDebugUnitTest :app:assembleStoreDebug :app:testLegacyDebugUnitTest :app:assembleLegacyDebug` gate after fixing empty Polish cancellation overwriting busy voice feedback. This follow-up independently inspected both flavor XML reports: **270 tests per flavor, zero failures/errors/skips**, including the now-green status regression. The full tests were not rerun in this installation follow-up.
- Ran `npm run android:sync`, then rebuilt both debug APKs successfully with JDK 21 and SDK 35 using foreground Gradle `--no-daemon`. Updated only the existing legacy package on S24 `R5CWC1ZQTEW` with `adb install -r`; the store APK was audited, not installed.
- Final local APK, device-side `sha256sum`, and the pulled actual installed APK all match SHA-256 **`1008A7D72ED8A3A397D035614FCF22ECF03BE1447CD9B565C123F7FAAB9AC556`**. Local artifact: `apps/mobile/android/app/build/outputs/apk/legacy/debug/app-legacy-debug.apk`. The prior helper observed `FA62F135D21B1A18913C59B7E3E22FAE9EC6D4FB4E21A044332FC04B075B2EC6`, not the older `DE964E...` baseline; neither identifies this final installation.
- SDK 35 `aapt2` inspected the actual pulled installed APK: package `app.odicto.mobile.legacy`, version code 2, version `1.1-legacy`, compile/target SDK 35. Its manifest declares `OdictoImeService` with `BIND_INPUT_METHOD`, microphone-type `AudioCaptureService`, special-use `VoiceOverlayService`, `PauseTileService`, and `OdictoAccessibilityService` with `BIND_ACCESSIBILITY_SERVICE`. Overlay and special-use permissions are present. The rebuilt store manifest contains the IME, capture service and Room service, and excludes overlay/accessibility services and overlay/special-use permissions.
- Default IME was unchanged before/after install: `app.odicto.mobile.legacy/app.odicto.mobile.ime.OdictoImeService`. Post-install runtime service inspection showed the system-bound IME and `VoiceOverlayService` with `isForeground=true`, notification ID 18 and special-use type. `enabled_accessibility_services` remained `null`. Running services are not proof of overlay appearance, gestures, or insertion reliability.

- Targeted Prettier formatting and `npm run verify` passed: repository formatting, lint, typecheck, workspace tests, and build. The mobile JavaScript workspace has no test files; Android coverage is reported separately above.

### Controlled physical check and remaining limits

- Launched the already-installed permissionless `tools/keyboard-interaction-host` fixture and ran its host-only `typing` instrumentation against the legacy IME. **Passed:** exact fixed-sequence match, 40 generated character changes, expected/actual 45 UTF-16 units including the seed, final batch depth zero, no unbalanced ends. The runner locates keys only in the specified IME window and checks host focus; no personal field or clipboard content was collected.
- This establishes controlled typing through the final installed keyboard, **not status timeout acceptance**. No safe local status scenario was available in the existing fixture's documented typing/hold runner; its usage explicitly avoids microphone/Polish/clipboard controls. With production/tests frozen, no new status hook or runner was added. No before/after-timeout keyboard screenshots or geometry measurements were obtained, and no provider request was triggered to manufacture a status.
- A final keyboard-only follow-up routes repeated unsupported-field mic holds through fresh transient notices without changing the overlay notice path. Both full flavor suites/builds passed again, now with **271 tests per flavor** including the repeated-hold regression. Reinstalled legacy in place; final local/device SHA-256 matches **`95C1B5A3166F7223805EF2516C2FB0B918C119CC55AE7358FAC0D9C412BF94C7`** and the default IME is unchanged. The `1008A7...` manifest audit and controlled typing smoke above describe the preceding build, not a repeated physical trial of this final follow-up.
- Still unverified physically: stable screen-relative toolbar/root geometry through busy/completion/error/Copy/expiry, default three-second clearing and accessibility extension, noninteractive status text, retained Copy after clearing, rapid replacement without stale timers, keyboard reopen, Polish recovery/countdown/dismissal/expiry, repeated unsupported-field mic notices, narrow/landscape layouts, and unchanged floating-overlay interaction. Automated regressions do not establish these device results, real Notes/X insertion, or physical haptic onset.
- The fixture was force-stopped in a `finally` cleanup after its trial. No Odicto force-stop, uninstall, data clear, credential inspection, saved-data export, personal-content interaction, settings change, or provider call was performed. Existing app data and repository changes were preserved; this follow-up changed documentation only apart from generated sync/build outputs. All commands ran in the foreground; no worker-started background activity remains.
