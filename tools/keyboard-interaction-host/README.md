# Disposable physical keyboard host

Native Java, AGP 8.7.2, compile/target SDK 35, minimum SDK 26. This is an independent single-project build, not a module of the production app. No dependencies, new wrapper, npm files, credentials, or production modifications. The framework instrumentation runner is embedded in the fixture APK and targets **only `app.odicto.keyboardtrial`**; no second test APK is needed.

## Build (PowerShell, repository root)

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\apps\mobile\android\gradlew.bat -p tools/keyboard-interaction-host assembleDebug --console=plain
```

APK: `tools/keyboard-interaction-host/build/outputs/apk/debug/OdictoKeyboardTrial-debug.apk`.

## Parent-operated device commands

These commands are supplied, not executed by the fixture author. Coordinate exclusive touch ownership with the person using the device. Unlock the device, manually select the installed Odicto IME, close its clipboard/emoji/settings panels, and use the lowercase alphabet layout. Do not change its settings, install a production APK, inspect personal app data, or open a personal document. Use the actual installed IME package in `$imePackage` (for example `app.odicto.mobile`); the runner refuses a different package's IME window. Screen/display settings are not changed.

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial='REPLACE_WITH_AUTHORIZED_DEVICE_SERIAL'
$imePackage='app.odicto.mobile'
& $adb -s $serial install -r tools/keyboard-interaction-host/build/outputs/apk/debug/OdictoKeyboardTrial-debug.apk
& $adb -s $serial shell am start -n app.odicto.keyboardtrial/.TrialActivity
& $adb -s $serial shell am instrument -w -r -e scenario hold -e imePackage $imePackage app.odicto.keyboardtrial/.TrialInstrumentation
& $adb -s $serial shell am instrument -w -r -e scenario typing -e imePackage $imePackage app.odicto.keyboardtrial/.TrialInstrumentation
& $adb -s $serial logcat -d -v threadtime -s OdictoTypingTrial:I '*:S'
```

Repeat the two instrument commands for baseline/candidate comparisons under the same orientation, IME layout/settings and refresh mode. The instrumentation result bundle contains `summary` and `status`; logcat contains the same content-free aggregates. No logcat clearing, device cleanup, uninstall, data clearing, IME selection, network operation, submission/Enter, or production Gradle task is used. `CLEAR_TASK` affects only the disposable host activity task, not application storage.

## Controls and workloads

- **Seed:** reset both fields and populate the focused field with 160 generated multiword Unicode phrases, including a combining accent and emoji sequence; move the caret to the end; restart input and measurement.
- **Clear/reset:** clear both generated fields and restart the focused editor and measurements.
- **Restart:** call `restartInput` on the same editor without replacing its contents.
- **Switch:** focus the other native field (multiline and single-line).
- **Recreate:** recreate the activity with a fresh generated fixture, never restore text from saved state.
- **Report:** stop and export aggregate measurements, never field content. The manual exact-match check is against the most recently seeded value; edited text normally fails it.

The `hold` run locates the exact `Backspace` description **only within an input-method accessibility window owned by the specified Odicto package**. It injects DOWN, stationary paced MOVEs, and UP across 4 seconds, then allows 500 ms for pending host work. There is only one press; it never lifts/represses to recover a stall. `deletedUnits`, `changes`, `longestDeletionGapMs` and `terminalDeletionGapMs` expose progress/stalls.

The `typing` run seeds `seed ` to avoid measuring sentence-start auto-capitalization, then locates the lowercase `f`, `j`, `d`, `k` descriptions in that same restricted window. It injects 20 overlapping two-thumb pairs (40 expected characters, `fjdk` repeated ten times). Each pair has DOWN at 0 ms, POINTER_DOWN at 35 ms, MOVE at 55 ms, POINTER_UP at 75 ms, UP at 100 ms, then a 20 ms gap. Both release orders are exercised; pointer IDs, current pointer indices and common gesture downTime are preserved. Injections use real uptime timestamps, paced deadlines and synchronous UiAutomation dispatch. A best-effort CANCEL releases pointers on failure. Missing/ambiguous keys, wrong package, lost host focus or injection failure abort rather than tap guessed coordinates. Do not rotate or change layout during a run. No other application accessibility tree is read. Existing accessibility services are not suppressed.

## Voice-first and repeated emoji trial

```powershell
& $adb -s $serial shell am instrument -w -r -e scenario voicefirst -e imePackage app.odicto.mobile app.odicto.keyboardtrial/.TrialInstrumentation
```

This scenario starts a fresh generated `seed ` editor, confirms the correct IME through its existing microphone description, then checks that only the voice bar is initially shown: `Show keyboard` is available and letter/space/number keys are absent. Running it against the original full-keyboard behavior must report `status=failed initialCollapsed=false`, rather than a setup abort. On the candidate it expands the key grid, inserts two fixed emoji from Smileys, compares the entire generated fixture and end selection after each insertion, and requires the emoji panel to remain open after both presses.

The runner visits every catalog category through the visible selector, requires at least 32 distinct emoji per category, and scrolls pages with offscreen entries to confirm that the visible choices change. It requires at least one scrollable page. Category labels have the accessible description `Emoji category <label>` and the vertical scroll container is `Emoji list`; only the specified IME window is searched. Offscreen category selectors are brought into view with Android's standard show-on-screen accessibility action, then tapped through the same bounded input injection path as other trials. No hidden selector is clicked directly.

After closing the panel through the same Emoji button, it uses `123` to insert double and single quotes plus basic punctuation, asserts exact generated contents, collapses and expands the grid, and switches between the fixture's two editors to check that each new focus starts collapsed. The fixture counts editor actions and Enter key events and requires both to remain zero. Results contain Booleans and counts, never field text, category labels, or emoji values. The trial does update the IME's normal local recent-emoji list through real user-facing presses; it does not reset any keyboard preference or read personal applications. It never presses the microphone, clipboard, voice-mode, or submission controls. This scenario does not establish audio/provider or floating-overlay behavior.

Existing `hold`, `typing`, `status`, `geometry`, `targets`, and `boundary` scenarios automatically expand via `Show keyboard` when present before their original checks. Their original expected input sequences and geometry gates are unchanged. Check instrumentation `status` and `summary`; ADB itself can exit zero after an assertion failure.

## Optional local autocorrect trial

The parent operator enables **On-device autocorrect** through Odicto's user-facing keyboard settings before running `autocorrect`, then disables it before running `autocorrectoff`. Use the actual displayed setting label if it changes. These scenarios never alter the preference themselves, read saved app settings, or invoke AI Polish. They target only `app.odicto.mobile` and use generated English words typed through real visible keys. Normal fixture fields for these two scenarios omit `TYPE_TEXT_FLAG_NO_SUGGESTIONS`; existing scenarios keep their original flags and expectations.

```powershell
# Enable the optional feature in Odicto Settings first.
& $adb -s $serial shell am instrument -w -r -e scenario autocorrect -e imePackage app.odicto.mobile app.odicto.keyboardtrial/.TrialInstrumentation
# Disable the optional feature in Odicto Settings before this separate run.
& $adb -s $serial shell am instrument -w -r -e scenario autocorrectoff -e imePackage app.odicto.mobile app.odicto.keyboardtrial/.TrialInstrumentation
```

The enabled trial starts with the compact voice bar and no spelling strip. After expansion it types the independently fixed typo `teh` followed by Space and requires the complete generated field to equal `seed the `. This assertion comes before discovery of the new suggestion controls: running against a keyboard without autocorrect must report `status=failed correctionExact=false`, not merely abort on a missing control. The harness waits up to three seconds for exact fixture text and caret state, and up to five seconds for a suggestion's visible accessible target.

It then verifies all of these outcomes through the actual IME/host boundary:

- Backspace restores `teh` and removes the triggering space; the next Space leaves that original spelling unchanged. A subsequent `recieve` becomes `receive` normally.
- Typing `becuase` offers `Suggestion because`; tapping it produces exactly `because` followed by one space, as a picked word ends the word.
- `Spelling suggestions` is visible while expanded, absent after collapse, and visible again after expansion.
- A later correction works again. Tapping `Undo autocorrect` restores the original spelling without the space; its next Space is also exempt from immediate correction, while the following word can correct normally.
- Focusing the other generated field starts collapsed and clears the previous undo. Backspace in the new field edits only that field. Returning to the original field also clears stale undo and leaves its corrected spelling intact when deleting the trailing space.
- Password, email-address, URI, explicit no-suggestions, and no-personalized-learning profiles still permit ordinary letter/space typing; they leave `teh` unchanged and expose neither spelling suggestions nor an undo action.
- Every profile requires zero editor-action calls, zero Enter key events, and balanced edit batches. A successful run restores a fresh normal `seed ` fixture with the compact bar.

The disabled trial requires `teh` to remain unchanged, the expanded spelling strip and undo action to remain absent, and ordinary Backspace to remove only the trailing space. The parent should also check that the chosen setting survives reopening Settings; preference persistence, no-network implementation, and changed-cursor/selection guards need their separate product checks. Neither scenario reads another application's accessibility tree, exports generated text, presses the microphone or provider controls, nor establishes autocorrect accuracy for arbitrary vocabulary or languages. Results expose only scenario/check identifiers, Booleans, and counts. Existing app preferences are left at the last state chosen by the parent operator.

## Transient-read compatibility trial

Add `-e profile transient` to the `hold` command to make this generated editor return unavailable surrounding/selected/extracted text between 150 and 750 ms after measurement starts. Writes are not delayed by this profile. This is controlled fault injection, not proof that a particular real app uses the same behavior. The same finger remains down after reads recover.

The continuity gate requires more than ten text changes, an exact retained prefix, balanced batches, and a final deletion within 400 ms before release (equivalently `terminalDeletionGapMs < 900`, including the fixed 500 ms drain). Use a long fixture so reaching the beginning cannot explain a stop. A completed runner with only one deletion fails this gate even when `exactMatch=true`.

The host keeps its own window awake only while it is foregrounded. It does not change the system screen timeout. `displayRefreshHz` records the reported display rate at summary time; adaptive mode can change during a run.

## Boundary routing and visible-target checks

`-e scenario boundary` injects one tap between adjacent letter bounds in the generated `seed ` editor, then reports only whether exactly one adjacent character was added. It exports bounds/counts, never the text. In addition to Odicto package IDs, the runner explicitly permits Samsung Keyboard's `com.samsung.android.honeyboard` for this comparison; other non-Odicto packages remain refused. The requested package must actually be the active keyboard, and inspection remains limited to its input-method window. Do not attempt injection through a lock screen or change keyboard selection without authorization.

```powershell
& $adb -s $serial shell am instrument -w -r -e scenario boundary -e imePackage $imePackage app.odicto.keyboardtrial/.TrialInstrumentation
& $adb -s $serial shell am instrument -w -r -e scenario targets -e keyGapDp 2 -e imePackage $imePackage app.odicto.keyboardtrial/.TrialInstrumentation
```

`targets` checks visible adjacent cap spacing, not touch tolerance: Odicto's visual margin stays 2dp in every tolerance mode. `boundary` checks routing across that painted gap. Forgiving should accept exactly one adjacent key; Precise intentionally refuses the gap. Neither test establishes capacitive detection through a screen protector, physical pressure thresholds, or general typing superiority. For manual trials, compare known attempted taps with the debug-only `OdictoKeyLatency` outcome totals after hiding the keyboard; no received event is not enough to distinguish hardware loss from upstream routing. Do not clear general logs or inspect personal text to collect the counts.

## Tablet geometry preview

For a generated-text spelling preview, add `--ez spelling_preview true` to the portrait preview launch below. This uses an ordinary prose field with the short `seed ` fixture, so an enabled autocorrect setting can be inspected visually. It changes no persistent keyboard setting.

The `autocorrect` scenario verifies that the expanded empty row is hidden, a correctly spelled word has no suggestions, and ordinary Space leaves no empty row. It separately checks `teh` before Space, `the ` after Space, and both Undo paths; a fresh empty field checks capitalized `The ` and `Teh` after Undo. Reopening or changing fields must not display an empty suggestion container.

The existing `voicefirst` scenario also accepts `-e profile spelling` to exercise the emoji/toolbar regression in normal prose fields while optional spelling is enabled. It retains all original output and repeated-emoji expectations. The autocorrect trial waits for expansion to settle before locating typing targets; policy profiles reconfigure the same field via `restartInput`, so they explicitly collapse/reopen rather than assuming a fresh editor focus.

Normal trials retain `FLAG_SECURE`. For a generated-text-only visual check, launch the disposable activity with `--es geometry_preview portrait` or `--es geometry_preview landscape`. This explicit mode permits screenshots of the fixture and requests orientation for this activity only; it does not change persistent device rotation settings. Do not enter personal content in preview mode. Launch a fresh task (`-f 0x10008000`, NEW_TASK plus CLEAR_TASK, affecting only the disposable fixture) when changing orientation or removing the extra to return to screenshot protection; delivering a new intent to an existing activity does not reconfigure the preview.

On the tested Samsung S24 Ultra, a protected trial that has drawn can leave subsequent previews producing entirely black `screencap` images even though the new window no longer has `FLAG_SECURE`. Cold starts sometimes restored capture, but also failed with confirmed window teardown and settling delays. In the same failing preview, Android's `screenrecord` path produced a visible display frame including the IME and overlay. The underlying Samsung capture/compositor mechanism remains unconfirmed. Do not remove capture protection from normal trials or change general device security. Use the checked capture helper (Python 3, Pillow and FFmpeg, development-only dependencies):

```powershell
python tools/keyboard-interaction-host/capture-preview.py --serial $serial --output work/gc-artifacts/YOUR-TASK/compact.png
# After interacting only with the generated field, preserve the current UI for another capture:
python tools/keyboard-interaction-host/capture-preview.py --serial $serial --existing --output work/gc-artifacts/YOUR-TASK/expanded.png
```

The helper checks that the phone is awake/unlocked, cold-starts only the fixture in screenshot-enabled prose preview mode, and refuses other foreground apps or secure fixture windows. By default it captures two seconds of silent display video, retains the companion `.h264` evidence and decodes its first frame to PNG. It checks the fixture again before saving and fails on an all-black image. `--existing` captures without resetting the preview; always use a new evidence filename. `--method screencap` uses the native PNG path for diagnosis and preserves a black result as failure evidence. These are real device captures, never reconstructed UI images.

```powershell
& $adb -s $serial shell am force-stop app.odicto.keyboardtrial
& $adb -s $serial shell am start -W -f 0x10008000 -n app.odicto.keyboardtrial/.TrialActivity --es geometry_preview portrait
& $adb -s $serial shell am instrument -w -r -e scenario geometry -e orientation portrait -e imePackage $imePackage app.odicto.keyboardtrial/.TrialInstrumentation
& $adb -s $serial shell am instrument -w -r -e scenario geometry -e orientation landscape -e imePackage $imePackage app.odicto.keyboardtrial/.TrialInstrumentation
```

The geometry scenario targets a roomy split tablet (the SM-X810 acceptance case), not a narrow phone. It inspects only the specified IME window and exports minimum/maximum letter widths, both spacebar widths, and the `t`-to-`y` cap gap in dp; no host text is collected. Its gate requires letter and space widths to agree within one pixel, equal outer edge insets of at least 60dp on this roomy tablet, portrait cap separation at most 69dp, and landscape cap separation between 374 and 381dp. Cap separation includes key margins in addition to the designed 64/375dp maximum split spacers. These measured bounds do not establish subjective thumb reach, all-page completeness, or touch correctness; use the view regression suite, screenshots, and typing trials separately. Check `passed`/`status`, not the ADB process exit code, which can be zero for a failed assertion.

## Reading results honestly

The runner exports `passed=true/false` and `status=passed/failed` using the correctness, batch-balance, and sustained-progress gates above. This is not a latency or physical-feel verdict. `status=aborted` in the summary exports only the exception class, never exception text or node contents. Earlier baseline captures used `status=completed`; those captures were evaluated by the same explicit gates externally.

- Typing `exactMatch` independently compares the entire actual field to the fixed expected sequence, including a collapsed caret at its end. Only the Boolean and expected/actual UTF-16 lengths are exported, never the sequence or field value.
- Hold `exactMatch` uses `oracle=remainingPrefix`: the actual result must equal the original generated prefix at the observed remaining length, with an end caret. It checks that deletion did not corrupt the retained prefix; **it does not assert a predetermined deletion count**, since timer cadence and the character-to-word transition are device/IME dependent. Even no progress can preserve a prefix, so inspect `progress=true`, deleted units, gaps and before/after distributions separately. It is not an independent semantic oracle for word boundaries.
- `applyCount/readCount/batchCount` and total/p50/p95/p99/max durations measure calls through the real host `InputConnectionWrapper`, using `System.nanoTime`. Reads cover before/after/selected/surrounding/extracted text and cursor caps; mutations cover commits, composition, selection, deletion and key events. No arguments or returned text are recorded. Counts include unsuccessful calls. These are host-side API execution durations, **not full cross-process IPC latency**. Unoverridden framework APIs are not included.
- `batchLifetime` measures outermost successful begin through matching end. Depth/max depth/unbalanced ends expose unfinished or unbalanced batches. Resetting metrics mid-batch is not a valid batch-lifetime trial.
- Each timing series retains at most its first 8192 duration samples. Counts, totals and maxima cover all calls; percentiles cover only retained samples (`Sampled`). Formatting/sorting/logging happens only at report time.
- `TextWatcher` records length decreases in UTF-16 units, notification count, and deletion gaps. Gaps include the initial wait and trailing drain (500 ms for hold), so compare equivalent windows. `changes` counts all notifications, not just deletion notifications.
- `draws` counts the active editor's `onDraw` completions. `changeToDraw` measures the first pending TextWatcher change until the next editor draw; several changes can share a draw. It is a draw opportunity, not compositor presentation. `deleteInterval` measures gaps between text decreases, including the initial hold-arming gap but excluding the trailing drain. `frameOpportunities` and `maxFrameGapMs` measure host Choreographer callbacks, not IME frames. Continuous frame callbacks add a small declared measurement cost.
- `injectedEvents/maxInjectionLatenessMs` describe the injection stream, not physical finger latency. Host draw is not compositor presentation; no physical haptic onset, motor latency, hardware touch latency, allocation/GC or Binder trace is measured.
- The fixed 500/700 ms drain is a bounded observation window, not proof all remote work is quiescent. No performance improvement or physical-device correctness is claimed by a successful build.

The manifest requests **zero permissions**, disallows backup, and has no providers/services. Text exists only in process memory; view state saving/autofill are disabled and the window uses `FLAG_SECURE` except in the explicit generated-text geometry preview mode. No clipboard API, files, network or recording code exists. The external IME remains outside this app's permission boundary; use only generated fixtures and never invoke its microphone/polish/clipboard controls. The normal eligible text input flags deliberately avoid `IME_FLAG_NO_PERSONALIZED_LEARNING`, which would select Odicto's protected/no-read path instead of the ordinary editing path being measured.
