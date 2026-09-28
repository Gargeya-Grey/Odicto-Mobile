package app.odicto.keyboardtrial;

import android.os.SystemClock;
import java.util.Arrays;
import java.util.Locale;

final class Probe {
    final Series apply = new Series(), read = new Series(), batch = new Series(), lifetime = new Series();
    final Series deletionIntervals = new Series(), changeToDraw = new Series();
    final Series inputToChange = new Series(), inputToDraw = new Series();
    private final java.util.ArrayDeque<Long> inputs = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Long> drawsPending = new java.util.ArrayDeque<>();
    private long lastDeletion, pendingDraw;
    boolean running;
    long start, lastProgress, longestGap, frames, draws, changes, deleted, lastFrame, frameGap;
    int depth, maxDepth, unbalanced;
    long batchStart;

    void start() {
        apply.reset(); read.reset(); batch.reset(); lifetime.reset();
        deletionIntervals.reset(); changeToDraw.reset();
        inputToChange.reset(); inputToDraw.reset(); inputs.clear(); drawsPending.clear();
        lastDeletion = pendingDraw = 0;
        frames = draws = changes = deleted = longestGap = frameGap = lastFrame = 0;
        depth = maxDepth = unbalanced = 0;
        start = lastProgress = SystemClock.uptimeMillis();
        running = true;
    }

    void input() {
        if (running && inputs.size() < 8192) inputs.addLast(System.nanoTime());
    }

    void change(int before, int after) {
        if (!running) return;
        changes++;
        Long input = inputs.pollFirst();
        if (input != null) {
            inputToChange.add(System.nanoTime()-input);
            if (drawsPending.size() < 8192) drawsPending.addLast(input);
        }
        if (pendingDraw == 0) pendingDraw = System.nanoTime();
        if (before > after) {
            long now = SystemClock.uptimeMillis();
            if (lastDeletion != 0) deletionIntervals.add((now-lastDeletion)*1000000);
            lastDeletion = now;
            longestGap = Math.max(longestGap, now - lastProgress);
            lastProgress = now;
            deleted += before - after;
        }
    }

    void draw() {
        if (!running) return;
        draws++;
        long now = System.nanoTime();
        while (!drawsPending.isEmpty()) inputToDraw.add(now-drawsPending.removeFirst());
        if (pendingDraw != 0) {
            changeToDraw.add(System.nanoTime()-pendingDraw);
            pendingDraw = 0;
        }
    }

    void frame(long time) {
        if (!running) return;
        frames++;
        if (lastFrame != 0) frameGap = Math.max(frameGap, time - lastFrame);
        lastFrame = time;
    }

    String finish(String scenario, boolean exact, int expected, int actual) {
        long end = SystemClock.uptimeMillis();
        running = false;
        return String.format(Locale.US,
            "scenario=%s exactMatch=%s expectedUnits=%d actualUnits=%d elapsedMs=%d deletedUnits=%d changes=%d longestDeletionGapMs=%d terminalDeletionGapMs=%d frameOpportunities=%d maxFrameGapMs=%.3f draws=%d batchDepth=%d maxBatchDepth=%d unbalancedEnds=%d %s %s %s %s",
            scenario, exact, expected, actual, end-start, deleted, changes,
            Math.max(longestGap, end-lastProgress), end-lastProgress, frames, frameGap/1000000.0, draws,
            depth, maxDepth, unbalanced, apply.summary("apply"), read.summary("read"), batch.summary("batch"), lifetime.summary("batchLifetime")) + " " + deletionIntervals.summary("deleteInterval") + " " + changeToDraw.summary("changeToDraw") + " " + inputToChange.summary("inputToChange") + " " + inputToDraw.summary("inputToDraw");
    }

    static final class Series {
        final long[] samples = new long[8192];
        long count, total, max;
        void reset() { count = total = max = 0; }
        void add(long nanos) {
            if (count < samples.length) samples[(int) count] = nanos;
            count++; total += nanos; max = Math.max(max, nanos);
        }
        String summary(String name) {
            int n = (int) Math.min(count, samples.length);
            long[] sorted = Arrays.copyOf(samples, n);
            Arrays.sort(sorted);
            return String.format(Locale.US, "%sCount=%d %sSampled=%d %sTotalMs=%.3f %sP50Ms=%.3f %sP95Ms=%.3f %sP99Ms=%.3f %sMaxMs=%.3f",
                name,count,name,n,name,total/1e6,name,percentile(sorted,.50)/1e6,
                name,percentile(sorted,.95)/1e6,name,percentile(sorted,.99)/1e6,name,max/1e6);
        }
        long percentile(long[] values, double p) { return values.length == 0 ? 0 : values[(int)Math.ceil(values.length*p)-1]; }
    }
}
