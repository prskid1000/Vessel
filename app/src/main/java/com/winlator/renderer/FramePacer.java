package com.winlator.renderer;

import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;

/**
 * VESSEL: when a synthesised frame should be shown, decided against vsync.
 *
 * <p>**{@code postDelayed} is not good enough for this, and the reason is the
 * whole point of frame generation.** A handler callback fires at some time at or
 * after its delay, on a thread competing with everything else the app is doing.
 * Frames placed that way arrive unevenly -- and uneven arrival *is* judder,
 * which is the exact artefact this feature exists to remove. Doubling the frame
 * count while scattering the timings makes the picture worse, not better, and it
 * would have looked like a quality problem in the synthesis rather than a
 * scheduling one.
 *
 * <p>{@link Choreographer} is the display's own clock. Its callback fires on the
 * vsync pulse and hands over the timestamp of the frame being composed, so a
 * synthesised frame can be aimed at a real display refresh instead of at a
 * stopwatch reading.
 *
 * <h2>Aiming</h2>
 *
 * <p>The target is the i-th of N evenly spaced points across the interval. A
 * vsync is accepted as that point when it is the closest one to it, which is the
 * best any display can do: a frame shown at a refresh boundary is shown, and a
 * frame aimed between two of them is shown at whichever comes first anyway.
 *
 * <p><b>Evenly, and it used to be nine tenths of evenly.</b> The bias existed to
 * win a race against the next real frame, back when a callback that fired late
 * showed a moment that had already been drawn. It cost more than it bought: K
 * phases compressed into {@code 0.9(K-1)/K} of an interval, then a hold for the
 * remainder -- motion running fast and then stopping, every interval. The
 * synthesiser now reads the moment to show from the vsync timestamp this hands
 * it, so a late callback shows a later moment instead of a wrong one, and the
 * aim can be what it should always have been.
 *
 * <p>The pacer never asks for a frame it has already missed. If the real frame
 * for this interval has already been superseded, the synthesised one is dropped
 * -- showing a prediction of a moment that has since been drawn for real is
 * strictly worse than showing nothing.
 */
class FramePacer {
    interface Target {
        /**
         * Called on the UI thread when a synthesised frame is due.
         *
         * @param vsyncNanos the timestamp of the display frame being composed,
         *     which is what decides the moment to show. See the class comment.
         */
        void onSynthesisDue(long vsyncNanos);

        /** Frames composited for real, for the staleness check. */
        long realFrameCount();

        /** One display refresh, from the panel. See {@link #halfRefresh}. */
        long vsyncPeriodNanos();
    }

    /**
     * VESSEL: the Choreographer is per-Looper, and the GL thread has none.
     *
     * <p>{@code Choreographer.getInstance()} throws
     * {@code IllegalStateException: The current thread must have a looper!} off
     * the main thread -- it is a {@code ThreadLocal} keyed on the thread's message
     * queue, and a {@code GLSurfaceView}'s render thread does not have one. So
     * every callback is registered from the main thread through this handler, and
     * {@link #schedule} is safe to call from wherever the composite happened.
     *
     * <p>Which is also where the callback has to fire: it wakes the renderer with
     * {@code requestRender()}, and that is the view's own thread-safe entry point
     * for exactly this.
     */
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Target target;

    /**
     * VESSEL: which display refresh is currently on screen, counted.
     *
     * <p><b>Because the platform will not say when a frame was scanned out, and
     * the question that needed answering was never really "when".</b>
     * EGL_ANDROID_get_frame_timestamps is inert on this device -- the extension
     * resolves, enabling collection on the surface succeeds,
     * eglGetFrameTimestampSupportedANDROID claims all four timestamps, and then
     * every frame of every session comes back EGL_TIMESTAMP_INVALID_ANDROID for
     * all of them. eglGetCompositorTimingANDROID does not answer either.
     *
     * <p>What that measurement was wanted for is narrower than it sounds. Two
     * frames presented into one refresh means the first was never seen: work
     * done, power spent, nothing shown -- and that failure is invisible to a
     * frame count by construction, which is the whole reason it needed its own
     * instrument. Knowing the exact nanosecond of scanout is not required to
     * detect it. Knowing which refresh each present landed on is.
     *
     * <p>The Choreographer hands over a vsync timestamp on every pulse, and
     * counting those pulses gives exactly that -- a number that increments once
     * per refresh, in the display's own rhythm rather than in this thread's.
     * Two presents recording the same value shared a scanout, and no timestamp
     * from anywhere is needed to say so.
     *
     * <p>Volatile and static: written by the one callback below on the main
     * thread, read by the synthesiser on the GL thread, and belonging to the
     * display rather than to any instance.
     */
    private static volatile long vsyncIndex = 0;
    private static boolean counting = false;

    static long vsyncIndex() { return vsyncIndex; }

    /**
     * Start counting refreshes, once, and never stop.
     *
     * <p>One Choreographer callback that re-posts itself. The platform is already
     * running this clock for anything on screen, so subscribing to it costs a
     * callback per refresh and nothing else. Started only when a diagnostic asked
     * for pacing, because that is the only thing that reads the count.
     */
    static void countRefreshes() {
        if (counting) return;
        counting = true;
        new Handler(Looper.getMainLooper()).post(() -> {
            final Choreographer choreographer = Choreographer.getInstance();
            choreographer.postFrameCallback(new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    vsyncIndex++;
                    choreographer.postFrameCallback(this);
                }
            });
        });
    }

    /**
     * VESSEL: present on the display's own cadence, not on the guest's.
     *
     * <h2>Why the schedule no longer starts at the real frame</h2>
     *
     * <p><b>The guest does not deliver evenly, and everything that laid a
     * schedule across its interval inherited that.</b> Measured over 2300 real
     * frames at 4x, arrival spacing in whole refreshes of a 120 Hz panel:
     *
     * <pre>
     *   0:1%  1:1%  2:7%  3:1%  6:5%  7:14%  8:51%  9:13%  10:5%
     * </pre>
     *
     * <p>Eight refreshes is the only spacing that divides into four presents of
     * two, and barely half the frames arrive on it. An interval that turns out to
     * be seven refreshes long cannot hold four evenly spaced presents however
     * carefully they are placed -- the last one is superseded by the next real
     * frame and dropped, which is exactly the quarter of intervals observed
     * holding two interpolated frames instead of three. The gaps that result are
     * 63% correct, 13% a single refresh and 19% three.
     *
     * <p>Two corrections were made at the scheduling end before this was
     * measured. Both were sound and both changed nothing: 25.6%, 27.0%, 24.5%,
     * 27.0% of pictures evenly spaced across four recordings. They were reverted.
     * Nothing done to a schedule can fix an interval that is the wrong length.
     *
     * <h2>What this does instead</h2>
     *
     * <p>Presents every N refreshes and never asks when the guest arrived. The
     * gaps are then even by construction rather than by prediction, and the
     * jitter lands where it does no harm: on the PHASE, which the synthesiser
     * reads from the clock anyway. A short interval means the shown moment
     * advances further per present; the picture still moves at the right average
     * speed, and the display timing -- which is what judder actually is -- stops
     * depending on the guest at all.
     *
     * <p>It costs nothing. A fixed cadence of one present every two refreshes is
     * 60 a second, against 59.2 measured for the schedule it replaces. That
     * matters because the first attempt at evenness in this project required a
     * whole slot of spacing, declined presents that arrived early, and cost a
     * sixth of the frame rate; and because a free-running clock tried here first
     * dropped it from 59.2 to 39.1 by letting its slots drift out from under the
     * frames they belonged to.
     */
    private volatile int cadence = 0;
    private long presentedAtIndex = -1;
    private boolean pumping = false;

    /**
     * @param intervalNanos measured spacing of real frames
     * @param multiple presented frames per real frame
     * @param refreshNanos one display refresh
     */
    void setCadence(long intervalNanos, int multiple, long refreshNanos) {
        if (multiple < 2 || intervalNanos <= 0 || refreshNanos <= 0) {
            cadence = 0;
            return;
        }
        // How many refreshes one slot is worth, rounded to what the display can
        // actually do. At 15 fps into 120 Hz at 4x that is two.
        final long slot = intervalNanos / multiple;
        cadence = Math.max(1, Math.round(slot / (float) refreshNanos));
        pump();
    }

    /** Whether the cadence pump is placing frames. See {@link #setCadence}. */
    boolean pumping() { return cadence > 0 && pumping; }

    private void pump() {
        if (pumping) return;
        pumping = true;
        main.post(() -> {
            final Choreographer choreographer = Choreographer.getInstance();
            choreographer.postFrameCallback(new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    final int every = cadence;
                    if (every > 0 && vsyncIndex - presentedAtIndex >= every) {
                        presentedAtIndex = vsyncIndex;
                        target.onSynthesisDue(frameTimeNanos);
                    }
                    choreographer.postFrameCallback(this);
                }
            });
        });
    }

    FramePacer(Target target) {
        this.target = target;
    }

    /**
     * Schedule the N-1 synthesised frames that belong between this real frame and
     * the next.
     *
     * @param intervalNanos measured spacing of real frames
     * @param multiple presented frames per real frame; 2 means one prediction
     */
    void schedule(long intervalNanos, int multiple) {
        if (multiple < 2 || intervalNanos <= 0) return;
        final long stamp = target.realFrameCount();
        for (int i = 1; i < multiple; i++) {
            // Evenly spaced across the interval. See the class comment for why
            // there is no longer a bias here.
            final long dueNanos = System.nanoTime() + (intervalNanos * i) / multiple;
            main.post(() -> {
                final Choreographer choreographer = Choreographer.getInstance();
                choreographer.postFrameCallback(new Choreographer.FrameCallback() {
                    @Override
                    public void doFrame(long frameTimeNanos) {
                        // A real frame landed in the meantime, so this prediction
                        // is of a moment that has already been drawn properly.
                        if (target.realFrameCount() != stamp) return;

                        // Not there yet: ride the next vsync rather than firing
                        // early. The display cannot show it sooner in any case,
                        // and waiting costs nothing -- the callback re-posts.
                        if (frameTimeNanos + halfRefresh() < dueNanos) {
                            choreographer.postFrameCallback(this);
                            return;
                        }
                        target.onSynthesisDue(frameTimeNanos);
                    }
                });
            });
        }
    }

    /**
     * Half a refresh of the panel actually in front of the user.
     *
     * <p>A vsync within this of the target is the closest one to it, so waiting
     * for the next would land further away than firing now.
     *
     * <p><b>This was four milliseconds, hardcoded, and the comment defending it
     * was wrong.</b> It reasoned that the pacer only has to pick the nearer of
     * two pulses so the exact rate cannot matter much. It does: four milliseconds
     * is half of 120 Hz, and on a 60 Hz panel half a refresh is 8.3, so every
     * target falling in the 4.3 ms band between them was handed to the *later*
     * pulse. A slot due at 25 ms with a vsync available at 18 ms should fire 7 ms
     * early; instead it waited and fired at 34.6 ms, 9.6 ms late. At 2x, where
     * there is one synthesised frame per interval, that is the whole placement.
     */
    private long halfRefresh() {
        final long period = target.vsyncPeriodNanos();
        return period > 0 ? period / 2 : 8_333_333L;
    }
}
