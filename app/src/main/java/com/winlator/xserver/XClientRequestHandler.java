package com.winlator.xserver;

import com.winlator.xconnector.ConnectedClient;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import android.util.Log;

import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.extensions.Extension;
import com.winlator.xserver.requests.AtomRequests;
import com.winlator.xserver.requests.CursorRequests;
import com.winlator.xserver.requests.DrawRequests;
import com.winlator.xserver.requests.ExtensionRequests;
import com.winlator.xserver.requests.FontRequests;
import com.winlator.xserver.requests.GrabRequests;
import com.winlator.xserver.requests.GraphicsContextRequests;
import com.winlator.xserver.requests.KeyboardRequests;
import com.winlator.xserver.requests.PixmapRequests;
import com.winlator.xserver.requests.SelectionRequests;
import com.winlator.xserver.requests.WindowRequests;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class XClientRequestHandler implements RequestHandler {
    public static final byte RESPONSE_CODE_ERROR = 0;
    public static final byte RESPONSE_CODE_SUCCESS = 1;
    public static final int MAX_REQUEST_LENGTH = 65535;

    @Override
    public boolean handleRequest(ConnectedClient client) throws IOException {
        XClient xClient = (XClient)client;
        XInputStream inputStream = client.getInputStream();
        XOutputStream outputStream = client.getOutputStream();

        if (xClient.isAuthenticated()) {
            return handleNormalRequest(xClient, inputStream, outputStream);
        }
        else return handleAuthRequest(xClient, inputStream, outputStream);
    }

    private void sendServerInformation(XClient client, XOutputStream outputStream) throws IOException {
        short vendorNameLength = (short)XServer.VENDOR_NAME.length();
        byte pixmapFormatCount = (byte)client.xServer.pixmapManager.supportedPixmapFormats.length;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(XServer.VERSION);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writeInt(1);
            outputStream.writeInt(client.resourceIDBase);
            outputStream.writeInt(client.xServer.resourceIDs.idMask);
            outputStream.writeInt(256);
            outputStream.writeShort(vendorNameLength);
            outputStream.writeShort((short)MAX_REQUEST_LENGTH);
            outputStream.writeByte((byte)1);
            outputStream.writeByte(pixmapFormatCount);
            outputStream.writeByte((byte)0);
            outputStream.writeByte((byte)0);
            outputStream.writeByte((byte)32);
            outputStream.writeByte((byte)32);
            outputStream.writeByte((byte)Keyboard.MIN_KEYCODE);
            outputStream.writeByte((byte)Keyboard.MAX_KEYCODE);
            outputStream.writeInt(0);
            outputStream.writeString8(XServer.VENDOR_NAME);

            for (PixmapFormat pixmapFormat : client.xServer.pixmapManager.supportedPixmapFormats) {
                outputStream.writeByte(pixmapFormat.depth);
                outputStream.writeByte(pixmapFormat.bitsPerPixel);
                outputStream.writeByte(pixmapFormat.scanlinePad);
                outputStream.writePad(5);
            }

            Visual rootVisual = client.xServer.windowManager.rootWindow.getContent().visual;

            outputStream.writeInt(client.xServer.windowManager.rootWindow.id);
            outputStream.writeInt(0);
            outputStream.writeInt(0xffffff);
            outputStream.writeInt(0x000000);
            outputStream.writeInt(client.xServer.windowManager.rootWindow.getAllEventMasks().getBits());
            outputStream.writeShort(client.xServer.screenInfo.width);
            outputStream.writeShort(client.xServer.screenInfo.height);
            outputStream.writeShort(client.xServer.screenInfo.getWidthInMillimeters());
            outputStream.writeShort(client.xServer.screenInfo.getHeightInMillimeters());
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)1);
            outputStream.writeInt(rootVisual.id);
            outputStream.writeByte((byte)0);
            outputStream.writeByte((byte)0);
            outputStream.writeByte(rootVisual.depth);
            outputStream.writeByte((byte)client.xServer.pixmapManager.supportedVisuals.length);

            for (Visual visual : client.xServer.pixmapManager.supportedVisuals) {
                outputStream.writeByte(visual.depth);
                outputStream.writeByte((byte)0);
                outputStream.writeShort((short)(visual.displayable ? 1 : 0));
                outputStream.writeInt(0);

                if (visual.displayable) {
                    outputStream.writeInt(visual.id);
                    outputStream.writeByte((byte)visual.type.ordinal());
                    outputStream.writeByte(visual.bitsPerRGBValue);
                    outputStream.writeShort(visual.colormapEntries);
                    outputStream.writeInt(visual.redMask);
                    outputStream.writeInt(visual.greenMask);
                    outputStream.writeInt(visual.blueMask);
                    outputStream.writeInt(0);
                }
            }

            short additionalDataLength = (short)((outputStream.length() - 8) / 4);
            outputStream.writeAt(6, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(additionalDataLength).array());
        }
    }

    private boolean handleAuthRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        if (inputStream.available() < 12) return false;
        inputStream.skip(2);

        short majorVersion = inputStream.readShort();
        if (majorVersion != 11) throw new UnsupportedOperationException("Unsupported major X protocol version "+majorVersion+".");

        inputStream.skip(2);
        short nameLength = inputStream.readShort();
        short dataLength = inputStream.readShort();
        inputStream.skip(2);

        if (nameLength > 0) inputStream.readString8(nameLength);
        if (dataLength > 0) inputStream.readString8(dataLength);

        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            sendServerInformation(client, outputStream);
        }

        client.setAuthenticated(true);
        return true;
    }

    private boolean handleNormalRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        if (inputStream.available() < 4) return false;
        byte opcode = inputStream.readByte();
        byte requestData = inputStream.readByte();

        int requestLength = inputStream.readUnsignedShort();
        if (requestLength != 0) {
            requestLength = requestLength * 4 - 4;
        }
        else if (inputStream.available() < 4) {
            return false;
        }
        else requestLength = inputStream.readInt() * 4 - 8;
        if (inputStream.available() < requestLength) return false;

        client.generateSequenceNumber();
        client.setRequestData(requestData);
        client.setRequestLength(requestLength);

        try {
            if (opcode < 0) {
                Extension extension = client.xServer.getExtension(opcode);
                if (extension != null) extension.handleRequest(client, inputStream, outputStream);
            }
            else {
                switch (opcode) {
                    case ClientOpcodes.CREATE_WINDOW:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.INPUT_DEVICE, XServer.Lockable.CURSOR_MANAGER)) {
                            WindowRequests.createWindow(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CHANGE_WINDOW_ATTRIBUTES:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
                            WindowRequests.changeWindowAttributes(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.GET_WINDOW_ATTRIBUTES:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.getWindowAttributes(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.DESTROY_WINDOW:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.destroyWindow(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.DESTROY_SUB_WINDOWS:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.destroySubWindows(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.REPARENT_WINDOW:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.reparentWindow(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.MAP_WINDOW:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.mapWindow(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.MAP_SUB_WINDOWS:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.mapSubWindows(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.UNMAP_WINDOW:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.unmapWindow(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CONFIGURE_WINDOW:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.configureWindow(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.GET_GEOMETRY:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                            WindowRequests.getGeometry(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.QUERY_TREE:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.queryTree(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.INTERN_ATOM:
                        AtomRequests.internAtom(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.GET_ATOM_NAME:
                        AtomRequests.getAtomName(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.CHANGE_PROPERTY:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.changeProperty(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.DELETE_PROPERTY:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.deleteProperty(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.GET_PROPERTY:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.getProperty(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.SET_SELECTION_OWNER:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            SelectionRequests.setSelectionOwner(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.GET_SELECTION_OWNER:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            SelectionRequests.getSelectionOwner(client, inputStream, outputStream);
                        }
                        break;
                    // VESSEL: ConvertSelection. The lock is WINDOW_MANAGER
                    // because the handler both reads the selection table and
                    // writes a property onto the requestor's window, which is the
                    // same pair CHANGE_PROPERTY above takes.
                    case ClientOpcodes.CONVERT_SELECTION:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            SelectionRequests.convertSelection(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.SEND_EVENT:
                        try (XLock lock = client.xServer.lockAll()) {
                            WindowRequests.sendEvent(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.GRAB_POINTER:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE, XServer.Lockable.CURSOR_MANAGER)) {
                            GrabRequests.grabPointer(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.UNGRAB_POINTER:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            GrabRequests.ungrabPointer(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.QUERY_POINTER:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.queryPointer(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.TRANSLATE_COORDINATES:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.translateCoordinates(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.WARP_POINTER:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            WindowRequests.warpPointer(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.SET_INPUT_FOCUS:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.setInputFocus(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.GET_INPUT_FOCUS:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                            WindowRequests.getInputFocus(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.OPEN_FONT:
                        FontRequests.openFont(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.LIST_FONTS:
                        FontRequests.listFonts(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.CREATE_PIXMAP:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                            PixmapRequests.createPixmap(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.FREE_PIXMAP:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                            PixmapRequests.freePixmap(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CREATE_GC:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            GraphicsContextRequests.createGC(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CHANGE_GC:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            GraphicsContextRequests.changeGC(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.SET_DASHES:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.SET_CLIP_RECTANGLES:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.FREE_GC:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            GraphicsContextRequests.freeGC(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CLEAR_AREA:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                            DrawRequests.clearArea(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.COPY_AREA:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            DrawRequests.copyArea(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.POLY_LINE:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            DrawRequests.polyLine(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.POLY_POINT:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            DrawRequests.polyPoint(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.POLY_SEGMENT:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            DrawRequests.polySegment(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.POLY_RECTANGLE:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            DrawRequests.polyRectangle(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.POLY_ARC:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            DrawRequests.polyArc(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.FILL_POLY:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            DrawRequests.fillPoly(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.POLY_FILL_ARC:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            DrawRequests.polyFillArc(client, inputStream, outputStream);
                        }
                        break;
                    // VESSEL: CopyPlane is the one core drawing request still
                    // consumed rather than drawn, and it is not for want of a
                    // rasteriser -- the geometry requests above are all served
                    // by `fillRect` and `drawLine` and needed nothing else.
                    // CopyPlane reads *one bit plane* out of the source and maps
                    // set to foreground and clear to background, so it needs the
                    // source's pixels unpacked at the source's depth, which is
                    // the one thing Drawable does not expose. The common use --
                    // a depth-1 bitmap stencilled in two colours -- is already
                    // covered by drawAlphaMaskedBitmap, so the missing piece is
                    // the general case rather than the one applications reach
                    // for. Skipping leaves that draw blank; it does not end the
                    // connection.
                    case ClientOpcodes.COPY_PLANE:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.POLY_FILL_RECTANGLE:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            DrawRequests.polyFillRectangle(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.PUT_IMAGE:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                            DrawRequests.putImage(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.GET_IMAGE:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                            DrawRequests.getImage(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CREATE_COLORMAP:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.FREE_COLORMAP:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.CREATE_CURSOR:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
                            CursorRequests.createCursor(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.CREATE_GLYPH_CURSOR:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.FREE_CURSOR:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
                            CursorRequests.freeCursor(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.QUERY_EXTENSION:
                        ExtensionRequests.queryExtension(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.GET_KEYBOARD_MAPPING:
                        try (XLock lock = client.xServer.lock(XServer.Lockable.INPUT_DEVICE)) {
                            KeyboardRequests.getKeyboardMapping(client, inputStream, outputStream);
                        }
                        break;
                    case ClientOpcodes.BELL:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.SET_SCREEN_SAVER:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.GET_SCREEN_SAVER:
                        WindowRequests.getScreenSaver(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.FORCE_SCREEN_SAVER:
                        client.skipRequest();
                        break;
                    case ClientOpcodes.GET_POINTER_MAPPING:
                        CursorRequests.getPointerMapping(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.GET_MODIFIER_MAPPING:
                        KeyboardRequests.getModifierMapping(client, inputStream, outputStream);
                        break;
                    case ClientOpcodes.NO_OPERATION:
                        client.skipRequest();
                        break;
                    default:
                        // VESSEL: **a request this server does not implement is
                        // an error to answer, not a connection to drop.**
                        //
                        // This used to throw UnsupportedOperationException, a
                        // RuntimeException rather than an XRequestError, so it
                        // bypassed the catch below entirely: no error reply was
                        // ever written, the request handler unwound, and
                        // XConnectorEpoll closed the client. From the other end
                        // that is "X connection to :0 broken" with no protocol
                        // error in front of it, which is what the first
                        // zero-copy attempt presented as — and what Caribbean
                        // Legend died of, measured on 2026-09-16: one PolyPoint
                        // (major 64, a *core* request and not an extension at
                        // all) took the whole connection down mid-frame. The
                        // symptoms arrive in the wrong order and none of them
                        // names the cause: Mesa's WSI sees
                        // xcb_wait_for_special_event return NULL and reports
                        // VK_ERROR_SURFACE_LOST_KHR, DXVK tries to recreate a
                        // swapchain against a dead display, and the process
                        // exits on `XIO: fatal IO error 2` after a request
                        // count that is identical every run.
                        //
                        // BadImplementation (code 17) is exactly what the
                        // protocol reserves for this, and being an XRequestError
                        // it reaches the catch below: the request is skipped,
                        // the error is written, and the connection survives. A
                        // client that asked for something unimplemented gets a
                        // failure for that one request, which is a thing
                        // applications are written to cope with. Losing the
                        // display is not.
                        //
                        // Unregistered extensions land here too — the set is
                        // BigReq, MIT-SHM, DRI3, Present, SYNC and Composite
                        // (XServer.java), notably not XFIXES — and they want
                        // the same answer for the same reason.
                        Log.w(XRequestError.PROTO_TAG, "unimplemented major opcode " + opcode +
                                " (minor " + client.getRequestData() +
                                ") — answering BadImplementation and keeping the connection");
                        throw new BadImplementation();
                }
            }
        }
        catch (XRequestError e) {
            client.skipRequest();
            e.sendError(client, opcode);
        }

        return true;
    }
}
