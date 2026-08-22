package com.winlator.renderer;

import android.opengl.GLES30;
import android.util.Log;

/**
 * VESSEL: what a pass actually costs on the GPU, rather than what it ought to.
 *
 * <p>This exists because six consecutive theories about frame generation were
 * wrong, and the one that settled it was a measurement. `docs/OPTIMIZATION.md`
 * already carries the rule -- a claim without a measurement is unmeasured -- and
 * nowhere does it bite harder than here: the arithmetic in a warp pass is
 * trivial and the cost is all bandwidth and render-target switches, so reasoning
 * about the shader tells you almost nothing about the frame time.
 *
 * <p>{@code EXT_disjoint_timer_query} is what makes this possible at all.
 * Wall-clock around a {@code glDrawArrays} measures the time to *submit* work,
 * not to do it -- GL is asynchronous, so a pass that costs four milliseconds on
 * the GPU can return in twenty microseconds on the CPU. The extension puts the
 * timestamps in the command stream instead.
 *
 * <h2>Why the result is always a frame or more late</h2>
 *
 * <p>A query cannot be read back the moment it is issued without stalling the
 * pipeline, which would cost more than the thing being measured and change the
 * answer. So a result is collected on a later frame, when it is ready, and the
 * reported average lags reality by a frame or two. That is the right trade for
 * an instrument: it must not perturb what it observes.
 *
 * <p>Disjoint is checked and honoured. The GPU can be preempted or clocked
 * mid-query, and the extension signals that by setting {@code GPU_DISJOINT_EXT};
 * a sample taken across such an event is meaningless and is dropped rather than
 * averaged in.
 */
public class GpuTimer {
    private static final String TAG = "GpuTimer";
    private static final String EXTENSION = "GL_EXT_disjoint_timer_query";

    /** From EXT_disjoint_timer_query. Not in {@code android.opengl}. */
    private static final int TIME_ELAPSED_EXT = 0x88BF;
    private static final int GPU_DISJOINT_EXT = 0x8FBB;

    /**
     * Queries in flight before one is collected.
     *
     * <p>Three, because the GPU is typically one to two frames behind the CPU
     * and a result asked for sooner is not ready. Asking early is not an error --
     * {@code QUERY_RESULT_AVAILABLE} simply says no -- but a ring this size means
     * the answer is almost always waiting by the time its slot comes round again.
     */
    private static final int RING = 3;

    public static boolean isSupported() {
        final int generation = GLRenderer.contextGeneration();
        if (supportedGeneration != generation) {
            supportedGeneration = generation;
            supported = false;
            final String extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS);
            if (extensions != null) {
                for (String name : extensions.split(" ")) {
                    if (name.equals(EXTENSION)) { supported = true; break; }
                }
            }
        }
        return supported;
    }

    private static int supportedGeneration = -1;
    private static boolean supported = false;

    private final String label;
    private final int[] queries = new int[RING];
    private final boolean[] issued = new boolean[RING];
    private int slot = 0;
    private boolean open = false;
    private int generation = -1;

    private long samples = 0;
    private double totalMillis = 0;
    private double peakMillis = 0;
    private long reportedAt = 0;

    public GpuTimer(String label) {
        this.label = label;
    }

    /** Begin timing. Silently does nothing when the extension is absent. */
    public void begin() {
        if (!isSupported()) return;
        ensureQueries();
        // One TIME_ELAPSED query may be active at a time, per the extension, so a
        // caller that nests or forgets to end must not corrupt the ring.
        if (open) return;
        if (issued[slot]) collect(slot);
        GLES30.glBeginQuery(TIME_ELAPSED_EXT, queries[slot]);
        open = true;
    }

    public void end() {
        if (!open) return;
        GLES30.glEndQuery(TIME_ELAPSED_EXT);
        issued[slot] = true;
        open = false;
        slot = (slot + 1) % RING;
    }

    private void ensureQueries() {
        final int current = GLRenderer.contextGeneration();
        if (generation == current && queries[0] != 0) return;
        // A new context invalidates every query name; the old ones went with it.
        generation = current;
        java.util.Arrays.fill(issued, false);
        slot = 0;
        open = false;
        GLES30.glGenQueries(RING, queries, 0);
    }

    /**
     * Read one finished query, if it has finished, and fold it into the average.
     *
     * <p>Nothing blocks here. An unavailable result leaves the slot issued and is
     * picked up on a later pass, which is what keeps the instrument from becoming
     * the bottleneck it is measuring.
     */
    private void collect(int index) {
        final int[] available = new int[1];
        GLES30.glGetQueryObjectuiv(queries[index], GLES30.GL_QUERY_RESULT_AVAILABLE, available, 0);
        if (available[0] == 0) return;

        final int[] disjoint = new int[1];
        GLES30.glGetIntegerv(GPU_DISJOINT_EXT, disjoint, 0);

        final int[] nanos = new int[1];
        GLES30.glGetQueryObjectuiv(queries[index], GLES30.GL_QUERY_RESULT, nanos, 0);
        issued[index] = false;

        // Dropped, not averaged. A query spanning a preemption or a clock change
        // measures the interruption rather than the work.
        if (disjoint[0] != 0) return;

        final double millis = (nanos[0] & 0xffffffffL) / 1e6;
        samples++;
        totalMillis += millis;
        if (millis > peakMillis) peakMillis = millis;
    }

    /**
     * Log the average and peak, at most once a second, and start a fresh window.
     *
     * <p>A window rather than a lifetime average: what matters is what the cost is
     * *now*, at this resolution with this content, and a mean over the whole
     * session hides the moment a pass became expensive.
     */
    public void report(long nowMillis) {
        if (samples == 0) return;
        if (nowMillis - reportedAt < 1000) return;
        reportedAt = nowMillis;
        Log.i(TAG, String.format("%-22s mean %6.3f ms   peak %6.3f ms   over %d samples",
                                 label, totalMillis / samples, peakMillis, samples));
        samples = 0;
        totalMillis = 0;
        peakMillis = 0;
    }
}
