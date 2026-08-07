// VESSEL: subset of upstream com/winlator/core/ImageUtils.java. getScaledSize()
// is the only member the renderer uses (GLRenderer, when it caps an offscreen
// framebuffer). Upstream's file also decodes bitmaps from content URIs.
package com.winlator.core;

public abstract class ImageUtils {
    public static int[] getScaledSize(float oldWidth, float oldHeight, float newWidth, float newHeight) {
        if (newWidth > 0 && newHeight == 0) {
            newHeight = (newWidth / oldWidth) * oldHeight;
            newWidth = (newHeight / oldHeight) * oldWidth;
        }
        else if (newWidth == 0 && newHeight > 0) {
            newWidth = (newHeight / oldHeight) * oldWidth;
            newHeight = (newWidth / oldWidth) * oldHeight;
        }
        return new int[]{(int)newWidth, (int)newHeight};
    }
}
