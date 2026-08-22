package com.winlator.renderer;

import android.os.SystemClock;
import android.util.Log;

/**
 * VESSEL: frames the guest never drew, and only the ones we can build exactly.
 *
 * <p>The temporal counterpart to {@link com.winlator.renderer.material.SGSRMaterial}:
 * that reconstructs across space, from a frame rendered below the panel's
 * resolution; this fills time between frames arriving below its refresh rate.
 *
 * <h2>What is here, and what was removed</h2>
 *
 * <p><b>Window translation, and nothing else.</b> We are the compositor, so when
 * a window moves we know the translation exactly. A synthesised frame
 * re-composites the same window textures at carried-forward positions -- not an
 * estimate of the motion, the motion itself, replayed a fraction of a frame on.
 * There is no block matching, no warping and no filtering, so there is nothing
 * to distort: every pixel comes from a real texture placed at a position the
 * window really passed through. Nothing working from a screen capture can do
 * this, which is every other frame-generation implementation on Android.
 *
 * <p><b>An image-warping tier was built, measured, and taken out.</b> It used
 * {@code GL_QCOM_motion_estimation} -- which, unlike its stubbed sibling
 * {@code GL_QCOM_frame_extrapolation}, genuinely works, at 0.001 ms for a
 * fixed-function block match -- followed by a 3x3 vector median, a
 * forward-backward consistency test and a backward warp, all comfortably inside
 * budget at about 1.4 ms. It still looked worse than showing the previous frame
 * again, and no amount of filtering fixed it, because the filtering was never
 * the problem.
 *
 * <p>The problem is the size of the guess. Frame generation divides the guest's
 * frame cap by the multiple, so a 24 fps container at 4x renders six frames a
 * second, and the last prediction of each interval is aimed 125 ms past anything
 * real using vectors measured at up to a hundred pixels per pair. A block field
 * carried that far stretches the picture, and detail that was never observed
 * cannot be recovered by a median. Judged on the screen rather than on the
 * timings -- which is the right way round, and was not how it got there.
 *
 * <p>It is in the history if the aim ever gets small enough to be worth
 * revisiting: a multiple of 2 against a frame rate limit high enough that the
 * guest still renders 30 or more, so the prediction is 16 ms out and not 125.
 *
 * <p><b>The honest scope of what remains:</b> a fullscreen game has one window
 * that never moves, so this does nothing for it. It is for a desktop -- dragged
 * windows, sliding panels -- where it is exact and free.
 */
public class FrameSynthesizer implements FramePacer.Target {
    private static final String TAG = "FrameSynthesizer";

    private final GLRenderer renderer;
    private final FramePacer pacer;
    private final GpuTimer recompositeTimer = new GpuTimer("tier0 recomposite");

    private int multiple = 2;
    private volatile long realFrames = 0;
    private long lastRealFrameNanos = 0;
    private final java.util.concurrent.atomic.AtomicInteger pending =
        new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Above this gap between real frames the picture is not really moving.
     *
     * <p>It was 40 ms once, which is 25 fps, and that switched the whole feature
     * off for every container that needed it: a 24 fps cap composites every
     * 41.7 ms, so every frame fell the wrong side of the gate and not one
     * prediction was ever scheduled. A slow renderer is not an idle one. 250 ms
     * is four frames a second -- below that a synthesised frame is aimed so far
     * from anything real that it is invention rather than prediction.
     */
    private static final long IDLE_NANOS = 250_000_000L;

    private long synthesized = 0;
    private long skipped = 0;
    private long reportedAt = 0;

    public FrameSynthesizer(GLRenderer renderer) {
        this.renderer = renderer;
        this.pacer = new FramePacer(this);
    }

    public void setMultiple(int multiple) {
        this.multiple = Math.max(2, Math.min(8, multiple));
    }

    @Override
    public long realFrameCount() {
        return realFrames;
    }

    @Override
    public void onSynthesisDue(int index) {
        pending.set(index);
        renderer.xServerView.requestRender();
    }

    /** Consume a pending request, so one schedule yields exactly one frame. */
    public int consumePending() {
        return pending.getAndSet(0);
    }

    /**
     * Note that a real frame has been composited, and queue what follows it.
     *
     * <p>No offscreen capture any more. The warping tier needed the finished
     * frame as a texture; this one re-composites from the window textures the
     * renderer already holds, so the extra full-screen copy -- measured at
     * 0.8 ms, the most expensive pass in the whole pipeline -- went out with it.
     */
    public void endRealFrame() {
        realFrames++;
        renderer.latchWindowPositions();

        final long now = System.nanoTime();
        final long interval = lastRealFrameNanos == 0 ? 0 : now - lastRealFrameNanos;
        lastRealFrameNanos = now;

        if (realFrames >= 2 && interval > 0 && interval <= IDLE_NANOS) {
            pacer.schedule(interval, multiple);
        }
        report();
    }

    /**
     * Re-composite the scene aimed {@code index/N} of the way to the next real
     * frame.
     */
    public void presentSynthesized(int index) {
        // **Every path through here must draw, and the one that did not caused a
        // flicker.** GLSurfaceView swaps the buffer after onDrawFrame whether or
        // not anything was rendered into it, so returning early does not skip a
        // frame -- it presents an unwritten back buffer, which on a
        // double-buffered surface holds the frame from two swaps ago. The result
        // is the display alternating between the current picture and an old one,
        // which reads as flicker and looks like a fault in the synthesis rather
        // than in the decision not to synthesise.
        //
        // So a frame that cannot be predicted is composited as it is, at t = 0.
        // That costs one composite and shows the same picture again, which is
        // exactly what "no new information" should look like.
        float t = 0f;
        boolean predicted = false;
        if (realFrames >= 2 && index >= 1 && index < multiple && renderer.anyWindowMoved()) {
            t = (float)index / (float)multiple;
            predicted = true;
        }

        recompositeTimer.begin();
        renderer.drawSynthesizedFrame(t);
        recompositeTimer.end();

        if (predicted) synthesized++; else skipped++;
    }

    /** Say once a second what is being drawn and what it costs. */
    private void report() {
        final long now = SystemClock.uptimeMillis();
        recompositeTimer.report(now);
        if (now - reportedAt < 1000) return;
        reportedAt = now;
        if (synthesized > 0 || skipped > 0) {
            Log.i(TAG, "real " + realFrames + ", synthesized " + synthesized
                + ", skipped " + skipped + ", " + multiple + "x");
        }
        synthesized = 0;
        skipped = 0;
    }
}
