package app.odicto.keyboardtrial;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Canvas;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.*;
import android.widget.*;
import java.util.function.Supplier;

public final class TrialActivity extends Activity {
    static final String TAG = "OdictoTypingTrial";
    final Probe probe = new Probe();
    TrialEdit first, second, active;
    TextView results;
    String seed = "";
    boolean transientReads;
    boolean readsUnavailable() {
        long elapsed = android.os.SystemClock.uptimeMillis()-probe.start;
        return transientReads && probe.running && elapsed >= 150 && elapsed < 750;
    }
    private boolean resumed;
    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        public void doFrame(long time) {
            probe.frame(time);
            if (resumed) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public void onCreate(Bundle state) {
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 24, 12, 8);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(12, insets.getSystemWindowInsetTop()+8, 12, insets.getSystemWindowInsetBottom()+8);
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("Generated fixture only · no submission · no persistence");
        root.addView(title);
        LinearLayout row = new LinearLayout(this);
        root.addView(row);
        button(row, "Seed", () -> prepare(generated()));
        button(row, "Clear/reset", () -> prepare(""));
        button(row, "Restart", () -> ime().restartInput(active));
        LinearLayout row2 = new LinearLayout(this);
        root.addView(row2);
        button(row2, "Switch", () -> focus(active == first ? second : first));
        button(row2, "Recreate", this::recreate);
        button(row2, "Report", () -> report("manual", seed.equals(active.getText().toString()), seed.length()));
        first = new TrialEdit(true);
        second = new TrialEdit(false);
        first.setHint("Multiline generated fixture");
        second.setHint("Single-line generated fixture");
        root.addView(first, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(second, new LinearLayout.LayoutParams(-1, dp(56)));
        results = new TextView(this);
        results.setTextSize(11);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(90)));
        setContentView(root);
        active = first;
        prepare(generated());
    }

    static String generated() {
        StringBuilder value = new StringBuilder();
        for (int i=0; i<160; i++) value.append("alpha bravo café e\u0301lan delta 👩🏽‍💻 東京 omega multiword fixture. ");
        return value.toString();
    }
    void button(LinearLayout row, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setOnClickListener(v -> action.run());
        row.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
    }
    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    InputMethodManager ime() { return getSystemService(InputMethodManager.class); }
    void focus(TrialEdit field) {
        active = field;
        field.requestFocus();
        ime().showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
    }
    void prepare(String text) {
        probe.running = false;
        seed = text;
        first.setText(""); second.setText("");
        active.setText(text);
        active.setSelection(active.length());
        focus(active);
        ime().restartInput(active);
        probe.start();
    }
    String report(String scenario, boolean exact, int expected) {
        String value = probe.finish(scenario, exact, expected, active.length());
        results.setText(value);
        Log.i(TAG, value);
        return value;
    }
    protected void onResume() { super.onResume(); resumed=true; Choreographer.getInstance().postFrameCallback(frame); }
    protected void onPause() { resumed=false; Choreographer.getInstance().removeFrameCallback(frame); super.onPause(); }
    protected void onSaveInstanceState(Bundle out) { }

    final class TrialEdit extends EditText {
        TrialEdit(boolean multiline) {
            super(TrialActivity.this);
            setSaveEnabled(false);
            setFreezesText(false);
            setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
                (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
            setSingleLine(!multiline);
            setImeOptions(EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            setOnFocusChangeListener((v, focused) -> { if (focused) active=this; });
            addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
                public void onTextChanged(CharSequence s,int start,int before,int count) { probe.change(before,count); }
                public void afterTextChanged(Editable e) { }
            });
        }
        protected void onDraw(Canvas canvas) { super.onDraw(canvas); if (this == active) probe.draw(); }
        public InputConnection onCreateInputConnection(EditorInfo info) {
            InputConnection connection = super.onCreateInputConnection(info);
            return connection == null ? null : new MeasuredConnection(connection);
        }
    }

    final class MeasuredConnection extends InputConnectionWrapper {
        MeasuredConnection(InputConnection target) { super(target, false); }
        <T> T timed(Probe.Series series, Supplier<T> call) {
            boolean record = probe.running;
            long start = System.nanoTime();
            try { return call.get(); } finally { if (record) series.add(System.nanoTime()-start); }
        }
        public boolean commitText(CharSequence t,int p) { return timed(probe.apply, () -> super.commitText(t,p)); }
        public boolean commitText(CharSequence t,int p,TextAttribute a) { return timed(probe.apply, () -> super.commitText(t,p,a)); }
        public boolean setComposingText(CharSequence t,int p) { return timed(probe.apply, () -> super.setComposingText(t,p)); }
        public boolean setComposingText(CharSequence t,int p,TextAttribute a) { return timed(probe.apply, () -> super.setComposingText(t,p,a)); }
        public boolean setComposingRegion(int s,int e) { return timed(probe.apply, () -> super.setComposingRegion(s,e)); }
        public boolean finishComposingText() { return timed(probe.apply, () -> super.finishComposingText()); }
        public boolean deleteSurroundingText(int b,int a) { return timed(probe.apply, () -> super.deleteSurroundingText(b,a)); }
        public boolean deleteSurroundingTextInCodePoints(int b,int a) { return timed(probe.apply, () -> super.deleteSurroundingTextInCodePoints(b,a)); }
        public boolean setSelection(int s,int e) { return timed(probe.apply, () -> super.setSelection(s,e)); }
        public boolean sendKeyEvent(KeyEvent e) { return timed(probe.apply, () -> super.sendKeyEvent(e)); }
        public CharSequence getTextBeforeCursor(int n,int f) { return timed(probe.read, () -> readsUnavailable() ? null : super.getTextBeforeCursor(n,f)); }
        public CharSequence getTextAfterCursor(int n,int f) { return timed(probe.read, () -> readsUnavailable() ? null : super.getTextAfterCursor(n,f)); }
        public CharSequence getSelectedText(int f) { return timed(probe.read, () -> readsUnavailable() ? null : super.getSelectedText(f)); }
        public SurroundingText getSurroundingText(int b,int a,int f) { return timed(probe.read, () -> readsUnavailable() ? null : super.getSurroundingText(b,a,f)); }
        public ExtractedText getExtractedText(ExtractedTextRequest r,int f) { return timed(probe.read, () -> readsUnavailable() ? null : super.getExtractedText(r,f)); }
        public int getCursorCapsMode(int modes) { return timed(probe.read, () -> super.getCursorCapsMode(modes)); }
        public boolean beginBatchEdit() {
            boolean result = timed(probe.batch, () -> super.beginBatchEdit());
            if (probe.running && result) {
                if (probe.depth++ == 0) probe.batchStart=System.nanoTime();
                probe.maxDepth=Math.max(probe.maxDepth,probe.depth);
            }
            return result;
        }
        public boolean endBatchEdit() {
            try { return timed(probe.batch, () -> super.endBatchEdit()); }
            finally {
                if (probe.running) {
                    if (probe.depth == 0) probe.unbalanced++;
                    else if (--probe.depth == 0) probe.lifetime.add(System.nanoTime()-probe.batchStart);
                }
            }
        }
    }
}
