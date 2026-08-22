package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.XServer;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    private final GLRenderer renderer;

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer = new GLRenderer(this, xServer);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    /**
     * VESSEL: draw now, without going through the container's frame limiter.
     *
     * <p>**A frame that has already been paced must not be paced twice.** Vessel
     * subclasses this view to apply {@code display.fpsLimit}, and that override
     * drops or re-posts through a {@code Handler} any {@code requestRender} that
     * arrives sooner than the limit allows. That is right for the guest's own
     * damage, which arrives whenever the guest feels like it, and wrong for a
     * synthesised frame, which {@code FramePacer} has just finished aiming at a
     * specific vsync -- re-posting it through a delay queue is precisely the
     * scheduling the pacer exists to avoid, and it scattered the frames the
     * feature adds.
     *
     * <p>The rate is unaffected, because the source rate already is: the guest is
     * capped at a fraction of the limit and the synthesised frames fill the gaps
     * that division leaves.
     */
    public void requestRenderUnpaced() {
        requestRender();
    }
}
