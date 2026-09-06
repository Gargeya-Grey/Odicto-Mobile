# S24 Ultra voice UI check

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

1. Install the debug APK using `adb install -r apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk`. USB is only needed for installation/debugging.
2. Open Voice settings. Save your Groq key for Raw and AI speech. Save your Gemini key for Gemini AI and Live. For OpenRouter AI, save its key and select an explicit model ID. Keys stay in Android Keystore-backed storage.
3. Complete microphone, keyboard, overlay, and notification setup. Enable Floating microphone and select Odicto as the current keyboard.
4. Use Wi-Fi or mobile data. Remove old forwarding with `adb reverse --remove tcp:8080` if present, or unplug USB, to demonstrate independence from the computer.

Raw uses Groq `whisper-large-v3-turbo`. Gemini AI defaults to `gemini-3.5-flash-lite`; Live defaults to `gemini-3.5-transcribe-live`. Both Gemini model IDs can be changed in settings. Model availability and quota depend on the provider account.

## Five-minute interaction check

- Keyboard polish follow-up: hold Backspace (400 ms delay, then 65 ms repeat); verify release, sliding outside the key, closing the keyboard, and changing fields stop deletion immediately. Editing keys have dark rounded backgrounds, spacing, and a wider Space key; Switch keyboard is a quieter text control.
- Successfully inserted results dismiss after five seconds. Verify a new recording is not cleared by an older timer, while non-inserted/error results remain available to copy. The preview-expiry unit tests pass; repeat-key timing and visual checks on the S24 remain pending after APK installation.

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
- Start dictation and switch apps before completion. Text must not appear in the new field; retrieve it under Recent words. Test password, phone, numeric, and protected inputs: no voice controls.
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
- The bubble is available while enabled and its Android service survives; Samsung background restrictions, force-stop, or a reboot may require reopening Odicto. The keyboard mic is the fallback. An unavailable bubble outside a supported editor can open settings but cannot record.
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
