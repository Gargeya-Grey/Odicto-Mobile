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

## Transient-read compatibility trial

Add `-e profile transient` to the `hold` command to make this generated editor return unavailable surrounding/selected/extracted text between 150 and 750 ms after measurement starts. Writes are not delayed by this profile. This is controlled fault injection, not proof that a particular real app uses the same behavior. The same finger remains down after reads recover.

The continuity gate requires more than ten text changes, an exact retained prefix, balanced batches, and a final deletion within 400 ms before release (equivalently `terminalDeletionGapMs < 900`, including the fixed 500 ms drain). Use a long fixture so reaching the beginning cannot explain a stop. A completed runner with only one deletion fails this gate even when `exactMatch=true`.

The host keeps its own window awake only while it is foregrounded. It does not change the system screen timeout. `displayRefreshHz` records the reported display rate at summary time; adaptive mode can change during a run.

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

The manifest requests **zero permissions**, disallows backup, and has no providers/services. Text exists only in process memory; view state saving/autofill are disabled and the window uses `FLAG_SECURE`. No clipboard API, files, network or recording code exists. The external IME remains outside this app's permission boundary; use only generated fixtures and never invoke its microphone/polish/clipboard controls. The normal eligible text input flags deliberately avoid `IME_FLAG_NO_PERSONALIZED_LEARNING`, which would select Odicto's protected/no-read path instead of the ordinary editing path being measured.
