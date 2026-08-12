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
    public final ViewTransformation viewTransformation = new ViewTransformation();
    private final Drawable rootCursorDrawable;
    private final ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private boolean forceWindowsFullscreen;
    private boolean fullscreen = false;
    private boolean toggleFullscreen = false;
    protected boolean viewportNeedsUpdate = true;
    private boolean cursorVisible = true;
    private float cursorScale = 1.0f;
    private int cursorBackColor = 0xffffff;
    private int cursorForeColor = 0x000000;
    private boolean screenOffsetYRelativeToCursor = false;
    private float magnifierZoom = 1.0f;
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

    @Override
    public void onDrawFrame(GL10 gl) {
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

        if (effectComposer.hasEffects()) {
            effectComposer.render();
        }
        else drawFrame();

        if (listener != null) listener.onFrameEnd();
    }

    protected void drawFrame() {
        if (viewportNeedsUpdate) {
            if (fullscreen) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            }
            else GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
            viewportNeedsUpdate = false;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

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

        renderWindows();
        if (cursorVisible) renderCursor();
    }

    @Override
    public void onMapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUnmapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onChangeWindowZOrder(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowGeometry(final Window window, boolean resized) {
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
    private boolean useSGSRFor(Drawable drawable, float destWidth, float destHeight, boolean transparent) {
        if (transparent) return false;
        if (drawable.width <= 0 || drawable.height <= 0) return false;
        if (!SGSRMaterial.isSupported()) return false;

        float scaleX;
        float scaleY;
        if (fullscreen) {
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

    private void renderWindows() {
        // VESSEL: the material is chosen per window now, so the pass opens with
        // nothing bound rather than with the bilinear one bound unconditionally.
        boundWindowMaterial = null;

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            for (RenderableWindow window : renderableWindows) {
                if (!window.content.isOffscreenStorage()) {
                    renderWindowDrawable(window.content, window.rootX, window.rootY, window.transparent, window.fullscreenTransformation);
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
