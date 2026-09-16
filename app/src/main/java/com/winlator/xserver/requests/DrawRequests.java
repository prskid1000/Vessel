package com.winlator.xserver.requests;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.GraphicsContext;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadDrawable;
import com.winlator.xserver.errors.BadGraphicsContext;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.events.Expose;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class DrawRequests {
    public enum Format {BITMAP, XY_PIXMAP, Z_PIXMAP}
    private enum CoordinateMode {ORIGIN, PREVIOUS}

    public static void putImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        Format format = Format.values()[client.getRequestData()];
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        byte leftPad = inputStream.readByte();
        byte depth = inputStream.readByte();
        inputStream.skip(2);
        int length = client.getRemainingRequestLength();
        ByteBuffer data = inputStream.readByteBuffer(length);

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        if (!(graphicsContext.getFunction() == GraphicsContext.Function.COPY || format == Format.Z_PIXMAP)) {
            throw new UnsupportedOperationException("GC Function other than COPY is not supported.");
        }

        switch (format) {
            case BITMAP:
                if (leftPad != 0) throw new UnsupportedOperationException("PutImage.leftPad cannot be != 0.");
                if (depth == 1) {
                    drawable.drawImage((short)0, (short)0, dstX, dstY, width, height, (byte)1, data, width, height);
                }
                else throw new BadMatch();
                break;
            case XY_PIXMAP:
                if (drawable.visual.depth != depth) throw new BadMatch();
                break;
            case Z_PIXMAP:
                if (leftPad == 0) {
                    drawable.drawImage((short)0, (short)0, dstX, dstY, width, height, depth, data, width, height);
                }
                else throw new BadMatch();
                break;
        }
    }

    public static void getImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        Format format = Format.values()[client.getRequestData()];
        int drawableId = inputStream.readInt();
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        inputStream.skip(4);

        if (format != Format.Z_PIXMAP) throw new UnsupportedOperationException("Only Z_PIXMAP is supported.");

        Drawable drawable =  client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        int visualId = client.xServer.pixmapManager.getPixmap(drawableId) == null ? drawable.visual.id : 0;
        ByteBuffer data = drawable.getImage(x, y, width, height);
        int length = data.limit();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(drawable.visual.depth);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((length + 3) / 4);
            outputStream.writeInt(visualId);
            outputStream.writePad(20);
            outputStream.write(data);
            if ((-length & 3) > 0) outputStream.writePad(-length & 3);
        }
    }

    public static void clearArea(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        boolean exposures = client.getRequestData() == 1;
        int windowId = inputStream.readInt();
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        short width = inputStream.readShort();
        short height = inputStream.readShort();

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        if (!window.isInputOutput()) throw new BadMatch();

        Drawable drawable = window.getContent();
        drawable.fillRect(x, y, width, height, 0x000000);

        if (exposures) window.sendEvent(new Expose(window));
    }

    public static void copyArea(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int srcDrawableId = inputStream.readInt();
        int dstDrawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short srcX = inputStream.readShort();
        short srcY = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        short width = inputStream.readShort();
        short height = inputStream.readShort();

        Drawable srcDrawable =  client.xServer.drawableManager.getDrawable(srcDrawableId);
        if (srcDrawable == null) throw new BadDrawable(srcDrawableId);

        Drawable dstDrawable =  client.xServer.drawableManager.getDrawable(dstDrawableId);
        if (dstDrawable == null) throw new BadDrawable(dstDrawableId);

        GraphicsContext graphicsContext =  client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        if (srcDrawable.visual.depth != dstDrawable.visual.depth) throw new BadMatch();

        dstDrawable.copyArea(srcX, srcY, dstX, dstY, width, height, srcDrawable, graphicsContext.getFunction());
    }

    /**
     * VESSEL: PolyPoint, core opcode 64.
     *
     * **This one had no handler at all, and the default was fatal.** An
     * unhandled major opcode threw out of the request loop and the server
     * dropped the client, so a single PolyPoint took the display down mid-frame
     * -- measured on 2026-09-16, and the symptoms all arrive somewhere else:
     * Mesa's WSI sees xcb_wait_for_special_event return NULL and reports
     * VK_ERROR_SURFACE_LOST_KHR, DXVK tries to rebuild a swapchain against a
     * dead connection, and the process exits on `XIO: fatal IO error 2`.
     *
     * A point is a one-pixel fill, which is all the primitive there is to use.
     * Foreground, not background: the X11 protocol draws points in the GC's
     * foreground, and polyFillRectangle below using the background instead
     * looks like a bug, but it is left alone here rather than changed blind.
     */
    public static void polyPoint(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        CoordinateMode coordinateMode = CoordinateMode.values()[client.getRequestData()];
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        int length = client.getRemainingRequestLength();
        int color = graphicsContext.getForeground();
        short prevX = 0, prevY = 0;
        boolean first = true;

        while (length >= 4) {
            short x = inputStream.readShort();
            short y = inputStream.readShort();
            // PREVIOUS makes every point after the first relative to the one
            // before it, which is the mode a plotter or a spectrum analyser
            // uses to walk a curve without resending absolute coordinates.
            if (coordinateMode == CoordinateMode.PREVIOUS && !first) {
                x += prevX;
                y += prevY;
            }
            drawable.fillRect(x, y, 1, 1, color);
            prevX = x;
            prevY = y;
            first = false;
            length -= 4;
        }
    }

    /**
     * VESSEL: PolySegment, core opcode 66. Was `client.skipRequest()` -- the
     * request was consumed and nothing was drawn, so anything using it rendered
     * blank rather than wrong, silently. Each segment is an independent pair of
     * endpoints, unlike PolyLine's connected run.
     */
    public static void polySegment(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        int length = client.getRemainingRequestLength();
        int color = graphicsContext.getForeground();
        int lineWidth = Math.max(1, graphicsContext.getLineWidth());

        while (length >= 8) {
            short x1 = inputStream.readShort();
            short y1 = inputStream.readShort();
            short x2 = inputStream.readShort();
            short y2 = inputStream.readShort();
            drawable.drawLine(x1, y1, x2, y2, color, lineWidth);
            length -= 8;
        }
    }

    /**
     * VESSEL: PolyRectangle, core opcode 67. Also was `client.skipRequest()`.
     *
     * The outline only -- PolyFillRectangle is the filled one and already had a
     * handler. Four lines rather than a rectangle primitive because that is what
     * Drawable offers, and the corners are inclusive in X11's model, so the far
     * edges sit at x+width and y+height exactly.
     */
    public static void polyRectangle(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        int length = client.getRemainingRequestLength();
        int color = graphicsContext.getForeground();
        int lineWidth = Math.max(1, graphicsContext.getLineWidth());

        while (length >= 8) {
            short x = inputStream.readShort();
            short y = inputStream.readShort();
            short width = inputStream.readShort();
            short height = inputStream.readShort();
            int x2 = x + width, y2 = y + height;
            drawable.drawLine(x,  y,  x2, y,  color, lineWidth);
            drawable.drawLine(x2, y,  x2, y2, color, lineWidth);
            drawable.drawLine(x2, y2, x,  y2, color, lineWidth);
            drawable.drawLine(x,  y2, x,  y,  color, lineWidth);
            length -= 8;
        }
    }

    public static void polyLine(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        CoordinateMode coordinateMode = CoordinateMode.values()[client.getRequestData()];
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);
        int length = client.getRemainingRequestLength();

        short[] points = new short[length / 2];
        int i = 0;
        while (length != 0) {
            points[i++] = inputStream.readShort();
            points[i++] = inputStream.readShort();
            length -= 4;
        }

        if (coordinateMode == CoordinateMode.ORIGIN && graphicsContext.getLineWidth() > 0) {
            drawable.drawLines(graphicsContext.getForeground(), graphicsContext.getLineWidth(), points);
        }
    }

    /**
     * VESSEL: scanline fill of an arbitrary polygon, for FillPoly and
     * PolyFillArc.
     *
     * Drawable has no polygon primitive, only `fillRect`, so the span is the
     * primitive: for each row, find where the edges cross it, sort the
     * crossings, and fill between them in pairs. That is the even-odd rule,
     * which is what X11's Complex shape means and is also correct for the
     * Convex and Nonconvex shapes a client may declare -- declaring a simpler
     * shape is a promise about the polygon, not a request for different
     * filling, so honouring the general case honours all three.
     *
     * Edges are half-open in y (`y0 <= y < y1`) so that a vertex shared by two
     * edges is counted once rather than twice, which is what stops a horizontal
     * run of single-pixel gaps appearing along shallow diagonals.
     */
    private static void fillPolygon(Drawable drawable, int color, short[] xs, short[] ys, int count) {
        if (count < 3) return;

        int minY = ys[0], maxY = ys[0];
        for (int i = 1; i < count; i++) {
            if (ys[i] < minY) minY = ys[i];
            if (ys[i] > maxY) maxY = ys[i];
        }

        int[] crossings = new int[count];
        for (int y = minY; y <= maxY; y++) {
            int n = 0;
            for (int i = 0, j = count - 1; i < count; j = i++) {
                int y0 = ys[j], y1 = ys[i];
                if (y0 == y1) continue;
                // Half-open: the lower endpoint belongs to this edge, the upper
                // one does not.
                if ((y >= Math.min(y0, y1)) && (y < Math.max(y0, y1))) {
                    int x0 = xs[j], x1 = xs[i];
                    crossings[n++] = x0 + (int) ((long) (y - y0) * (x1 - x0) / (y1 - y0));
                }
            }
            if (n < 2) continue;
            java.util.Arrays.sort(crossings, 0, n);
            for (int i = 0; i + 1 < n; i += 2) {
                int spanStart = crossings[i], spanEnd = crossings[i + 1];
                if (spanEnd >= spanStart) drawable.fillRect(spanStart, y, spanEnd - spanStart + 1, 1, color);
            }
        }
    }

    /**
     * VESSEL: the points along one X11 arc, as a polyline.
     *
     * An X11 arc is a bounding box plus two angles in 64ths of a degree, the
     * second being a delta rather than an end. Angles run counter-clockwise
     * from three o'clock while y grows downward, so the sine is negated. The
     * step is chosen from the arc's size rather than fixed: a small arc wants
     * few segments and a large one would show the corners.
     */
    private static int arcPoints(int x, int y, int width, int height, int angle1, int angle2,
                                 short[] xs, short[] ys) {
        double cx = x + width / 2.0, cy = y + height / 2.0;
        double rx = width / 2.0, ry = height / 2.0;
        double start = Math.toRadians(angle1 / 64.0), sweep = Math.toRadians(angle2 / 64.0);

        int segments = (int) Math.max(8, Math.min(xs.length - 2, Math.abs(sweep) * Math.max(rx, ry) / 2));
        for (int i = 0; i <= segments; i++) {
            double t = start + sweep * i / segments;
            xs[i] = (short) Math.round(cx + rx * Math.cos(t));
            ys[i] = (short) Math.round(cy - ry * Math.sin(t));
        }
        return segments + 1;
    }

    /**
     * VESSEL: PolyArc, core opcode 68. Outlines only; PolyFillArc fills.
     *
     * The curve is walked as short straight segments because `drawLine` is the
     * primitive available, which is the same approach the arc gets everywhere
     * else in this file.
     */
    public static void polyArc(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        int length = client.getRemainingRequestLength();
        int color = graphicsContext.getForeground();
        int lineWidth = Math.max(1, graphicsContext.getLineWidth());
        short[] xs = new short[258], ys = new short[258];

        while (length >= 12) {
            short ax = inputStream.readShort();
            short ay = inputStream.readShort();
            short aw = inputStream.readShort();
            short ah = inputStream.readShort();
            short a1 = inputStream.readShort();
            short a2 = inputStream.readShort();
            length -= 12;
            if (aw <= 0 || ah <= 0) continue;

            int n = arcPoints(ax, ay, aw, ah, a1, a2, xs, ys);
            for (int i = 1; i < n; i++) {
                drawable.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], color, lineWidth);
            }
        }
    }

    /**
     * VESSEL: PolyFillArc, core opcode 71.
     *
     * The arc is turned into a polygon and filled by the scanline routine
     * above. The polygon is closed through the centre -- a pie slice -- because
     * ArcPieSlice is X11's default arc-mode and this GraphicsContext carries no
     * arc-mode field to consult; a client that set ArcChord gets a pie where it
     * asked for a chord, which is wrong in the filled region near the centre
     * and right everywhere else. Storing the mode on the GC is what would fix
     * it properly.
     */
    public static void polyFillArc(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        int length = client.getRemainingRequestLength();
        int color = graphicsContext.getForeground();
        short[] xs = new short[258], ys = new short[258];

        while (length >= 12) {
            short ax = inputStream.readShort();
            short ay = inputStream.readShort();
            short aw = inputStream.readShort();
            short ah = inputStream.readShort();
            short a1 = inputStream.readShort();
            short a2 = inputStream.readShort();
            length -= 12;
            if (aw <= 0 || ah <= 0) continue;

            int n = arcPoints(ax, ay, aw, ah, a1, a2, xs, ys);
            // A full ellipse already closes on itself; anything less needs the
            // centre to become a slice rather than a lens.
            if (Math.abs(a2) < 360 * 64 && n < xs.length) {
                xs[n] = (short) Math.round(ax + aw / 2.0);
                ys[n] = (short) Math.round(ay + ah / 2.0);
                n++;
            }
            fillPolygon(drawable, color, xs, ys, n);
        }
    }

    /**
     * VESSEL: FillPoly, core opcode 69.
     *
     * The shape and coordinate-mode bytes follow the GC id rather than riding in
     * the request's data byte, which is why they are read here and not taken
     * from `client.getRequestData()`.
     */
    public static void fillPoly(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        inputStream.readByte();                                   // shape: see fillPolygon
        int coordinateMode = inputStream.readByte() & 0xff;       // 0 Origin, 1 Previous
        inputStream.skip(2);

        int length = client.getRemainingRequestLength();
        int count = length / 4;
        if (count < 3) {
            inputStream.skip(length);
            return;
        }

        short[] xs = new short[count], ys = new short[count];
        short prevX = 0, prevY = 0;
        for (int i = 0; i < count; i++) {
            short px = inputStream.readShort();
            short py = inputStream.readShort();
            if (coordinateMode == 1 && i > 0) {
                px += prevX;
                py += prevY;
            }
            xs[i] = px;
            ys[i] = py;
            prevX = px;
            prevY = py;
        }

        fillPolygon(drawable, graphicsContext.getForeground(), xs, ys, count);
    }

    public static void polyFillRectangle(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);
        int length = client.getRemainingRequestLength();

        while (length != 0) {
            short x = inputStream.readShort();
            short y = inputStream.readShort();
            short width = inputStream.readShort();
            short height = inputStream.readShort();
            drawable.fillRect(x, y, width, height, graphicsContext.getBackground());
            length -= 8;
        }
    }
}