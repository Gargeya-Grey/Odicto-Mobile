# Android development

## Requirements

Install Android Studio, its JDK 21 runtime (required by Capacitor 7), Android SDK/platform 35, and platform tools. Open `apps/mobile/android` after running `npm run android:sync`.

## Activation test

Test the debug build on a physical device first; it is what ships. This checklist describes required checks, not passed results. Record actual evidence separately in [S24 Ultra testing](s24-ultra-testing.md); source changes and automated coverage alone do not establish that X insertion is fixed or physical interaction checks passed. Use synthetic content and never publish a test post. Verify visible output or an honest unverified/rejected state with Copy/history recovery, without automatic retries.

1. Install the debug APK on a physical device.
2. Grant microphone and notification permission.
3. Enable Odicto under system keyboard settings, then select it as the current keyboard.
4. Focus a normal text field. Type a sentence, then dictate with the keyboard microphone.
5. Test a space tap, hold/drag arming, small horizontal steps and immediate reversal over Unicode text. Vertical jitter must not jump to document edges; cancellation must insert nothing. Check exact key boundaries/gaps and the guarded uppercase hold choice.
6. Open emoji and clipboard panels and insert synthetic text. Exercise 51 distinct copies, pins, long exact paste, restart, clear, recopy, the capture disable switch, and sensitive/protected exclusions; clipboard content must never reach `logcat`.
7. Test a password field and a numeric field: typing works, while the microphone, emoji, and clipboard are refused with a visible reason.
8. Type a sentence without tapping Polish; verify no correction request is made. Set an OpenRouter key in Odicto settings, then tap the top-right Polish button in a normal field. Test selected-only and no-selection whole-field correction, unchanged surrounding text, reversed selections, proper names, cursor placement, and exactly one replacement. Exercise completed-result Copy/dismiss recovery and its 120-second expiry. Repeat while typing, moving the cursor, or switching fields during processing: the old field must not be overwritten. An unreadable/oversized field should show an in-keyboard refusal without sending text.
9. Check narrow one-handed/floating and >=600dp usable-width split layouts, rotation, inert center gap, both space halves, mic/Polish adjacency, compact Copy/stop/cancel access, and actual navigation insets. Test haptic timing, TalkBack, and scaled fonts on a real device.
10. Open the bank app that previously warned about an unknown accessibility app and record the result; the pause tile and in-app pause switch are the documented answer when it still warns.
11. Test the overlay, pause tile, and accessibility insertion while Samsung Keyboard is active.

Test Android 12, 14, and the current Play target on Pixel, Samsung, and Xiaomi hardware. Exercise denied permissions, offline requests, timeouts, keyboard switching, screen lock, process death, restart, and OEM battery restrictions.

## Direct provider connections

Android voice calls Groq, Gemini, and OpenRouter directly over the phone's internet connection. Enter keys in Voice settings; they are stored using Android Keystore. No `npm run dev`, USB forwarding, or hosted Odicto API is needed for dictation. USB is only used to install/debug the APK. See [S24 Ultra testing](s24-ultra-testing.md).

The top-right IME Polish button uses the same device-encrypted OpenRouter key but has its own model and system prompt in the Text polish settings section. The default model is exactly `poolside/laguna-xs-2.1`; a missing/unavailable model or key shows an error without a fallback. On an explicit tap, it sends only the selected substring, or the whole field if nothing is selected. Both paths require a complete eligible field snapshot of at most 20,000 UTF-16 units; surrounding guard text stays local for selected-only requests. Partial or protected fields are refused. Replacement revalidates the editor and original target range; completed but unapplied/unverified output has transient 120-second Copy/dismiss recovery, not persistent Polish history or automatic retry. Ordinary typing makes no network correction request.

## Build

```powershell
npm run build --workspace @odicto/mobile
npm run android:sync
cd apps/mobile/android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

The keyboard microphone is the approved fallback if OEM restrictions make background overlay recording unreliable.

## Keyboard feel settings

`KeySettings` in `VoicePreferences` holds the keyboard's feel, and the React Settings page edits it through `VoicePlugin.save`:

- `hapticLevel` 0-3 maps to off, light, medium, and strong. `Haptics.prepare` caches vibrator capabilities, immutable effects, and touch attributes at view initialization. Playback synchronously requests a supported touch primitive (`PRIMITIVE_TICK` / `PRIMITIVE_CLICK`) or one-shot fallback, using `USAGE_TOUCH` where supported; there is no delayed feedback worker. API request/return timing does not measure physical motor onset. A store written before levels existed resolves through the old `haptics` boolean, so a user who turned haptics off does not get them back. Read it with `VoicePreferences.levelOf(...)`, never the raw field. Each dispatched backspace repeat requests configured feedback once; an evidence-wait tick requests none. Feedback does not confirm host deletion.
- `keySize` small/medium/large maps to 46/52/58dp row height and 15.4/17.6/19.8sp glyphs. Key-gap margins are 2dp per side. Hit testing uses only each key's nonoverlapping rectangular bounds (left/top inclusive, right/bottom exclusive); gaps and outside touches are inert, with no nearest-key fallback. The spacebar row is 1.2 times a letter row; Enter matches backspace width. The letters page has no period key.
- Typing dispatch remains on touch-down with synchronous configured haptics and multitouch. Ordinary letters commit immediately, without a frame-spanning batch or per-letter selection query; compound replacements use short balanced operation-scoped batches. Session-observed/predicted state and a bounded edit ledger replace single-expected-cursor tracking; see [Manual input continuity](architecture.md#manual-input-continuity) for evidence and repeat bounds. A 300ms letter hold switches the pressed-key preview to the capital letter and highlights the uppercase choice inside the IME; release on it accepts, moving away cancels. Replacement is guarded by the actual committed character/range, editor identity, and text/selection generations, never unconditional backspace; already auto-capitalized letters are not duplicated. Secondary typing, geometry/page changes, focus loss, and cancellation invalidate the choice. Shift/caps lock and an accessible uppercase action remain available.
- Space cursor mode arms after a 250ms hold or approximately 8dp horizontal travel. Activation travel is consumed; subsequent 4dp signed steps move by grapheme with fractional remainder and immediate reversal, rather than waiting to retrace the previous excursion. Vertical motion never jumps to document edges. A tap inserts one space on release; cancelled/armed gestures insert none. Session-local cursor prediction is invalidated on editor/text/external selection changes. Gestures remain disabled during voice/AI selection work.
- Holding comma offers punctuation and sliding onto a choice replaces the comma. Enter follows the editor's Search/Go/Send/Next/Done/newline action. Double-tap Shift locks capitals; sentence capitalization and double-space period remain. Held backspace enters the word phase after ten steps when context permits. Transient evidence gaps suspend destructive dispatch and allow the same hold to resume; opaque/ambiguous hosts may exhaust the bounded allowance. Primary/secondary pressed states have separate lifecycles; backspace repeat belongs to its initiating pointer and primary space gestures stay with their owner. Secondary cleanup precedes pointer assignment. Owner release/cancellation ends the hold rather than transferring it to the other thumb. Prefix suggestions remain hidden to avoid height changes.
- `keyFont` is `system` or `sansflex`. Google Sans Flex is the default key face, bundled in `assets/fonts/GoogleSansFlex.ttf` from `google/fonts` under the SIL Open Font License 1.1, with `assets/fonts/GoogleSansFlex-OFL.txt` shipped alongside. A stored `lora` value is read as Sans Flex. An unreadable font falls back to the system sans and must never crash the keyboard.
- `pinnedEmoji` is the favourites row, stored comma-joined, capped at five, re-validated on load. Toggle it with `VoicePreferences.withPinned(...)`.

The IME toolbar reserves six fixed cells (48dp regular, 40dp compact below 370dp usable width): settings/cancel, Raw, AI, Live, lock/Copy, and mic. Polish retains its adjacent 48dp allocation. Empty slots have no touch or accessibility target; busy/result/meter changes do not move persistent controls. The compact allocation requires at least 288dp of usable toolbar width including Polish; narrower widths are not a supported fit.

The status and recovery descriptions below are implementation contracts, not a physical-device acceptance result. See [the bounded status verification handoff](s24-ultra-testing.md#keyboard-status-fix-bounded-verification-handoff) for the installation gate and outstanding checks. Do not reinstall a candidate after a failed full Gradle run until that failure is resolved.

Status reserves one font-scale-aware line even when empty (`INVISIBLE`, never `GONE`), with end ellipsis and normalized newlines. It is not clickable or expandable. Busy messages persist until the operation ends; terminal messages and explicit Copy feedback clear after three seconds, extended by Android's recommended accessibility timeout. Complete current warnings remain in the visible status accessibility description. Expiry hides only IME presentation: retained voice results and explicit Copy remain available, and neither delivery nor history persistence is inferred from message expiry. Meter/history updates do not renew deadlines; hiding/recreating the keyboard cancels view callbacks without replaying expired results. Copy rechecks eligible editor policy at the tap.

Polish Copy/Dismiss/countdown replaces the existing content slot rather than adding a row below the header. Dismiss returns to typing; copying or its unchanged 120-second expiry also closes recovery. Countdown ticks do not resize the keyboard or announce every second. The bottom tools retain actual navigation-bar insets instead of the old fixed 18dp margin. These are keyboard-only changes: floating mic size, placement, gestures, overlay layout and Copy toast remain unchanged.

At >=600dp usable keyboard width, rows automatically split into balanced halves with an inert center gap and space under both thumbs; this is width-driven, not orientation-only. Narrow windows retain docked, one-handed, and draggable floating layouts. Resize/rotation recomputes geometry and cancels active gestures. Fullscreen extract mode remains disabled.

Panels replace the key grid, never stack below it outside the IME window. Clipboard rendering uses saved state, not a platform clipboard read: the panel discloses gated automatic local capture and offers a disable switch, 50 recent unpinned items, persistent pins, selection/delete, and Clear all unpinned. See [Privacy and security](privacy-and-security.md#local-clipboard-and-editor-reads) for exact-text storage, migration limitations, and capture eligibility.

## Current trace — debug-only, content-free

The input core and probes must be judged by the replay and physical results recorded in [S24 Ultra testing](s24-ultra-testing.md), not by API timing alone. The isolated host includes a transient-read profile that reproduces and checks recovery from the earlier held-backspace stop.

`KeyLatencyProbe` keeps a bounded 1,024-sample ring and exports count/p50/p95/p99/max off the input hot path, including short trials on keyboard hide. It records event-queue/handler/hit timing, haptic preparation/request/API duration, instrumented character submission/return, cursor API duration, and repeat lateness. Event uptime is explicitly converted relative to handler-entry elapsed time; touch-to-* samples start at handler entry, not physical finger contact. A metric declaration alone does not establish coverage: the batch-close hook is not a host-frame marker, and read/depth/suspension metrics must not be assumed present in the IME trace. Samples contain local event IDs and timings, never text, key values/labels, clipboard payloads, prompts, or credentials.

Use the standalone permissionless `tools/keyboard-interaction-host/` (`app.odicto.keyboardtrial`) for isolated generated-text trials: native single-/multiline fields, switching, restart/recreate, Unicode samples, and host-only instrumentation across the real IME process boundary. It requests no permissions, stores no text/credentials, and disables backup. Keep trials out of personal notes/conversations; never clear/uninstall personal Odicto or inspect saved keys.

Measurement bounds are separate: **IME API return ≠ host text application ≠ host draw ≠ screen presentation**. The host's `apply` series times wrapped editor-method execution; text-watcher changes establish local mutation, deletion intervals measure mutation gaps, and `changeToDraw` spans the first pending change to the next active editor `onDraw`, potentially coalescing changes. Batch depth/lifetime and Choreographer frame opportunities are not presentation evidence or end-to-end event correlation. Haptic API timing is not physical vibration onset. Report presentation and motor onset as unmeasured without independent instrumentation; distributions alone do not prove improved feel or a fixed stall.

## What ships in the APK

There is a single build — no product flavors. `app.odicto.mobile` ships the IME, the microphone capture service, the floating overlay with its `specialUse` foreground service, the Quick Settings pause tile, and `OdictoAccessibilityService`. The bubble requires the Android display-over-other-apps permission. `BuildCapability.overlaySupported` / `accessibilitySupported` are both `true`; shared code must still consult them instead of assuming a service exists, and `VoiceOverlayController` returns without starting anything when a future build flips them off.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

`OdictoAccessibilityService` is the insertion channel while a non-Odicto keyboard is active. Keep it limited to focus tracking and insertion; it must never navigate, gesture, or collect screen content, and it must stand down when the Odicto IME owns the editor. Android 13+ blocks enabling accessibility for sideloaded builds until the tester allows restricted settings; the Android setup card points at App info → ⋮ → Allow restricted settings when the toggle stays off. Pausing (the in-app switch or `PauseTileService` in the shade) stops the overlay and calls `disableSelf()` on the service.

`VoiceOverlayService` runs as a `specialUse` foreground service while the bubble is enabled, so OEM freezers cannot suspend the process behind an unresponsive overlay. It stops itself when the bubble is disabled, paused, or the overlay permission is missing; the persistent notification is minimal importance and opens the app when tapped.

## Play readiness

- Play distribution is not current. The single build ships the AccessibilityService and the overlay, so a future Play release would need the AccessibilityService declaration, its demo video, and disclosures for the `specialUse` overlay — or a reintroduced capability-free flavor at that point.
- Still required before an upload: the data safety form (microphone, network processing by the selected AI providers, device-only history, optional BYOK), the `FOREGROUND_SERVICE_MICROPHONE` and `FOREGROUND_SERVICE_SPECIAL_USE` declarations, and a target API check. The project targets 35 and the test device runs Android 16 (API 36).
- The pause control exists because banks and other protected apps refuse to run with an overlay drawn or an accessibility service enabled. It is not a way to bypass those checks and should never be described as one.
