package com.winlator.renderer;

/**
 * VESSEL: when frames actually reached the panel, rather than when we drew them.
 *
 * <p><b>Every pacing number this project has produced measures the wrong
 * thing.</b> {@code FrameSynthesizer.notePresented} stamps the clock on the GL
 * thread at the moment a draw is issued. That is not when the frame is scanned
 * out, not even when it is swapped, and separated from both by a queue nothing
 * here controls. An afternoon went into a "0.2 ms gap between presents" that
 * turned out to be one draw overwriting another before the swap, and every
 * cadence conclusion since has rested on the same foundation -- including the
 * ones drawn today.
 *
 * <p>{@code EGL_ANDROID_get_frame_timestamps} has the compositor answer
 * directly. {@code EGL_DISPLAY_PRESENT_TIME_ANDROID} is when the display put the
 * frame up, in the same clock base as {@link System#nanoTime()}, so the two are
 * directly comparable and the difference between them is the queue.
 *
 * <h2>Why this is a ring buffer and not a function call</h2>
 *
 * <p>A frame's present time does not exist until the display has presented it,
 * which is several frames after the swap that produced it. So there is no
 * version of this that answers about the frame in hand. Each draw records the id
 * of the frame it is about to become; every draw also drains the oldest recorded
 * ids, and the ones the compositor has finished with turn into gaps.
 *
 * <p>Sixteen entries. The documented lag is a handful of frames and the
 * extension itself keeps a bounded history -- asking about a frame it has
 * forgotten returns invalid, not a wrong number -- so a deeper ring buys
 * nothing. An id that never resolves is overwritten and forgotten, which is the
 * correct outcome for a frame the compositor has declined to account for.
 *
 * <h2>What it costs</h2>
 *
 * <p>Nothing unless a diagnostic asked. Collection is off in the platform until
 * {@link #enable()} switches it on, and that is called only when {@code FG_LOG}
 * requested pacing. The per-frame work when it is on is one {@code
 * eglGetNextFrameIdANDROID} and at most a few {@code eglGetFrameTimestamps}
 * queries, all of which read compositor bookkeeping that already exists.
 */
final class FrameTimestamps {
    static {
        System.loadLibrary("winlator");
    }

    private static native boolean enable();

    private static native long nextFrameId();

    private static native long presentTime(long frameId);

    /** The extension's own sentinels, passed through by the native side. */
    private static final long PENDING = -2L;
    private static final long INVALID = -1L;

    private static final int RING = 16;

    private final long[] ids = new long[RING];
    private int head = 0;
    private int held = 0;

    /**
     * Whether the platform agreed to collect these.
     *
     * <p>Three separate things have to be true and any of them can be false on a
     * real device: the driver exposes the entry points, there is a current
     * surface, and the compositor accepts the request for that surface. Some
     * display paths report the extension and then answer
     * {@code EGL_TIMESTAMP_INVALID_ANDROID} for the only field that matters.
     */
    private boolean available = false;
    private boolean asked = false;

    /** Present times are useless in isolation; the gaps between them are not. */
    private long lastPresentNanos = 0;
    private long gapMin = Long.MAX_VALUE;
    private long gapMax = 0;
    private long gapTotal = 0;
    private long gaps = 0;
    /** Frames the compositor said it could not account for. */
    private long unaccounted = 0;

    /**
     * Called on the GL thread inside the draw, before the swap.
     *
     * <p>Records the frame about to be produced and collects whatever the
     * compositor has finished with. Both halves have to happen here: the id is
     * only meaningful before the swap, and the queries need a current context.
     */
    void onDraw() {
        if (!asked) {
            asked = true;
            available = enable();
        }
        if (!available) return;

        drain();

        final long id = nextFrameId();
        if (id == 0) return;
        // Overwriting the oldest is the intended behaviour, not a loss: an id
        // that has not resolved in sixteen frames is one the compositor is never
        // going to answer for.
        ids[head] = id;
        head = (head + 1) % RING;
        if (held < RING) held++;
    }

    /**
     * Turn every resolved id into a gap, oldest first.
     *
     * <p>Oldest first matters. The gap between two presents is only meaningful
     * between *consecutive* frames, so an id resolved out of order would produce
     * a difference spanning frames that were never adjacent. Walking the ring in
     * order and stopping at the first still-pending entry keeps the sequence
     * intact; the pending one will be the oldest on the next pass.
     */
    private void drain() {
        while (held > 0) {
            final int tail = (head - held + RING) % RING;
            final long id = ids[tail];
            final long when = presentTime(id);

            if (when == PENDING) return;
            held--;

            if (when <= 0 || when == INVALID) {
                // The compositor cannot say. The sequence is broken here, so the
                // next present starts a new run rather than measuring a gap
                // across the hole.
                unaccounted++;
                lastPresentNanos = 0;
                continue;
            }

            if (lastPresentNanos != 0) {
                final long gap = when - lastPresentNanos;
                // A quarter second is a pause, not a cadence -- the same rule the
                // draw-side measurement uses, so the two are comparable.
                if (gap > 0 && gap < 250_000_000L) {
                    gapMin = Math.min(gapMin, gap);
                    gapMax = Math.max(gapMax, gap);
                    gapTotal += gap;
                    gaps++;
                }
            }
            lastPresentNanos = when;
        }
    }

    /** Whether there is anything worth printing. */
    boolean hasData() {
        // **Unaccounted frames count as data.** With only `gaps > 0` here, a
        // surface where the compositor answers INVALID for every frame reports
        // nothing at all: hasData is false because no gap resolved, and
        // unavailable is false because the extension did enable. The one case
        // that most needs saying out loud was the one case that said nothing.
        return available && (gaps > 0 || unaccounted > 0);
    }

    /** Whether the platform declined, so the caller can say so once. */
    boolean unavailable() {
        return asked && !available;
    }

    /**
     * One line describing the real cadence, and reset for the next second.
     *
     * @param refreshNanos one display refresh, so the line can say which gaps
     *     were too short to be separate scanouts.
     */
    String describe(long refreshNanos) {
        if (gaps == 0) {
            final String none = String.format(
                "fg presented: the display accepted the request and then could not"
                    + " account for any of %d frames -- this surface has no real"
                    + " present times, so every cadence figure is a draw schedule",
                unaccounted);
            unaccounted = 0;
            return none;
        }
        final float mean = gapTotal / (float)gaps / 1e6f;
        final String line = String.format(
            "fg presented: %.1f ms mean, %.1f shortest, %.1f longest, over %d"
                + " frames the display confirmed%s (a refresh is %.2f ms)",
            mean, gapMin / 1e6f, gapMax / 1e6f, gaps,
            unaccounted > 0 ? ", " + unaccounted + " it could not account for" : "",
            refreshNanos / 1e6f);
        gapMin = Long.MAX_VALUE;
        gapMax = 0;
        gapTotal = 0;
        gaps = 0;
        unaccounted = 0;
        return line;
    }
}
