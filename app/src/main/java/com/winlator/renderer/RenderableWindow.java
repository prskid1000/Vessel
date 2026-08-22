package com.winlator.renderer;

import com.winlator.xserver.Drawable;

class RenderableWindow {
    final Drawable content;
    short rootX;
    short rootY;

    /**
     * VESSEL: where this window was at the previous composite.
     *
     * <p>The cheapest frame generation available to us, and the one nothing else
     * on Android can do: we *are* the compositor, so when a window moves we know
     * the translation exactly rather than estimating it. A synthesised frame
     * re-composites the same window textures at positions carried forward by
     * (root - previousRoot) -- which is not a prediction of the motion, it is the
     * motion, replayed a fraction of a frame further on.
     *
     * <p>A dragged window, a panel sliding out, a document whose scroll moves its
     * own window: all reproject exactly, with no block matching, no warping and
     * no inpainting, and therefore none of the artefacts those bring. What this
     * cannot do is advance the *content* of a window, so a video playing inside a
     * stationary window is untouched -- which is what the later tiers are for.
     */
    short previousRootX;
    short previousRootY;
    final boolean transparent;
    final FullscreenTransformation fullscreenTransformation;

    public RenderableWindow(Drawable content, int rootX, int rootY, boolean transparent, FullscreenTransformation fullscreenTransformation) {
        this.content = content;
        this.rootX = (short)rootX;
        this.rootY = (short)rootY;
        this.previousRootX = (short)rootX;
        this.previousRootY = (short)rootY;
        this.transparent = transparent;
        this.fullscreenTransformation = fullscreenTransformation;
    }
}
