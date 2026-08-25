package com.winlator.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import androidx.core.graphics.ColorUtils;

// VESSEL: app.vessel.R, not com.winlator.R — the vendored packages live
// inside Vessel's APK and android.nonTransitiveRClass is on.
import app.vessel.R;

import com.winlator.core.Bitmask;
import com.winlator.core.Callback;
import com.winlator.core.ImageUtils;
import com.winlator.math.Mathf;
import com.winlator.math.XForm;
import com.winlator.renderer.material.CursorMaterial;
import com.winlator.renderer.material.SGSRMaterial;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;
import com.winlator.renderer.material.WindowMaterial;
import com.winlator.widget.XServerView;
import com.winlator.xserver.Cursor;
import com.winlator.xserver.Decoration;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowAttributes;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLRenderer implements GLSurfaceView.Renderer, WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener {
    public final XServerView xServerView;
    private final XServer xServer;
    protected final VertexAttribute quadVertices = new VertexAttribute("position", 2);
    private final float[] tmpXForm1 = XForm.getInstance();
    private final float[] tmpXForm2 = XForm.getInstance();
    private final CursorMaterial cursorMaterial = new CursorMaterial();
    private final WindowMaterial windowMaterial = new WindowMaterial();
    // VESSEL: the upscaler, used instead of windowMaterial when a window is
    // being magnified. See useSGSRFor().
    private final SGSRMaterial sgsrMaterial = new SGSRMaterial();
    // VESSEL: the container's choice. Default true so a session that never calls
    // setUpscaler behaves as it did before the setting existed.
    private boolean sgsrEnabled = true;
    public final ViewTransformation viewTransformation = new ViewTransformation();
    private final Drawable rootCursorDrawable;
    private final ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private boolean forceWindowsFullscreen;
    private boolean fullscreen = false;

    /**
     * VESSEL: composite at the size the guest drew, not the size it is shown at.
     *
     * <p>**Everything downstream of the composite was doing several times the
     * work the content justifies, and the upscale is why.** The guest draws at
     * `display.resolution`; the compositor stretches that to the panel by binding
     * a letterboxed viewport, because {@link WindowMaterial} emits *guest*
     * coordinates and the viewport is what scales them. Frame generation then
     * captured the result -- a panel-sized image of an upscaled 1280x720 frame,
     * with black bars either side -- and searched, matched and interpolated all
     * of it. On this device that is 3.5 megapixels of work carrying 0.9
     * megapixels of information, and roughly 40% of it is bars.
     *
     * <p>Worse than the cost: between every pair of rendered pixels sit ones the
     * upscaler invented, and a block matcher has nothing to lock onto in those.
     * The search was being asked to find motion in pixels that never moved
     * because they were never drawn.
     *
     * <p>So the capture binds a guest-sized target and a guest-sized viewport, and
     * the upscale moves to the end -- {@link #presentGuestFrame} -- where it runs
     * once, on a finished frame, into the letterbox rectangle. Nothing else about
     * the composite changes: the same transform, the same {@code viewSize}, the
     * same materials. It is one viewport.
     *
     * <p>This is also the order FSR3 and DLSS use, and for the same reason: work
     * at render resolution, upscale last.
     */
    private boolean capturingAtGuestScale = false;

    /** VESSEL: see {@link #capturingAtGuestScale}. */
    void beginGuestScaleCapture() {
        capturingAtGuestScale = true;
        viewportNeedsUpdate = true;
    }

    /** VESSEL: see {@link #capturingAtGuestScale}. */
    void endGuestScaleCapture() {
        capturingAtGuestScale = false;
        viewportNeedsUpdate = true;
    }
    private boolean toggleFullscreen = false;
    protected boolean viewportNeedsUpdate = true;
    private boolean cursorVisible = true;
    private float cursorScale = 1.0f;
    private int cursorBackColor = 0xffffff;
    private int cursorForeColor = 0x000000;
    private boolean screenOffsetYRelativeToCursor = false;
    private float magnifierZoom = 1.0f;
    /**
     * VESSEL: what the guest actually rendered, as opposed to what it is shown at.
     *
     * <p>The guest draws at {@code display.resolution} and the compositor scales
     * that to the panel, so a captured frame is an *upscaled* image: between every
     * pair of rendered pixels sit interpolated ones carrying no independent
     * detail. Frame generation searches that image for motion, and a block matcher
     * has nothing to lock onto in an interpolated pixel -- it was matching at
     * 2776x1264 on content with 1280x720 of real information in it, which is 3.8
     * times the work for a weaker answer. See {@link FrameSynthesizer}.
     */
    int guestWidth() { return xServer.screenInfo.width; }

    int guestHeight() { return xServer.screenInfo.height; }

    protected short surfaceWidth;
    protected short surfaceHeight;
    public final EffectComposer effectComposer = new EffectComposer(this);

    public GLRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        rootCursorDrawable = createRootCursorDrawable();

        quadVertices.put(new float[]{
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        });

        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    /**
     * VESSEL: which EGL context the GL names in this process belong to.
     *
     * Upstream never needs this. Winlator's X server owns a whole Activity for
     * the whole session, so its context is created once and destroyed once.
     * Vessel's is one screen among several: navigating away destroys the
     * SurfaceView and the context with it, and coming back builds a new one —
     * while every Java object that cached a GL name lives straight through.
     *
     * Three of them do, and the effect compounds. {@link Texture} keeps a
     * texture id, {@link com.winlator.renderer.material.ShaderMaterial} keeps a
     * linked program id, and {@link VertexAttribute} keeps a buffer id; all
     * three test their id against zero to decide whether they still have to
     * create it, and a stale id is not zero. The program is the one that turns
     * the screen black on its own — `glUseProgram` on a name from a dead context
     * fails, and after that nothing is drawn at all, textures or no textures.
     *
     * Deleting the stale names is not the alternative. A fresh context issues
     * names from 1 again, so a delete would very likely destroy whatever now
     * owns that number. Each holder compares generations and recreates.
     */
    public static int contextGeneration() {
        return contextGeneration;
    }

    private static volatile int contextGeneration = 0;

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // VESSEL: upstream calls GPUHelper.setGlobalEGLContext() here, which
        // stashes the EGLContext for libgladiorenderer to share against. No
        // GLX extension, no gladio, nothing to share with.
        //
        // VESSEL: this fires again every time the SurfaceView is recreated —
        // leaving the desktop and coming back does it — and the context that
        // comes back is a new one, holding none of the objects the old one did.
        // Everything that cached a GL name is now holding a number that means
        // nothing here. See contextGeneration().
        contextGeneration++;
        GLES20.glFrontFace(GLES20.GL_CCW);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceWidth = (short)width;
        surfaceHeight = (short)height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        viewportNeedsUpdate = true;
        // VESSEL: ask for a frame, because nothing else will.
        //
        // The view is RENDERMODE_WHEN_DIRTY, so it draws only when the X server
        // reports damage. A desktop that has finished starting has nothing left
        // to damage — so after the surface is created the screen stayed blank
        // until the user moved the cursor, which was the first thing to call
        // requestRender(). Reported as "blank screen after container start
        // until I move the cursor", which is exactly that and nothing more.
        //
        // Upstream never sees it: Winlator's surface is created before its guest
        // starts, so the first damage always arrives after the first frame.
        // Vessel's desktop can already be running when the surface appears —
        // coming back to a session does exactly that.
        xServerView.requestRender();
    }

    /**
     * VESSEL: how many frames this renderer has composited, ever.
     *
     * A counter and not a rate, deliberately. Turning it into frames-per-second
     * needs two reads and the wall time between them, and the thing doing that
     * is a coroutine on the Android side that already knows what a second is —
     * so nothing here has to keep a clock, a window of samples, or any state
     * that could be wrong.
     *
     * **What it counts is what the user is actually shown.** The view is
     * `RENDERMODE_WHEN_DIRTY`, so a frame happens when the guest damages the
     * screen and not on a display vsync. An idle desktop composites nothing and
     * reads 0, which is the truth: nothing was drawn. A program rendering flat
     * out reads its own delivered rate, capped by the surface.
     *
     * `volatile` rather than an `AtomicLong`: there is exactly one writer, the
     * GL thread, and the readers only need to see a recent value. A missed
     * increment would cost one frame out of a sample of sixty.
     */
    public long compositedFrames() {
        return compositedFrames;
    }

    private volatile long compositedFrames = 0;

    /**
     * VESSEL: notified around each composite, on the GL thread.
     *
     * The one seam `app.vessel.display.FrameHints` needs, and the reason it is
     * here rather than in the view: `GLSurfaceView.setRenderer` may be called
     * once and `XServerView`'s constructor has already called it, so a Vessel
     * subclass of the view cannot wrap the renderer from outside.
     *
     * Both callbacks bracket the *whole* frame including the effect composer,
     * because a hint session is being told how long the composite took and an
     * effect pass is part of that.
     */
    public interface FrameListener {
        void onFrameBegin();

        void onFrameEnd();
    }

    /**
     * VESSEL: `volatile` for the same reason `compositedFrames` is — one writer
     * (whoever built the view), one reader (the GL thread). Null is the normal
     * state on a device with no performance-hint support.
     */
    private volatile FrameListener frameListener;

    /** VESSEL: see {@link FrameListener}. Null clears it. */
    public void setFrameListener(FrameListener listener) {
        this.frameListener = listener;
    }

    /**
     * VESSEL: predicted frames, off unless a container asks for them.
     *
     * <p>Null until switched on, because the three render targets it allocates
     * are the size of the surface and a container that will never use them
     * should not be paying for them.
     */
    private FrameSynthesizer frameSynthesizer;

    /** Presented frames per real frame. Below 2 means off. */
    private int frameGenerationMultiplier = 0;

    /**
     * VESSEL: which parts of frame generation should report on themselves.
     *
     * <p>From the container's {@code FG_LOG} environment row. Empty is the normal
     * case and costs nothing -- every diagnostic is gated on membership, and the
     * measurement passes that feed the expensive categories are not run at all
     * unless something asked for them.
     */
    private java.util.Set<String> frameGenerationLog = java.util.Collections.emptySet();

    /** VESSEL: see {@link #frameGenerationLog}. */
    public void setFrameGenerationLog(java.util.Set<String> categories) {
        this.frameGenerationLog = categories == null
            ? java.util.Collections.emptySet() : categories;
        if (frameSynthesizer != null) frameSynthesizer.setDiagnostics(this.frameGenerationLog);
    }

    /** VESSEL: see {@link FrameSynthesizer}. Below 2 switches it off. */
    public void setFrameGenerationMultiplier(int multiplier) {
        this.frameGenerationMultiplier = multiplier;
        if (frameSynthesizer != null && multiplier >= 2) {
            frameSynthesizer.setMultiple(multiplier);
        }
        xServerView.requestRender();
    }

    /**
     * VESSEL: whether this frame goes through the extrapolator at all.
     *
     * <p>Effects are the one exclusion, and it is a structural one rather than a
     * policy: {@link EffectComposer#render()} binds framebuffer 0 for its last
     * pass, so it writes the finished picture straight to the screen and there
     * is nothing left for the extrapolator to capture. A container with an
     * effect keeps the path it had.
     */
    private boolean extrapolating() {
        if (frameGenerationMultiplier < 2) return false;
        if (effectComposer.hasEffects()) return false;
        if (frameSynthesizer == null) {
            frameSynthesizer = new FrameSynthesizer(this);
            frameSynthesizer.setMultiple(frameGenerationMultiplier);
            frameSynthesizer.setDiagnostics(frameGenerationLog);
        }
        return true;
    }

    /**
     * VESSEL: forget which window material is bound.
     *
     * <p>{@link #bindWindowMaterial} keeps one {@code glUseProgram} per frame by
     * remembering what it last bound. Anything else that binds a program behind
     * its back -- the extrapolator's blit does -- has to say so, or the next
     * window pass skips a bind it needed and draws with the wrong shader.
     */
    void invalidateBoundWindowMaterial() {
        boundWindowMaterial = null;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // VESSEL: a predicted frame counts. This is documented as what the user
        // is actually shown, and a synthesised frame is shown.
        compositedFrames++;
        // VESSEL: read once. The field is volatile and the teardown path clears
        // it, so a local is what keeps begin and end paired on the same object.
        final FrameListener listener = frameListener;
        if (listener != null) listener.onFrameBegin();

        if (toggleFullscreen) {
            fullscreen = !fullscreen;
            toggleFullscreen = false;
            viewportNeedsUpdate = true;
        }

        if (extrapolating()) {
            // **Read and cleared together, and the real frame wins the draw.**
            //
            // See guestDamaged. The pending timestamp is consumed either way:
            // a prediction the guest has already overtaken must not be allowed
            // to fire on some later draw, where it would show a moment older
            // than what is already on screen.
            final boolean haveReal = guestDamaged;
            guestDamaged = false;
            final long synthesized = frameSynthesizer.consumePending();

            if (!haveReal && synthesized > 0) {
                frameSynthesizer.presentSynthesized(synthesized);
                if (listener != null) listener.onFrameEnd();
                return;
            }
            if (frameSynthesizer.beginRealFrame()) {
                drawFrame();
                frameSynthesizer.endRealFrame();
                if (listener != null) listener.onFrameEnd();
                return;
            }
            // Allocation failed. Fall through and composite to the screen, which
            // is what this did before the extrapolator existed.
        }

        if (effectComposer.hasEffects()) {
            effectComposer.render();
        }
        else drawFrame();

        if (listener != null) listener.onFrameEnd();
    }

    protected void drawFrame() {
        if (viewportNeedsUpdate) {
            // VESSEL: one to one with what the guest drew, when the frame is being
            // captured for frame generation. See capturingAtGuestScale.
            if (capturingAtGuestScale) {
                GLES20.glViewport(0, 0, guestWidth(), guestHeight());
            }
            else if (fullscreen) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            }
            else GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
            viewportNeedsUpdate = false;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        updatePointerTransform();

        renderWindows();
        // **Not while capturing.** See presentGuestFrame: a frame captured for
        // frame generation must not contain the cursor, because everything
        // downstream would then try to infer its motion from an 8x8 block match
        // and get it wrong. It is drawn over the presented frame instead.
        if (cursorVisible && !capturingAtGuestScale) renderCursor();
    }

    /**
     * VESSEL: the magnifier and screen-offset transform the cursor draws through.
     *
     * <p>Lifted out of {@link #drawFrame} because the cursor is now drawn from two
     * places -- there when compositing straight to the screen, and from {@link
     * #presentGuestFrame} when a captured frame is being shown -- and {@link
     * #renderCursorDrawable} multiplies by {@code tmpXForm2}, so both paths have
     * to have set it.
     */
    private void updatePointerTransform() {
        float pointerX = 0;
        float pointerY = 0;
        float magnifierZoom = !screenOffsetYRelativeToCursor ? this.magnifierZoom : 1.0f;

        if (magnifierZoom != 1.0f) {
            pointerX = Mathf.clamp(xServer.pointer.getX() * magnifierZoom - xServer.screenInfo.width * 0.5f, 0, xServer.screenInfo.width * Math.abs(1.0f - magnifierZoom));
        }

        if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) {
            float scaleY = magnifierZoom != 1.0f ? Math.abs(1.0f - magnifierZoom) : 0.5f;
            float offsetY = xServer.screenInfo.height * (screenOffsetYRelativeToCursor ? 0.25f : 0.5f);
            pointerY = Mathf.clamp(xServer.pointer.getY() * magnifierZoom - offsetY, 0, xServer.screenInfo.height * scaleY);
        }

        XForm.makeTransform(tmpXForm2, -pointerX, -pointerY, magnifierZoom, magnifierZoom, 0);
    }

    /**
     * VESSEL: the guest has drawn something this draw has not composited yet.
     *
     * <p><b>Without this the two schedulers cannot be told apart, and the wrong
     * one wins.</b> The view is {@code RENDERMODE_WHEN_DIRTY}, so a composite
     * happens because someone called {@code requestRender()} -- and two
     * independent things do: the guest, whenever it damages a window, and the
     * pacer, when a synthesised frame is due. {@code GLSurfaceView} coalesces
     * every request between two draws into one draw, so when both land in the
     * same refresh only one composite happens, and {@link #onDrawFrame} decided
     * which by looking at the pending timestamp -- which the pacer had set.
     *
     * <p>So the synthesised frame won, every time the two coincided. That frame
     * is an interpolation of the previous two real frames, presented while newer
     * real content was already sitting in the X server's buffers waiting. The
     * comment on FramePacer states the rule this breaks: showing a prediction of
     * a moment that has since been drawn for real is strictly worse than showing
     * nothing.
     *
     * <p>{@code volatile} because the damage callbacks run on the X server's
     * thread and the draw runs on the GL thread.
     */
    private volatile boolean guestDamaged = false;

    @Override
    public void onMapWindow(Window window) {
        guestDamaged = true;
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUnmapWindow(Window window) {
        guestDamaged = true;
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onChangeWindowZOrder(Window window) {
        guestDamaged = true;
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        // The one that matters: a game rendering calls this every frame.
        guestDamaged = true;
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowGeometry(final Window window, boolean resized) {
        guestDamaged = true;
        if (resized) {
            xServerView.queueEvent(this::updateScene);
        }
        else xServerView.queueEvent(() -> updateWindowPosition(window));
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) xServerView.requestRender();
    }

    @Override
    public void onPointerMove(short x, short y) {
        xServerView.requestRender();
    }

    private void renderCursorDrawable(Drawable drawable, int x, int y) {
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable();

            XForm.set(tmpXForm1, x, y, drawable.width * cursorScale, drawable.height * cursorScale);
            XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);

            cursorMaterial.setUniformColor(cursorMaterial.uniforms.backColor, cursorBackColor);
            cursorMaterial.setUniformColor(cursorMaterial.uniforms.foreColor, cursorForeColor);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
            cursorMaterial.setUniformInt(cursorMaterial.uniforms.texture, 0);
            cursorMaterial.setUniformFloatArray(cursorMaterial.uniforms.xform, tmpXForm1);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    private void renderWindowDrawable(Drawable drawable, int x, int y, boolean transparent, FullscreenTransformation fullscreenTransformation) {
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable();

            float destWidth;
            float destHeight;
            if (fullscreenTransformation != null) {
                XForm.set(tmpXForm1, fullscreenTransformation.x, fullscreenTransformation.y, fullscreenTransformation.width, fullscreenTransformation.height);
                destWidth = fullscreenTransformation.width;
                destHeight = fullscreenTransformation.height;
            }
            else {
                XForm.set(tmpXForm1, x, y, drawable.width, drawable.height);
                destWidth = drawable.width;
                destHeight = drawable.height;
            }

            XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());

            // VESSEL: SGSR when this window is genuinely being magnified, the
            // bilinear blit when it is not. See useSGSRFor().
            if (useSGSRFor(drawable, destWidth, destHeight, transparent)) {
                bindWindowMaterial(sgsrMaterial);
                sgsrMaterial.setUniformInt(sgsrMaterial.uniforms.texture, 0);
                sgsrMaterial.setSourceSize(drawable.width, drawable.height);
                sgsrMaterial.setUniformFloatArray(sgsrMaterial.uniforms.xform, tmpXForm1);
                sgsrMaterial.setUniformBool(sgsrMaterial.uniforms.flipY, texture.isFlipY());
            }
            else {
                bindWindowMaterial(windowMaterial);
                windowMaterial.setUniformInt(windowMaterial.uniforms.texture, 0);
                windowMaterial.setUniformFloat(windowMaterial.uniforms.noAlpha, !transparent ? 1.0f : 0.0f);
                windowMaterial.setUniformFloatArray(windowMaterial.uniforms.xform, tmpXForm1);
                windowMaterial.setUniformBool(windowMaterial.uniforms.flipY, texture.isFlipY());
            }

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    /**
     * VESSEL: the container's upscaler choice, applied before the first frame.
     *
     * <p>Takes primitives and {@link SGSRMaterial.Tuning} rather than anything
     * from {@code app.vessel}, because {@code app/src/main/java/com/winlator/README.md}
     * says the vendored tree never imports Vessel's. The caller composes the
     * tuning; this only stores it.
     *
     * <p>Safe to call after frames have already been drawn: {@link
     * SGSRMaterial#setTuning} zeroes the program id, so the next {@code use()}
     * recompiles with the new constants.
     */
    public void setUpscaler(boolean enabled, SGSRMaterial.Tuning tuning) {
        sgsrEnabled = enabled;
        sgsrMaterial.setTuning(tuning);
    }

    /**
     * VESSEL: whether this window is being drawn into more pixels than it has.
     *
     * <p><b>The point of the whole feature is that most windows fail this test.</b>
     * SGSR costs a dozen-odd taps and a Lanczos fit per fragment; spending that
     * to reproduce a 1:1 copy would be a pure loss. So the test is on the real
     * magnification and not on a setting the user forgot they turned on.
     *
     * <p>Three ways it can be false, and each is a real case:
     *
     * <ul>
     *   <li><b>No magnification.</b> The window's texture is {@code drawable.width}
     *       texels and lands on {@code destWidth} guest units, which the viewport
     *       then scales to the surface by {@link ViewTransformation#aspect} — or,
     *       in the fullscreen branch of {@link #drawFrame()}, by the surface over
     *       the desktop, because that branch sets the viewport to the whole
     *       surface. The smaller of the two axes decides, so a window stretched on
     *       one axis only is left to the bilinear path.</li>
     *   <li><b>A transparent window.</b> SGSR's last line is
     *       {@code color.w = 1.0; //assume alpha channel is not used}, which is
     *       true of a game and false of a layered window. Upstream's own comment
     *       is the reason this branch exists; running it on a window that needs
     *       its alpha would turn a soft edge into an opaque rectangle.</li>
     *   <li><b>A driver below GLSL ES 3.10</b>, which cannot compile
     *       {@code textureGather}. See {@link SGSRMaterial#isSupported()}.</li>
     * </ul>
     *
     * <p>The 1.02 margin is not a tuning knob, it is a guard against churn: the
     * desktop is letterboxed onto the surface with a {@code ceil()}, so a
     * nominally 1:1 configuration can land a hair over one and flip the material
     * back and forth between frames.
     */
    /**
     * VESSEL: put a guest-sized frame on the screen, upscaled once.
     *
     * <p>The other half of {@link #capturingAtGuestScale}. Takes a texture holding
     * a frame at the guest's resolution -- real or synthesised, they are the same
     * thing by this point -- and draws it into the letterboxed rectangle through
     * the same materials a window goes through, so the upscaler that used to run
     * per window now runs once per presented frame.
     *
     * <p>The quad is expressed in guest coordinates like everything else here,
     * which is what makes this resolution-agnostic: {@code viewSize} is the guest
     * screen and the viewport is the destination, so 1280x720 and 300x470 differ
     * only in the numbers.
     *
     * <p>The screen is cleared first because the letterbox bars are outside the
     * viewport and nothing else writes them.
     *
     * <p>**Callers holding a captured frame want {@code flipY} true, and getting
     * it wrong turns the picture upside down.** Guest coordinates run downwards,
     * so this material's vertex shader inverts Y on the way to clip space -- which
     * is right when it draws a window straight to the screen, and happens *twice*
     * when the same material draws a frame that was itself composited through it
     * into a texture. The old path blitted with {@link ScreenMaterial}, whose
     * shader does no inversion at all, so the question never arose. Flipping the
     * sampled V undoes the second one.
     */
    void presentGuestFrame(int textureId, boolean flipY) {
        final int guestW = guestWidth();
        final int guestH = guestHeight();
        if (guestW <= 0 || guestH <= 0) return;

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
                          viewTransformation.viewWidth, viewTransformation.viewHeight);
        viewportNeedsUpdate = true;
        GLES20.glDisable(GLES20.GL_BLEND);

        XForm.set(tmpXForm1, 0, 0, guestW, guestH);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        // The same test the per-window path used, asked once about the whole
        // frame: SGSR where the picture is genuinely being magnified, the
        // bilinear blit where it is not.
        final boolean magnifying = sgsrEnabled
            && SGSRMaterial.isSupported()
            && viewTransformation.aspect > 1.02f;

        if (magnifying) {
            bindWindowMaterial(sgsrMaterial);
            sgsrMaterial.setUniformInt(sgsrMaterial.uniforms.texture, 0);
            sgsrMaterial.setSourceSize(guestW, guestH);
            sgsrMaterial.setUniformFloatArray(sgsrMaterial.uniforms.xform, tmpXForm1);
            sgsrMaterial.setUniformBool(sgsrMaterial.uniforms.flipY, flipY);
        }
        else {
            bindWindowMaterial(windowMaterial);
            windowMaterial.setUniformInt(windowMaterial.uniforms.texture, 0);
            windowMaterial.setUniformFloat(windowMaterial.uniforms.noAlpha, 1.0f);
            windowMaterial.setUniformFloatArray(windowMaterial.uniforms.xform, tmpXForm1);
            windowMaterial.setUniformBool(windowMaterial.uniforms.flipY, flipY);
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        boundWindowMaterial = null;

        // **The cursor goes on top of the frame, not into it.**
        //
        // It used to be composited into the captured frame, which sent it
        // through the block matcher like scenery -- and the matcher is the wrong
        // instrument for it twice over. A cursor is about sixteen 8x8 blocks on a
        // desktop where every neighbour correctly reports zero, so the median
        // filter that exists to reject lone dissenters deletes its motion
        // (GuardMaterial); and a quick flick moves it further in one interval
        // than the 112 px search window can see at all, so there is no vector to
        // find. Measured on ninety seconds of real pointer movement, 9 to 16% of
        // synthesised frames lost the motion entirely and fell back to a
        // cross-fade, which puts the pointer half way back -- one bad frame every
        // other interval at 4x, seen as the cursor splitting and lagging.
        //
        // None of that had to be inferred. The X server holds the exact pointer
        // position, and this is a compositor: it can simply draw the thing where
        // it is. Reading it here rather than at capture time also makes the
        // cursor FRESHER than the frame under it, which is what a hardware cursor
        // plane does and why they exist.
        //
        // Tier 0 is untouched -- drawSynthesizedFrame goes through drawFrame with
        // capturingAtGuestScale false, so it still draws its own cursor there.
        if (cursorVisible) {
            updatePointerTransform();
            renderCursor();
        }
    }

    /**
     * VESSEL: what the compositor is actually stacking, layer by layer.
     *
     * <p>Frame generation sees one flattened picture and can say nothing about
     * what went into it -- but nearly every artefact class depends on that. A
     * transparent overlay is blended and cannot be tracked by a block matcher; a
     * window smaller than the screen has edges that are not scene boundaries; a
     * second layer moving over a first is two motions in one pixel, which a single
     * vector per block cannot express at all. All of that is invisible downstream
     * and known here.
     *
     * <p>Also says, per layer, whether the upscaler touched it. With the capture
     * now at the guest's own resolution nothing should be magnified during a
     * composite, and a layer reporting otherwise means the capture is not one to
     * one after all.
     */
    String describeLayers() {
        final StringBuilder out = new StringBuilder();
        int index = 0;
        int hidden = 0;
        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            // **Report what is drawn, not what exists.** This counted every
            // window while renderWindows skipped the covered ones, and a
            // diagnostic describing something other than what happens is how the
            // wasted fill went unnoticed in the first place.
            final int firstVisible = Math.max(0, topmostOpaqueCover());
            int position = -1;
            for (RenderableWindow window : renderableWindows) {
                position++;
                if (position < firstVisible) { hidden++; continue; }
                if (window.content.isOffscreenStorage()) continue;
                final float destW = window.fullscreenTransformation != null
                    ? window.fullscreenTransformation.width : window.content.width;
                final float destH = window.fullscreenTransformation != null
                    ? window.fullscreenTransformation.height : window.content.height;
                if (index > 0) out.append("; ");
                out.append('[').append(index).append("] ")
                   .append(window.content.width).append('x').append(window.content.height)
                   .append(" at ").append(window.rootX).append(',').append(window.rootY);
                if (window.rootX != window.previousRootX || window.rootY != window.previousRootY) {
                    out.append(" moved ")
                       .append(window.rootX - window.previousRootX).append(',')
                       .append(window.rootY - window.previousRootY);
                }
                if (window.transparent) out.append(" transparent");
                if (window.fullscreenTransformation != null) {
                    out.append(" stretched to ").append((int)destW).append('x').append((int)destH);
                }
                if (useSGSRFor(window.content, destW, destH, window.transparent)) {
                    out.append(" UPSCALED");
                }
                index++;
            }
        }
        if (index == 0) return "none";
        return index + " layer" + (index == 1 ? "" : "s")
            + (hidden > 0 ? " (" + hidden + " culled as fully covered)" : "")
            + ": " + out;
    }

    private boolean useSGSRFor(Drawable drawable, float destWidth, float destHeight, boolean transparent) {
        if (!sgsrEnabled) return false;
        if (transparent) return false;
        if (drawable.width <= 0 || drawable.height <= 0) return false;
        if (!SGSRMaterial.isSupported()) return false;

        float scaleX;
        float scaleY;
        // VESSEL: a capture is one to one by construction, so nothing is being
        // magnified and SGSR has nothing to do. Without this it would upscale
        // into a target the same size as its source and then be upscaled again at
        // present -- twice the cost for a worse picture than doing it once.
        if (capturingAtGuestScale) {
            scaleX = 1f;
            scaleY = 1f;
        }
        else if (fullscreen) {
            scaleX = (float)surfaceWidth / xServer.screenInfo.width;
            scaleY = (float)surfaceHeight / xServer.screenInfo.height;
        }
        else {
            scaleX = viewTransformation.aspect;
            scaleY = viewTransformation.aspect;
        }

        float magnification = Math.min(
            (destWidth * scaleX) / drawable.width,
            (destHeight * scaleY) / drawable.height);
        return magnification > 1.02f;
    }

    /**
     * VESSEL: bind a window material, and only when it is not the bound one.
     *
     * <p>Upstream binds one material for the whole window pass, outside the loop.
     * With two of them the bind has to move inside it, and this keeps the common
     * case — every window taking the same path — at exactly one
     * {@code glUseProgram} per frame, which is what it was before.
     */
    private void bindWindowMaterial(ShaderMaterial material) {
        if (material == boundWindowMaterial) return;
        material.use();
        if (material == sgsrMaterial) {
            sgsrMaterial.setUniformVec2(sgsrMaterial.uniforms.viewSize, xServer.screenInfo.width, xServer.screenInfo.height);
        }
        else {
            windowMaterial.setUniformVec2(windowMaterial.uniforms.viewSize, xServer.screenInfo.width, xServer.screenInfo.height);
        }
        quadVertices.bind(material.programId);
        boundWindowMaterial = material;
    }

    /** VESSEL: which of the two window materials is bound, within one pass. */
    private ShaderMaterial boundWindowMaterial;

    /**
     * VESSEL: how far past the last real composite this frame is aimed.
     *
     * <p>**One for a real frame, and it used to be zero.** The tier reads as an
     * interpolation now, matching the pipeline around it: phase 0 is the previous
     * composite and phase 1 is this one, so a real frame is phase 1 and lands
     * exactly where the window is. The old form added {@code (root - previous)*t}
     * on top of the *current* position, which carried the window past frame N
     * rather than between N-1 and N -- an extrapolation left over from the design
     * before this one. Windows ran ahead and snapped back on every real frame.
     *
     * <p>See {@link RenderableWindow#previousRootX} for why this is exact rather
     * than estimated.
     */
    private float synthesisT = 1f;

    /**
     * Remember where every window is, so the next synthesised frame can carry it
     * forward. Called once per real composite, after it has been drawn.
     */
    void latchWindowPositions() {
        for (RenderableWindow window : renderableWindows) {
            window.previousRootX = window.rootX;
            window.previousRootY = window.rootY;
        }
    }

    /**
     * Whether any window moved between the last two real composites.
     *
     * <p>The gate for this tier. Nothing moved means a synthesised frame would be
     * a byte-for-byte copy of the one before it, which is worse than not drawing:
     * it costs a composite and shows the user nothing new.
     */
    boolean anyWindowMoved() {
        for (RenderableWindow window : renderableWindows) {
            if (window.rootX != window.previousRootX || window.rootY != window.previousRootY) {
                return true;
            }
        }
        return false;
    }

    /** Composite the whole scene aimed at {@code t} frames past the last real one. */
    void drawSynthesizedFrame(float t) {
        synthesisT = t;
        try {
            drawFrame();
        } finally {
            synthesisT = 1f;
        }
    }

    /**
     * VESSEL: the topmost opaque window that covers the whole screen, or -1.
     *
     * <p><b>Metro composites three full-screen opaque layers and two of them are
     * never seen.</b> Wine's virtual desktop, the game's own window and the
     * presentation surface all arrive as 1280x720 at 0,0, stacked; only the last
     * is visible, and this drew all three every frame. That is two whole screens
     * of fill thrown away, on the same GPU the guest is competing with for the
     * frame rate everything else here depends on.
     *
     * <p>Culling what an opaque full-screen window completely hides cannot change
     * a pixel, because the covered windows contribute nothing to begin with.
     * Transparent windows never count as covering and neither does one that does
     * not span the screen, so menus, overlays and child windows are untouched.
     *
     * <p>Only full-screen coverage is considered rather than general rectangle
     * occlusion: this exists to remove one specific large waste, and a general
     * solution would have to reason about partial overlap and stacking order for
     * a case no guest here produces.
     */
    private int topmostOpaqueCover() {
        final int screenWidth = xServer.screenInfo.width;
        final int screenHeight = xServer.screenInfo.height;
        int cover = -1;
        for (int i = 0; i < renderableWindows.size(); i++) {
            final RenderableWindow window = renderableWindows.get(i);
            if (window.content.isOffscreenStorage() || window.transparent) continue;
            final float width = window.fullscreenTransformation != null
                ? window.fullscreenTransformation.width : window.content.width;
            final float height = window.fullscreenTransformation != null
                ? window.fullscreenTransformation.height : window.content.height;
            final float x = window.fullscreenTransformation != null
                ? window.fullscreenTransformation.x : window.rootX;
            final float y = window.fullscreenTransformation != null
                ? window.fullscreenTransformation.y : window.rootY;
            if (x <= 0 && y <= 0
                    && x + width >= screenWidth && y + height >= screenHeight) {
                cover = i;
            }
        }
        return cover;
    }

    private void renderWindows() {
        // VESSEL: the material is chosen per window now, so the pass opens with
        // nothing bound rather than with the bilinear one bound unconditionally.
        boundWindowMaterial = null;

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            // Everything under an opaque window covering the screen is invisible.
            // See topmostOpaqueCover.
            final int firstVisible = Math.max(0, topmostOpaqueCover());
            int position = -1;
            for (RenderableWindow window : renderableWindows) {
                position++;
                if (position < firstVisible) continue;
                if (!window.content.isOffscreenStorage()) {
                    // VESSEL: placed between the last two composites on a
                    // synthesised frame. See RenderableWindow.previousRootX -- at
                    // synthesisT == 1 this is exactly the position the window has,
                    // so the real path is unchanged and costs one multiply it will
                    // optimise away.
                    final int x = window.previousRootX
                        + Math.round((window.rootX - window.previousRootX) * synthesisT);
                    final int y = window.previousRootY
                        + Math.round((window.rootY - window.previousRootY) * synthesisT);
                    renderWindowDrawable(window.content, x, y, window.transparent, window.fullscreenTransformation);
                }
            }
        }

        if (boundWindowMaterial != null) quadVertices.disable();
        boundWindowMaterial = null;
    }

    private void renderCursor() {
        cursorMaterial.use();
        cursorMaterial.setUniformVec2(cursorMaterial.uniforms.viewSize, xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(cursorMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            Window pointWindow = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pointWindow != null ? pointWindow.attributes.getCursor() : null;
            short x = xServer.pointer.getClampedX();
            short y = xServer.pointer.getClampedY();

            if (cursor != null) {
                if (cursor.isVisible()) renderCursorDrawable(cursor.cursorImage, x - cursor.hotSpotX, y - cursor.hotSpotY);
            }
            else renderCursorDrawable(rootCursorDrawable, x, y);
        }

        quadVertices.disable();
    }

    public void toggleFullscreen() {
        toggleFullscreen = true;
        xServerView.requestRender();
    }

    private Drawable createRootCursorDrawable() {
        Context context = xServerView.getContext();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
        return Drawable.fromBitmap(bitmap);
    }

    private void updateScene() {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            renderableWindows.clear();
            collectRenderableWindows(xServer.windowManager.rootWindow, xServer.windowManager.rootWindow.getX(), xServer.windowManager.rootWindow.getY());

            // VESSEL: a scene change re-uploads every window, not just the new one.
            //
            // The symptom this fixes: launch a program with the session, and the
            // desktop around its window is black instead of the seeded #161826.
            // An empty desktop is correct from the start, and leaving the desktop
            // and coming back paints it correctly for good — which is the whole
            // diagnosis. Coming back destroys the EGL context, every texture
            // fails isAllocated(), and updateFromDrawable() re-uploads from the
            // ByteBuffer. It comes back *right*, so the pixels were in the
            // drawable the entire time and only the texture was stale.
            //
            // Stale because a texture is uploaded once at allocation and after
            // that only when the drawable damages it. The desktop's background
            // paint lands in the window between its texture being allocated
            // (empty) and anything else asking to draw, and nothing damages it
            // again — an idle desktop has nothing to repaint.
            //
            // A map, an unmap or a resize is the one moment we know the scene is
            // wrong, so it is the honest place to distrust every texture in it.
            // The cost is one full upload per window per scene change, and those
            // are user-scale events: opening a window, not drawing in one.
            for (RenderableWindow window : renderableWindows) {
                if (!window.content.isOffscreenStorage()) window.content.getTexture().setNeedsUpdate(true);
            }
        }
    }

    private void collectRenderableWindows(Window window, int x, int y) {
        if (!window.isRenderable()) return;
        if (window != xServer.windowManager.rootWindow && window.attributes.isViewable()) {
            Window parent = window.getParent();
            boolean transparent = window.attributes.isTransparent() || parent.attributes.isTransparent() || parent.isLayered() || window.isLayered();

            if (forceWindowsFullscreen) {
                short width = window.getWidth();
                short height = window.getHeight();
                FullscreenTransformation fullscreenTransformation = null;

                boolean inBounds = width >= ScreenInfo.MIN_WIDTH && height >= ScreenInfo.MIN_HEIGHT && width < xServer.screenInfo.width && height < xServer.screenInfo.height;
                if (window.getType() == Window.Type.NORMAL && inBounds && window.hasNoDecorations()) {
                    fullscreenTransformation = window.getFullscreenTransformation();
                    if (fullscreenTransformation == null) window.setFullscreenTransformation(fullscreenTransformation = new FullscreenTransformation(window));
                    fullscreenTransformation.update(xServer.screenInfo, window.getWidth(), window.getHeight());

                    if (parent != xServer.windowManager.rootWindow && parent.getChildCount() == 1 && parent.hasDecoration(Decoration.BORDER) && parent.hasDecoration(Decoration.TITLE)) {
                        FullscreenTransformation parentFullscreenTransformation = parent.getFullscreenTransformation();
                        if (parentFullscreenTransformation == null) parent.setFullscreenTransformation(parentFullscreenTransformation = new FullscreenTransformation(parent));
                        parentFullscreenTransformation.update(xServer.screenInfo, parent.getWidth(), parent.getHeight());

                        removeRenderableWindow(parent);
                    }
                    else parent.setFullscreenTransformation(null);
                }
                else window.setFullscreenTransformation(null);

                renderableWindows.add(new RenderableWindow(window.getContent(), x, y, transparent, fullscreenTransformation));
            }
            else renderableWindows.add(new RenderableWindow(window.getContent(), x, y, transparent, null));
        }

        if (window.attributes.isRenderSubwindows()) {
            for (Window child : window.getChildren()) {
                collectRenderableWindows(child, child.getX() + x, child.getY() + y);
            }
        }
    }

    private void removeRenderableWindow(Window window) {
        for (int i = 0; i < renderableWindows.size(); i++) {
            if (renderableWindows.get(i).content == window.getContent()) {
                renderableWindows.remove(i);
                break;
            }
        }
    }

    private void updateWindowPosition(Window window) {
        for (RenderableWindow renderableWindow : renderableWindows) {
            if (renderableWindow.content == window.getContent()) {
                renderableWindow.rootX = window.getRootX();
                renderableWindow.rootY = window.getRootY();
                break;
            }
        }
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
        xServerView.requestRender();
    }

    public boolean isCursorVisible() {
        return cursorVisible;
    }

    public float getCursorScale() {
        return cursorScale;
    }

    public void setCursorScale(float cursorScale) {
        this.cursorScale = cursorScale;
    }

    public int getCursorColor() {
        return cursorBackColor;
    }

    public void setCursorColor(int cursorColor) {
        this.cursorBackColor = cursorColor;
        this.cursorForeColor = ColorUtils.calculateLuminance(cursorColor) < 0.5f ? 0xffffff : 0x000000;
    }

    public boolean isScreenOffsetYRelativeToCursor() {
        return screenOffsetYRelativeToCursor;
    }

    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) {
        this.screenOffsetYRelativeToCursor = screenOffsetYRelativeToCursor;
        xServerView.requestRender();
    }

    public boolean isForceWindowsFullscreen() {
        return forceWindowsFullscreen;
    }

    public void setForceWindowsFullscreen(boolean forceWindowsFullscreen) {
        this.forceWindowsFullscreen = forceWindowsFullscreen;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public float getMagnifierZoom() {
        return magnifierZoom;
    }

    public void setMagnifierZoom(float magnifierZoom) {
        this.magnifierZoom = magnifierZoom;
        xServerView.requestRender();
    }

    public int[] getPixelsARGB(int x, int y, int width, int height, boolean flipY) {
        ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(x, y, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer);

        IntBuffer colors = pixelBuffer.asIntBuffer();
        int[] result = new int[width * height];
        if (flipY) {
            for (int i = 0; i < height; i++) {
                colors.position((height - i - 1) * width);
                colors.get(result, i * width, width);
            }
        }
        else colors.get(result);

        for (int i = 0; i < result.length; i++) {
            result[i] = ((result[i] & 0xff00ff00)) | ((result[i] & 0x000000ff) << 16) | ((result[i] & 0x00ff0000) >> 16);
        }
        return result;
    }

    public void takeWindowScreenshot(final Drawable drawable, final Callback<Bitmap> callback) {
        xServerView.queueEvent(() -> {
            synchronized (drawable.renderLock) {
                Texture texture = drawable.getTexture();
                texture.updateFromDrawable();

                int[] framebufferSize = ImageUtils.getScaledSize(drawable.width, drawable.height, 0, 256);

                RenderTarget renderTarget = new RenderTarget();
                renderTarget.allocateFramebuffer(framebufferSize[0], framebufferSize[1]);

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderTarget.getFramebuffer());
                GLES20.glViewport(0, 0, framebufferSize[0], framebufferSize[1]);
                viewportNeedsUpdate = true;
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                ScreenMaterial material = new ScreenMaterial();
                material.use();
                material.setUniformBool(material.uniforms.flipY, texture.isFlipY());
                quadVertices.bind(material.programId);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
                material.setUniformInt(material.uniforms.screenTexture, 0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                quadVertices.disable();

                int[] colors = getPixelsARGB(0, 0, framebufferSize[0], framebufferSize[1], false);
                Bitmap bitmap = Bitmap.createBitmap(colors, framebufferSize[0], framebufferSize[1], Bitmap.Config.ARGB_8888);

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                renderTarget.destroy();
                material.destroy();

                callback.call(bitmap);
            }
        });
        xServerView.requestRender();
    }
}
