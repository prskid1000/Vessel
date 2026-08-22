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
 * <p>The target is the midpoint between the last real frame and the next one
 * expected -- or, for a multiple of N, the i-th of N-1 evenly spaced points. A
 * vsync is accepted as that point when it is the closest one to it, which is the
 * best any display can do: a frame shown at a refresh boundary is shown, and a
 * frame aimed between two of them is shown at whichever comes first anyway.
 *
 * <p>The pacer never asks for a frame it has already missed. If the real frame
 * for this interval has already been superseded, the synthesised one is dropped
 * -- showing a prediction of a moment that has since been drawn for real is
 * strictly worse than showing nothing.
 */
class FramePacer {
    interface Target {
        /** Called on the UI thread when a synthesised frame is due. */
        void onSynthesisDue(int index);

        /** Frames composited for real, for the staleness check. */
        long realFrameCount();
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
            final int index = i;
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
                        if (frameTimeNanos + HALF_VSYNC_NANOS < dueNanos) {
                            choreographer.postFrameCallback(this);
                            return;
                        }
                        target.onSynthesisDue(index);
                    }
                });
            });
        }
    }

    /**
     * Half a 120 Hz refresh. A vsync within this of the target is the closest one
     * to it, so waiting for the next would land further away than firing now.
     *
     * <p>A fixed number rather than one read from the display: the pacer only has
     * to pick the nearer of two pulses, and being wrong about the panel's exact
     * rate moves that decision by well under one refresh.
     */
    private static final long HALF_VSYNC_NANOS = 4_000_000L;
}
