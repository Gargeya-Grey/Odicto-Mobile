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
import java.util.List;

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
            if (!imePackage.startsWith("app.odicto." ) || imePackage.equals("app.odicto.keyboardtrial")) throw new IllegalArgumentException();
            String scenario = arguments.getString("scenario", "hold");
            if (!scenario.equals("hold") && !scenario.equals("typing") && !scenario.equals("status")) throw new IllegalArgumentException();
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            Intent intent = new Intent(getTargetContext(), TrialActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            host = (TrialActivity) startActivitySync(intent);
            runOnMainSync(() -> {
                host.prepare(scenario.equals("status") ? "" : scenario.equals("hold") ? TrialActivity.generated() : "seed ");
                host.transientReads = "transient".equals(arguments.getString("profile", "normal"));
            });
            SystemClock.sleep(1000);
            if (scenario.equals("status")) status(result); else if (scenario.equals("hold")) hold(result); else typing(result);
            boolean passed = result.getBoolean("passed", false);
            result.putString("status", passed ? "passed" : "failed");
            finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        } catch (Throwable failure) {
            String diagnostic = "status=aborted errorType=" + failure.getClass().getSimpleName();
            Log.e(TrialActivity.TAG, diagnostic);
            result.putString("summary", diagnostic);
            finish(Activity.RESULT_CANCELED, result);
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
        if (hasStatus(warning) || !baseline.equals(chrome())) throw new AssertionError();
        // Visit right/left/floating/docked, restoring the starting mode even on failure.
        int cycles = 0;
        try {
            for (; cycles < 4;) {
                tap(locate("One-handed or floating keyboard")); cycles++;
                SystemClock.sleep(250);
                List<Rect> compact = chrome();
                tap(locate("Polish all text in this field")); locate(warning);
                if (!compact.equals(chrome())) throw new AssertionError();
                SystemClock.sleep(3300);
                if (hasStatus(warning) || !compact.equals(chrome())) throw new AssertionError();
            }
        } finally {
            while (cycles > 0 && cycles < 4) { tap(locate("One-handed or floating keyboard")); cycles++; SystemClock.sleep(250); }
        }
        result.putBoolean("passed", true);
        result.putString("summary", "statusGeometry=true inertTap=true replacementDeadline=true expiry=true reopen=true fourLayouts=true providerCalls=0 hostTextCollected=false");
    }

    private List<Rect> chrome() {
        List<Rect> bounds = new ArrayList<>();
        for (String description : new String[]{"Open Odicto settings", "Raw transcription with Groq", "AI answer", "Live transcription", "Hold to speak. Double tap for voice settings.", "Polish all text in this field", "One-handed or floating keyboard"}) bounds.add(locate(description));
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
