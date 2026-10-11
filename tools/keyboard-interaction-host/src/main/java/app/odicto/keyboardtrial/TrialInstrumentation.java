package app.odicto.keyboardtrial;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class TrialInstrumentation extends Instrumentation {
    private Bundle arguments;
    private TrialActivity host;
    private UiAutomation automation;
    private String imePackage;
    private int injected;
    private long maxLateness;

    public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }

    public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!getTargetContext().getPackageName().equals("app.odicto.keyboardtrial")) throw new IllegalStateException();
            imePackage = arguments.getString("imePackage", "app.odicto.mobile");
            if ((!imePackage.startsWith("app.odicto.") && !imePackage.equals("com.samsung.android.honeyboard")) || imePackage.equals("app.odicto.keyboardtrial")) throw new IllegalArgumentException();
            String scenario = arguments.getString("scenario", "hold");
            boolean correctionScenario = scenario.equals("autocorrect") || scenario.equals("autocorrectoff");
            if (!scenario.equals("hold") && !scenario.equals("typing") && !scenario.equals("status") && !scenario.equals("geometry") && !scenario.equals("targets") && !scenario.equals("boundary") && !scenario.equals("voicefirst") && !correctionScenario) throw new IllegalArgumentException();
            if ((scenario.equals("voicefirst") || correctionScenario) && !imePackage.equals("app.odicto.mobile")) throw new IllegalArgumentException();
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            Intent intent = new Intent(getTargetContext(), TrialActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (scenario.equals("geometry")) {
                String orientation = arguments.getString("orientation", "landscape");
                if (!orientation.equals("portrait") && !orientation.equals("landscape")) throw new IllegalArgumentException();
                intent.putExtra("geometry_preview", orientation);
            }
            host = (TrialActivity) startActivitySync(intent);
            runOnMainSync(() -> {
                if (correctionScenario || scenario.equals("voicefirst") && "spelling".equals(arguments.getString("profile"))) host.prepareCorrectionProfile("normal");
                else host.prepare(scenario.equals("status") ? "" : scenario.equals("hold") ? TrialActivity.generated() : "seed ");
                host.transientReads = "transient".equals(arguments.getString("profile", "normal"));
            });
            SystemClock.sleep(1000);
            if (!scenario.equals("voicefirst") && !correctionScenario) expandKeyboardIfCollapsed();
            if (correctionScenario) autocorrect(result, scenario.equals("autocorrect")); else if (scenario.equals("voicefirst")) voicefirst(result); else if (scenario.equals("boundary")) boundary(result); else if (scenario.equals("targets")) targets(result); else if (scenario.equals("geometry")) geometry(result); else if (scenario.equals("status")) status(result); else if (scenario.equals("hold")) hold(result); else typing(result);
            boolean passed = result.getBoolean("passed", false);
            result.putString("status", passed ? "passed" : "failed");
            finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        } catch (Throwable failure) {
            String diagnostic = result.getString("summary", "") + " status=aborted errorType=" + failure.getClass().getSimpleName();
            Log.e(TrialActivity.TAG, diagnostic);
            result.putString("summary", diagnostic);
            result.putString("status", "aborted");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void expandKeyboardIfCollapsed() {
        if (hasStatus("Show keyboard")) {
            tap(locate("Show keyboard"));
            locate("Hide keyboard");
            SystemClock.sleep(400);
        }
    }

    /** Real key taps into generated fixtures only; the parent sets the public preference first. */
    private void autocorrect(Bundle result, boolean enabled) {
        result.putString("summary", "scenario=" + (enabled ? "autocorrect" : "autocorrectoff") + " hostTextExported=false");
        result.putBoolean("passed", false);
        locate("Hold to speak. Double tap for voice settings.");
        if (!voiceCheck(result, "initialCollapsed", collapsed() && suggestionsHidden())) return;
        tap(locate("Show keyboard")); SystemClock.sleep(400);
        if (!voiceCheck(result, "expanded", awaitDescription("q") && hasStatus("Hide keyboard"))) return;
        if (!voiceCheck(result, "expandedEmptySuggestionsHidden", suggestionsHidden())) return;
        if (enabled) {
            typeLowercase("hello");
            SystemClock.sleep(300);
            if (!voiceCheck(result, "correctWordUnchanged", awaitFixtureEquals("seed hello") && !hasStatus("Undo autocorrect"))) return;
            tap(locate("Space"));
            if (!voiceCheck(result, "ordinarySpaceLeavesWordUnchanged", awaitFixtureEquals("seed hello ") && !hasStatus("Undo autocorrect"))) return;
            runOnMainSync(() -> host.prepare(""));
            SystemClock.sleep(400);
            expandKeyboardIfCollapsed();
            typeLowercase("teh ");
            if (!voiceCheck(result, "emptyFieldCapitalizedCorrection", awaitFixtureEquals("The "))) return;
            tap(locate("Backspace"));
            if (!voiceCheck(result, "emptyFieldCapitalizedUndo", awaitFixtureEquals("Teh"))) return;
            runOnMainSync(() -> host.prepare("seed "));
            SystemClock.sleep(400);
            expandKeyboardIfCollapsed();
        }
        typeLowercase("teh");
        if (!voiceCheck(result, "tehUnchangedBeforeSpace", awaitFixtureEquals("seed teh"))) return;
        if (enabled && !voiceCheck(result, "theSuggestionBeforeSpace", awaitDescription("Suggestion the"))) return;
        tap(locate("Space"));
        // This independent value assertion rejects the old app before looking for new controls.
        if (!voiceCheck(result, "correctionExact", awaitFixtureEquals(enabled ? "seed the " : "seed teh "))) return;
        if (!enabled) {
            if (!voiceCheck(result, "disabledSuggestionsHidden", suggestionsHidden() && !hasStatus("Undo autocorrect"))) return;
            tap(locate("Backspace"));
            if (!voiceCheck(result, "disabledBackspaceExact", awaitFixtureEquals("seed teh"))) return;
            if (!noSubmission(result, "disabledNoSubmissionAndBalanced")) return;
            result.putBoolean("passed", true);
            Log.i(TrialActivity.TAG, result.getString("summary"));
            return;
        }

        if (!voiceCheck(result, "expandedSuggestionsVisible", awaitDescription("Spelling suggestions") && awaitDescription("Undo autocorrect"))) return;
        tap(locate("Backspace"));
        if (!voiceCheck(result, "backspaceUndoExact", awaitFixtureEquals("seed teh"))) return;
        typeLowercase(" ");
        boolean preservedUndo = awaitFixtureEquals("seed teh ");
        if (!preservedUndo) {
            // Compare only fixed generated alternatives; never export host content.
            voiceCheck(result, "undoSpaceWasMissing", fixtureEquals("seed teh"));
            voiceCheck(result, "undoSpaceWasRecorrected", fixtureEquals("seed the "));
        }
        if (!voiceCheck(result, "undoNotImmediatelyRecorrected", preservedUndo)) return;
        typeLowercase("recieve ");
        if (!voiceCheck(result, "nextWordCorrected", awaitFixtureEquals("seed teh receive "))) return;

        typeLowercase("becuase");
        if (!voiceCheck(result, "uncommittedTypoUnchanged", awaitFixtureEquals("seed teh receive becuase"))) return;
        if (!voiceCheck(result, "suggestionOffered", awaitDescription("Suggestion because"))) return;
        tap(locate("Suggestion because"));
        if (!voiceCheck(result, "suggestionEndsWord", awaitFixtureEquals("seed teh receive because "))) return;
        tap(locate("Hide keyboard"));
        SystemClock.sleep(400);
        if (!voiceCheck(result, "collapsedSuggestionsHidden", collapsed() && suggestionsHidden())) return;
        tap(locate("Show keyboard")); SystemClock.sleep(400);
        if (!voiceCheck(result, "reexpandedEmptySuggestionsHidden", awaitDescription("q") && suggestionsHidden())) return;
        typeLowercase("teh ");
        if (!voiceCheck(result, "repeatCorrectionExact", awaitFixtureEquals("seed teh receive because the "))) return;
        if (!voiceCheck(result, "undoChipAvailable", awaitDescription("Undo autocorrect"))) return;
        tap(locate("Undo autocorrect"));
        if (!voiceCheck(result, "chipUndoExact", awaitFixtureEquals("seed teh receive because teh"))) return;
        typeLowercase(" ");
        if (!voiceCheck(result, "chipUndoNotImmediatelyRecorrected", awaitFixtureEquals("seed teh receive because teh "))) return;
        typeLowercase("teh ");
        String firstExpected = "seed teh receive because teh the ";
        if (!voiceCheck(result, "laterCorrectionExact", awaitFixtureEquals(firstExpected))) return;

        runOnMainSync(() -> {
            host.second.setText("other ");
            host.second.setSelection(host.second.length());
            host.focus(host.second);
        });
        SystemClock.sleep(600);
        if (!voiceCheck(result, "newEditorCollapsed", collapsed() && suggestionsHidden() && !hasStatus("Undo autocorrect"))) return;
        tap(locate("Show keyboard")); SystemClock.sleep(400);
        if (!voiceCheck(result, "newEditorEmptySuggestionsHidden", awaitDescription("q") && suggestionsHidden())) return;
        tap(locate("Backspace"));
        if (!voiceCheck(result, "newEditorUndoCleared", awaitFixtureEquals("other"))) return;
        final boolean[] firstUnchanged = {false};
        runOnMainSync(() -> firstUnchanged[0] = firstExpected.contentEquals(host.first.getText()));
        if (!voiceCheck(result, "previousEditorUnchanged", firstUnchanged[0])) return;
        runOnMainSync(() -> host.focus(host.first));
        SystemClock.sleep(600);
        if (!voiceCheck(result, "returnedEditorCollapsed", collapsed() && suggestionsHidden())) return;
        tap(locate("Show keyboard")); SystemClock.sleep(400);
        if (!voiceCheck(result, "returnedEditorUndoAbsent", awaitDescription("q") && !hasStatus("Undo autocorrect"))) return;
        tap(locate("Backspace"));
        if (!voiceCheck(result, "returnedEditorUndoCleared", awaitFixtureEquals("seed teh receive because teh the"))) return;
        if (!noSubmission(result, "normalNoSubmissionAndBalanced")) return;

        int guardedProfiles = 0;
        for (String profile : new String[]{"password", "email", "uri", "noSuggestions", "noLearning"}) {
            runOnMainSync(() -> host.prepareCorrectionProfile(profile));
            SystemClock.sleep(600);
            // This changes flags on the already-focused field (restartInput), not editor focus.
            // It may retain expansion; exclusions must apply before we explicitly collapse it.
            if (!voiceCheck(result, profile + "ExcludesSuggestionsImmediately", suggestionsHidden())) return;
            if (hasStatus("Hide keyboard")) { tap(locate("Hide keyboard")); SystemClock.sleep(400); }
            if (!voiceCheck(result, profile + "Collapsed", collapsed() && suggestionsHidden())) return;
            tap(locate("Show keyboard")); SystemClock.sleep(400);
            if (!voiceCheck(result, profile + "Expanded", awaitDescription("q"))) return;
            typeLowercase("teh ");
            runOnMainSync(() -> {
                voiceCount(result, profile + "Length", host.active.length());
                voiceCount(result, profile + "Cursor", host.active.getSelectionStart());
                voiceCheck(result, profile + "SeedRetained", host.active.getText().toString().startsWith("seed "));
            });
            if (!voiceCheck(result, profile + "Unchanged", awaitFixtureEquals("seed teh "))) return;
            if (!voiceCheck(result, profile + "SuggestionsHidden", suggestionsHidden() && !hasStatus("Undo autocorrect"))) return;
            if (!noSubmission(result, profile + "NoSubmissionAndBalanced")) return;
            guardedProfiles++;
        }
        voiceCount(result, "guardedProfiles", guardedProfiles);
        // Leave a normal generated field and the compact voice bar, with no saved test text.
        runOnMainSync(() -> host.prepareCorrectionProfile("normal"));
        SystemClock.sleep(600);
        if (hasStatus("Hide keyboard")) { tap(locate("Hide keyboard")); SystemClock.sleep(400); }
        if (!voiceCheck(result, "normalProfileRestored", collapsed() && suggestionsHidden() && fixtureEquals("seed "))) return;
        result.putBoolean("passed", true);
        Log.i(TrialActivity.TAG, result.getString("summary"));
    }

    private void typeLowercase(String value) {
        for (int i = 0; i < value.length(); i++) {
            char key = value.charAt(i);
            if (key != ' ' && (key < 'a' || key > 'z')) throw new IllegalArgumentException();
            tap(locate(key == ' ' ? "Space" : String.valueOf(key)));
        }
    }

    private boolean suggestionsHidden() {
        return !hasStatus("Spelling suggestions") && imeDescriptions("Suggestion ", true).isEmpty();
    }

    private boolean awaitFixtureEquals(String expected) {
        long deadline = SystemClock.uptimeMillis() + 3000;
        do {
            if (fixtureEquals(expected)) return true;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private boolean noSubmission(Bundle result, String name) {
        final boolean[] safe = {false};
        runOnMainSync(() -> safe[0] = host.editorActionCalls == 0 && host.enterKeyEvents == 0
            && host.probe.depth == 0 && host.probe.unbalanced == 0);
        return voiceCheck(result, name, safe[0]);
    }

    /** Only generated host text and this package's IME window are observed. No voice/provider action. */
    private void voicefirst(Bundle result) {
        result.putString("summary", "scenario=voicefirst hostTextExported=false");
        result.putBoolean("passed", false);
        // Establish the correct active IME first: the original full keyboard must fail the next
        // behavioral assertion, rather than abort while trying to locate a new control.
        locate("Hold to speak. Double tap for voice settings.");
        if (!voiceCheck(result, "initialCollapsed", collapsed())) return;
        if (!voiceCheck(result, "initialFixtureExact", fixtureEquals("seed "))) return;
        tap(locate("Show keyboard"));
        if (!voiceCheck(result, "expanded", awaitDescription("q") && hasStatus("Hide keyboard") && hasStatus("123"))) return;
        tap(locate("Emoji"));
        if (!voiceCheck(result, "emojiOpened", awaitDescription("Emoji list") && !hasStatus("q"))) return;

        revealCategory("Emoji category Smileys");
        tap(locate("Emoji category Smileys"));
        tap(emojiTarget("😀"));
        if (!voiceCheck(result, "firstEmojiExact", fixtureEquals("seed 😀"))) return;
        if (!voiceCheck(result, "panelAfterFirstEmoji", hasStatus("Emoji list") && !hasStatus("q"))) return;
        tap(emojiTarget("😃"));
        if (!voiceCheck(result, "secondEmojiExact", fixtureEquals("seed 😀😃"))) return;
        if (!voiceCheck(result, "panelAfterSecondEmoji", hasStatus("Emoji list") && !hasStatus("q"))) return;

        Set<String> categories = imeDescriptions("Emoji category ", false);
        categories.remove("Emoji category Recent");
        voiceCount(result, "categoryCount", categories.size());
        if (!voiceCheck(result, "categoryChoices", categories.size() >= 5)) return;
        int minimumChoices = Integer.MAX_VALUE;
        int scrollablePages = 0;
        int categoryNumber = 0;
        for (String category : categories) {
            categoryNumber++;
            revealCategory(category);
            tap(locate(category));
            Set<String> all = emojiDescriptions(false);
            minimumChoices = Math.min(minimumChoices, all.size());
            if (!voiceCheck(result, "richCategory" + categoryNumber, all.size() >= 32)) return;
            Set<String> before = emojiDescriptions(true);
            if (all.size() > before.size()) {
                boolean scrolled = imeAction("Emoji list", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                SystemClock.sleep(250);
                Set<String> after = emojiDescriptions(true);
                if (!voiceCheck(result, "categoryScroll" + categoryNumber, scrolled && !after.equals(before))) return;
                scrollablePages++;
            }
        }
        voiceCount(result, "minimumCategoryChoices", minimumChoices);
        voiceCount(result, "scrolledPages", scrollablePages);
        if (!voiceCheck(result, "richScrollableCatalog", scrollablePages > 0)) return;
        tap(locate("Emoji"));
        if (!voiceCheck(result, "emojiClosed", awaitDescription("q") && !hasStatus("Emoji list"))) return;

        tap(locate("123"));
        if (!voiceCheck(result, "doubleQuoteAvailable", awaitDescription("\""))) return;
        String punctuation = "\"'.,?!:;()$&@-/";
        for (int i = 0; i < punctuation.length(); i++) tap(locate(punctuation.substring(i, i + 1)));
        String expected = "seed 😀😃" + punctuation;
        voiceCount(result, "punctuationInserted", punctuation.length());
        if (!voiceCheck(result, "punctuationExact", fixtureEquals(expected))) return;
        tap(locate("ABC"));
        if (!voiceCheck(result, "lettersRestored", awaitDescription("q"))) return;
        tap(locate("Hide keyboard"));
        SystemClock.sleep(400);
        if (!voiceCheck(result, "collapsedAgain", collapsed())) return;
        tap(locate("Show keyboard"));
        if (!voiceCheck(result, "reexpanded", awaitDescription("q") && hasStatus("Hide keyboard"))) return;
        if (!voiceCheck(result, "fixturePreserved", fixtureEquals(expected))) return;
        // A newly focused editor starts with voice controls even after the previous editor expanded.
        runOnMainSync(() -> host.focus(host.second));
        SystemClock.sleep(600);
        if (!voiceCheck(result, "newEditorCollapsed", collapsed())) return;
        runOnMainSync(() -> host.focus(host.first));
        SystemClock.sleep(600);
        if (!voiceCheck(result, "returnedEditorCollapsed", collapsed() && fixtureEquals(expected))) return;
        final boolean[] safe = {false};
        runOnMainSync(() -> {
            voiceCount(result, "editorActionCalls", host.editorActionCalls);
            voiceCount(result, "enterKeyEvents", host.enterKeyEvents);
            safe[0] = host.editorActionCalls == 0 && host.enterKeyEvents == 0
                && host.second.length() == 0 && host.probe.depth == 0 && host.probe.unbalanced == 0;
        });
        if (!voiceCheck(result, "noSubmissionAndBalanced", safe[0])) return;
        result.putBoolean("passed", true);
        Log.i(TrialActivity.TAG, result.getString("summary"));
    }

    private boolean voiceCheck(Bundle result, String name, boolean passed) {
        result.putString("summary", result.getString("summary") + " " + name + "=" + passed);
        if (!passed) Log.i(TrialActivity.TAG, result.getString("summary"));
        return passed;
    }

    private void voiceCount(Bundle result, String name, int count) {
        result.putString("summary", result.getString("summary") + " " + name + "=" + count);
    }

    private boolean collapsed() {
        return hasStatus("Show keyboard") && !hasStatus("Hide keyboard") && !hasStatus("q")
            && !hasStatus("Q") && !hasStatus("Space") && !hasStatus("123") && !hasStatus("Emoji list");
    }

    private boolean fixtureEquals(String expected) {
        final boolean[] exact = {false};
        runOnMainSync(() -> exact[0] = expected.contentEquals(host.active.getText())
            && host.active.getSelectionStart() == expected.length() && host.active.getSelectionEnd() == expected.length());
        return exact[0];
    }

    private boolean awaitDescription(String description) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            if (hasStatus(description)) return true;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private Rect emojiTarget(String emoji) {
        String plain = "Emoji " + emoji + ". Long press to pin.";
        return locate(hasStatus(plain) ? plain : "Emoji " + emoji + ", pinned. Long press to unpin.");
    }

    private void revealCategory(String description) {
        // Android scrolls only the IME's ancestors of this label; no host/application tree is read.
        imeAction(description, AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        SystemClock.sleep(200);
        locate(description);
    }

    private Set<String> emojiDescriptions(boolean visibleOnly) {
        Set<String> descriptions = imeDescriptions("Emoji ", visibleOnly);
        descriptions.removeIf(value -> !value.endsWith(". Long press to pin.")
            && !value.endsWith(", pinned. Long press to unpin."));
        return descriptions;
    }

    private Set<String> imeDescriptions(String prefix, boolean visibleOnly) {
        Set<String> result = new LinkedHashSet<>();
        List<AccessibilityNodeInfo> nodes = imeNodes(prefix, false);
        try {
            for (AccessibilityNodeInfo node : nodes) {
                if (!visibleOnly || node.isVisibleToUser()) result.add(node.getContentDescription().toString());
            }
        } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
        return result;
    }

    private boolean imeAction(String description, int action) {
        List<AccessibilityNodeInfo> nodes = imeNodes(description, true);
        try {
            if (nodes.size() != 1) throw new IllegalStateException();
            final boolean[] focused = {false};
            runOnMainSync(() -> focused[0] = host.hasWindowFocus() && host.active.hasFocus());
            if (!focused[0]) throw new IllegalStateException();
            return nodes.get(0).performAction(action);
        } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
    }

    private List<AccessibilityNodeInfo> imeNodes(String description, boolean exact) {
        List<AccessibilityNodeInfo> matches = new ArrayList<>();
        int[] remaining = {4096};
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            try {
                if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) {
                    try {
                        if (imePackage.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                            collectNodes(root, description, exact, matches, remaining);
                        }
                    } finally { root.recycle(); }
                }
            } finally { window.recycle(); }
        }
        return matches;
    }

    private void collectNodes(AccessibilityNodeInfo node, String description, boolean exact,
                              List<AccessibilityNodeInfo> matches, int[] remaining) {
        if (--remaining[0] < 0) throw new IllegalStateException();
        String value = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        if (exact ? value.equals(description) : value.startsWith(description)) matches.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try { collectNodes(child, description, exact, matches, remaining); }
                finally { child.recycle(); }
            }
        }
    }

    /** Empty fixture only: Polish refuses before any credential/provider access. No host tree/text reads. */
    private void status(Bundle result) {
        String warning = "Nothing to polish. Type some text first.";
        List<Rect> baseline = chrome();
        tap(locate("Polish all text in this field"));
        Rect status = locate(warning);
        if (!baseline.equals(chrome())) throw new AssertionError();
        tap(status); // Status must be inert, not an expand/collapse action.
        if (!baseline.equals(chrome())) throw new AssertionError();
        SystemClock.sleep(1000);
        tap(locate("Polish all text in this field")); // Replacement gets a fresh deadline.
        SystemClock.sleep(1800);
        locate(warning);
        if (!baseline.equals(chrome())) throw new AssertionError();
        SystemClock.sleep(1600);
        if (hasStatus(warning) || !baseline.equals(chrome())) throw new AssertionError();
        runOnMainSync(() -> host.ime().hideSoftInputFromWindow(host.active.getWindowToken(), 0));
        SystemClock.sleep(400);
        runOnMainSync(() -> host.focus(host.active));
        SystemClock.sleep(600);
        expandKeyboardIfCollapsed();
        if (hasStatus(warning) || !baseline.equals(chrome())) throw new AssertionError();
        result.putBoolean("passed", true);
        result.putString("summary", "statusGeometry=true inertTap=true replacementDeadline=true expiry=true reopen=true providerCalls=0 hostTextCollected=false");
    }

    private List<Rect> chrome() {
        List<Rect> bounds = new ArrayList<>();
        for (String description : new String[]{"Hide keyboard", "Hold to speak. Double tap for voice settings.", "Polish all text in this field"}) bounds.add(locate(description));
        return bounds;
    }

    private void tap(Rect target) {
        long down = SystemClock.uptimeMillis();
        event(down, down, MotionEvent.ACTION_DOWN, new int[]{0}, new Rect[]{target});
        event(down, down + 40, MotionEvent.ACTION_UP, new int[]{0}, new Rect[]{target});
        SystemClock.sleep(100);
    }

    private boolean hasStatus(String description) {
        List<Rect> matches = new ArrayList<>();
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            try {
                if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) {
                    try { if (imePackage.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) collect(root, description, matches); }
                    finally { root.recycle(); }
                }
            } finally { window.recycle(); }
        }
        return !matches.isEmpty();
    }

    private void hold(Bundle result) {
        Rect key = locate("Backspace");
        runOnMainSync(() -> host.probe.start());
        long down = SystemClock.uptimeMillis();
        boolean released = false;
        try {
            event(down, down, MotionEvent.ACTION_DOWN, new int[]{0}, new Rect[]{key});
            for (int i=1;i<40;i++) event(down, down+i*100L, MotionEvent.ACTION_MOVE, new int[]{0}, new Rect[]{key});
            event(down, down+4000, MotionEvent.ACTION_UP, new int[]{0}, new Rect[]{key});
            released=true;
        } finally { if (!released) cancel(down, new int[]{0}, new Rect[]{key}); }
        SystemClock.sleep(500);
        runOnMainSync(() -> {
            String actual = host.active.getText().toString();
            String expected = host.seed.substring(0, Math.min(actual.length(),host.seed.length()));
            boolean exact = expected.equals(actual) && host.active.getSelectionStart()==actual.length()
                && host.active.getSelectionEnd()==actual.length();
            String summary = host.report("hold",exact,expected.length()) + " oracle=remainingPrefix progress=" + (actual.length()<host.seed.length()) + injectionSummary();
            result.putBoolean("passed", exact && host.probe.changes > 10 && SystemClock.uptimeMillis()-host.probe.lastProgress < 900 && host.probe.depth == 0 && host.probe.unbalanced == 0);
            Log.i(TrialActivity.TAG,summary);
            result.putString("summary",summary);
        });
    }

    private void typing(Bundle result) {
        String sequence = "fjdk";
        Rect[] keys = new Rect[4];
        for (int i=0;i<4;i++) keys[i]=locate(sequence.substring(i,i+1));
        runOnMainSync(() -> host.probe.start());
        StringBuilder expected = new StringBuilder("seed ");
        for (int i=0;i<20;i++) {
            int k=(i%2)*2;
            expected.append(sequence.charAt(k)).append(sequence.charAt(k+1));
            pair(keys[k],keys[k+1],i%2==0);
        }
        SystemClock.sleep(700);
        runOnMainSync(() -> {
            String actual = host.active.getText().toString();
            boolean exact = expected.toString().equals(actual) && host.active.getSelectionStart()==expected.length()
                && host.active.getSelectionEnd()==expected.length();
            String summary=host.report("typing",exact,expected.length()) + " oracle=fixedSequence" + injectionSummary();
            result.putBoolean("passed", exact && host.probe.depth == 0 && host.probe.unbalanced == 0);
            Log.i(TrialActivity.TAG,summary);
            result.putString("summary",summary);
        });
    }

    private void boundary(Bundle result) {
        Rect left = locate("q");
        Rect right = locate("w");
        int x = (left.right + right.left) / 2;
        tap(new Rect(x, Math.max(left.top, right.top), x + 2, Math.min(left.bottom, right.bottom)));
        SystemClock.sleep(300);
        runOnMainSync(() -> {
            String actual = host.active.getText().toString();
            boolean accepted = actual.equals("seed q") || actual.equals("seed w");
            String summary = "scenario=boundary firstKeyWidthPx=" + left.width() + " adjacentBoundsGapPx=" + (right.left - left.right)
                + " adjacentCharacterAccepted=" + accepted + " addedUnits=" + (actual.length() - 5) + " hostTextCollected=false";
            Log.i(TrialActivity.TAG, summary);
            result.putString("summary", summary);
            result.putBoolean("passed", accepted);
        });
    }

    private void targets(Bundle result) {
        int spacing = Integer.parseInt(arguments.getString("keyGapDp", "2"));
        if (spacing < 0 || spacing > 2) throw new IllegalArgumentException();
        Rect left = locate("q");
        Rect right = locate("w");
        float density = getTargetContext().getResources().getDisplayMetrics().density;
        int expected = spacing == 0 ? 0 : Math.max(1, (int) (spacing * density)) * 2;
        int actual = right.left - left.right;
        boolean passed = left.width() > 0 && Math.abs(left.width() - right.width()) <= 1 && actual == expected;
        String summary = "scenario=targets expectedSpacingDp=" + spacing + " sampleKeyWidthPx=" + left.width()
            + " adjacentGapPx=" + actual + " expectedGapPx=" + expected + " density=" + density
            + " passed=" + passed + " hostTextCollected=false";
        Log.i(TrialActivity.TAG, summary);
        result.putString("summary", summary);
        result.putBoolean("passed", passed);
    }

    private void geometry(Bundle result) {
        int minimum = Integer.MAX_VALUE;
        int maximum = 0;
        for (char letter : "qwertyuiopasdfghjklzxcvbnm".toCharArray()) {
            Rect key = locate(String.valueOf(letter));
            minimum = Math.min(minimum, key.width());
            maximum = Math.max(maximum, key.width());
        }
        List<Rect> spaces = locateAll("Space");
        spaces.sort((a, b) -> Integer.compare(a.left, b.left));
        Rect left = locate("t");
        Rect right = locate("y");
        float density = getTargetContext().getResources().getDisplayMetrics().density;
        boolean portrait = "portrait".equals(arguments.getString("orientation"));
        float gapDp = (right.left - left.right) / density;
        boolean equalSpaces = spaces.size() == 2 && Math.abs(spaces.get(0).width() - spaces.get(1).width()) <= 1;
        boolean orientationMatches = getTargetContext().getResources().getConfiguration().orientation == (portrait
            ? android.content.res.Configuration.ORIENTATION_PORTRAIT : android.content.res.Configuration.ORIENTATION_LANDSCAPE);
        int leftInset = locate("1").left;
        int rightInset = getTargetContext().getResources().getDisplayMetrics().widthPixels - locate("0").right;
        boolean mirroredInsets = Math.abs(leftInset - rightInset) <= 1 && leftInset / density >= 60;
        boolean passed = orientationMatches && mirroredInsets && maximum - minimum <= 1 && equalSpaces && (portrait ? gapDp <= 69 : gapDp >= 374 && gapDp <= 381);
        String summary = "scenario=geometry orientation=" + (portrait ? "portrait" : "landscape")
            + " letterMinPx=" + minimum + " letterMaxPx=" + maximum + " density=" + density
            + " centerCapGapDp=" + gapDp + " leftInsetDp=" + leftInset / density + " rightInsetDp=" + rightInset / density
            + " mirroredInsets=" + mirroredInsets + " spaces=" + spaces.size()
            + " leftSpacePx=" + (spaces.isEmpty() ? 0 : spaces.get(0).width())
            + " rightSpacePx=" + (spaces.size() < 2 ? 0 : spaces.get(1).width())
            + " equalSpaces=" + equalSpaces + " passed=" + passed + " hostTextCollected=false";
        Log.i(TrialActivity.TAG, summary);
        result.putString("summary", summary);
        result.putBoolean("passed", passed);
    }

    private List<Rect> locateAll(String description) {
        List<Rect> matches = new ArrayList<>();
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            try {
                if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) {
                    try { if (imePackage.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) collect(root, description, matches); }
                    finally { root.recycle(); }
                }
            } finally { window.recycle(); }
        }
        return matches;
    }

    private String injectionSummary() { return " profile="+(host.transientReads ? "transient" : "normal")+" displayRefreshHz="+host.getWindowManager().getDefaultDisplay().getRefreshRate()+" injectedEvents="+injected+" maxInjectionLatenessMs="+maxLateness; }

    private void pair(Rect left, Rect right, boolean firstUp) {
        long down=SystemClock.uptimeMillis();
        int[] ids={0}; Rect[] points={left};
        boolean released=false;
        try {
            event(down,down,MotionEvent.ACTION_DOWN,ids,points);
            ids=new int[]{0,1}; points=new Rect[]{left,right};
            event(down,down+35,MotionEvent.ACTION_POINTER_DOWN | (1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),ids,points);
            event(down,down+55,MotionEvent.ACTION_MOVE,ids,points);
            event(down,down+75,MotionEvent.ACTION_POINTER_UP | ((firstUp ? 0 : 1)<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),ids,points);
            ids=new int[]{firstUp ? 1 : 0}; points=new Rect[]{firstUp ? right : left};
            event(down,down+100,MotionEvent.ACTION_UP,ids,points);
            released=true;
            SystemClock.sleep(20);
        } finally { if (!released) cancel(down,ids,points); }
    }

    private void event(long down,long scheduled,int action,int[] ids,Rect[] points) {
        long remaining=scheduled-SystemClock.uptimeMillis();
        if (remaining>0) SystemClock.sleep(remaining);
        boolean[] focused={false};
        runOnMainSync(() -> focused[0]=host.hasWindowFocus() && host.active.hasFocus());
        if (!focused[0]) throw new IllegalStateException();
        long now=SystemClock.uptimeMillis();
        maxLateness=Math.max(maxLateness,now-scheduled);
        int masked = action & MotionEvent.ACTION_MASK;
        if (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_POINTER_DOWN) runOnMainSync(() -> host.probe.input());
        inject(down,now,action,ids,points);
    }

    private void inject(long down,long now,int action,int[] ids,Rect[] points) {
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[ids.length];
        for (int i=0;i<ids.length;i++) {
            properties[i]=new MotionEvent.PointerProperties();
            properties[i].id=ids[i]; properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();
            coords[i].x=points[i].exactCenterX(); coords[i].y=points[i].exactCenterY();
            coords[i].pressure=1; coords[i].size=1;
        }
        MotionEvent event=MotionEvent.obtain(down,now,action,ids.length,properties,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try { if (!automation.injectInputEvent(event,true)) throw new IllegalStateException(); injected++; }
        finally { event.recycle(); }
    }

    private void cancel(long down,int[] ids,Rect[] points) {
        try { inject(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,ids,points); } catch (RuntimeException ignored) { }
    }

    private Rect locate(String description) {
        long deadline=SystemClock.uptimeMillis()+5000;
        do {
            List<Rect> matches=new ArrayList<>();
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                try {
                    if (window.getType()!=AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                    AccessibilityNodeInfo root=window.getRoot();
                    if (root!=null) {
                        try { if (imePackage.contentEquals(root.getPackageName()==null ? "" : root.getPackageName())) collect(root,description,matches); }
                        finally { root.recycle(); }
                    }
                } finally { window.recycle(); }
            }
            if (matches.size()==1) return matches.get(0);
            if (matches.size()>1) throw new IllegalStateException();
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis()<deadline);
        throw new IllegalStateException();
    }

    private void collect(AccessibilityNodeInfo node,String description,List<Rect> matches) {
        if (node.isVisibleToUser() && description.contentEquals(node.getContentDescription()==null ? "" : node.getContentDescription())) {
            Rect bounds=new Rect(); node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) matches.add(bounds);
        }
        for (int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            if (child!=null) { try { collect(child,description,matches); } finally { child.recycle(); } }
        }
    }
}
