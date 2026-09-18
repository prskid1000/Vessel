package com.winlator.xserver;

import com.winlator.core.Callback;
import com.winlator.core.CursorLocker;
import com.winlator.renderer.GLRenderer;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.extensions.BigReqExtension;
import com.winlator.xserver.extensions.DRI3Extension;
import com.winlator.xserver.extensions.Extension;
import com.winlator.xserver.extensions.MITSHMExtension;
import com.winlator.xserver.extensions.PresentExtension;
import com.winlator.xserver.extensions.SyncExtension;
import com.winlator.xserver.extensions.XComposite;
import com.winlator.xserver.extensions.XFixesExtension;

import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    private final Extension[] extensions;
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    // VESSEL: the server's own participant in the selection protocol, which is
    // what makes clipboard work in either direction. Always present and inert
    // until a host installs a Bridge; see ClipboardSelection.
    public final ClipboardSelection clipboard;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    public final CursorLocker cursorLocker;
    private SHMSegmentManager shmSegmentManager;
    private GLRenderer renderer;
    // VESSEL: never null. Upstream's DesktopHelper and InputDeviceManager call
    // straight through the result of getWinHandler() without a null check, and
    // Vessel has no guest-side helper to install yet.
    private WinHandler winHandler = WinHandler.NULL;
    // VESSEL: replaces upstream's `public final XServerDisplayActivity activity`,
    // whose only use inside the server was reaching the debug dialog. Vessel's
    // display host is a Service, not that Activity, so the dependency is
    // inverted into a sink the host may set.
    private Callback<String> debugSink;
    private final EnumMap<Lockable, OwnedLock> locks = new EnumMap<>(Lockable.class);

    // Vessel (change 34): a ReentrantLock that will say who holds it. getOwner()
    // is protected on ReentrantLock and exists for exactly this -- monitoring.
    private static final class OwnedLock extends ReentrantLock {
        // Vessel (change 35): fair. An unfair lock lets the thread that just
        // released it take it straight back, and the X server's request thread
        // does exactly that from one present to the next -- an input thread
        // waiting behind it measured up to 312 ms, several whole frames, not
        // one. Fair ordering costs a little throughput under contention and
        // hands the lock to whoever has waited longest.
        OwnedLock() {
            super(true);
        }

        Thread owner() {
            return getOwner();
        }
    }

    /**
     * The thread holding [lockable] right now, or null. Diagnostic only, and
     * racy by nature: the answer may be stale the moment it returns. Used to
     * catch whoever keeps the window-manager lock long enough to stall input.
     */
    public Thread ownerOf(Lockable lockable) {
        return locks.get(lockable).owner();
    }
    private boolean relativeMouseMovement = false;

    public XServer(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
        cursorLocker = new CursorLocker(this);
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new OwnedLock());

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        // VESSEL: after selectionManager and windowManager, which it reaches
        // through this server. It creates nothing until a selection is claimed.
        clipboard = new ClipboardSelection(this);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);

        DesktopHelper.attachTo(this);
        extensions = setupExtensions();
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        cursorLocker.setEnabled(!relativeMouseMovement);
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public void setRenderer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler != null ? winHandler : WinHandler.NULL;
    }

    // VESSEL: see debugSink above.
    public void setDebugSink(Callback<String> debugSink) {
        this.debugSink = debugSink;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final OwnedLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    public Extension getExtensionByName(String name) {
        for (Extension extension : extensions) if (extension.getName().equals(name)) return extension;
        return null;
    }

    // VESSEL: give every extension the chance to drop what it holds for a
    // client that has disconnected. See Extension.freeClientResources for why
    // this is a correctness problem and not housekeeping — an extension's
    // state is keyed on XIDs the next client will be handed again.
    public void freeClientExtensionResources(XClient client) {
        for (Extension extension : extensions) extension.freeClientResources(client);
    }

    public void injectPointerMove(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            int dx = x - pointer.getX();
            int dy = y - pointer.getY();
            pointer.setPosition(x, y);
            sendRawMotion(dx, dy);
        }
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(pointer.getX() + dx, pointer.getY() + dy);
            sendRawMotion(dx, dy);
        }
    }

    /**
     * Vessel (change 36): the motion that was asked for, before the pointer
     * was clamped to the screen, as a `_VESSEL_RAW_MOTION` ClientMessage to the
     * focused window.
     *
     * Raw mouse input is how a game that locks the cursor reads the mouse, and
     * on Windows it comes from the device, not from the cursor -- it does not
     * stop at the edge of the screen. This server has no XInput2, so Wine's
     * only source of raw input was the pointer's *position*, and the position
     * is clamped: Empyrion's mouse-look turned until the pointer reached the
     * screen edge, about 640 px from the centre, and then stopped -- "look
     * left and right only very little". The requested delta is not clamped;
     * patches/wine/0080 turns this message into raw input and stops deriving
     * raw motion from positions once it has seen one.
     *
     * Sent to the focused window because Wine only dispatches a ClientMessage
     * to a window it can map to an HWND, and the game's toplevel is the one
     * with focus. A client that does not know the atom ignores the message.
     */
    private void sendRawMotion(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        Window focus = windowManager.getFocusedWindow();
        if (focus == null || focus == windowManager.rootWindow) return;
        if (rawMotionAtom == 0) rawMotionAtom = Atom.internAtom("_VESSEL_RAW_MOTION");
        focus.sendClientMessage(rawMotionAtom, dx, dy);
    }

    private int rawMotionAtom;

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);
        }
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, false);
        }
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(xKeycode.id, keysym);
        }
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(xKeycode.id);
        }
    }

    // VESSEL: the same two calls, addressed by raw keycode.
    //
    // XKeycode is a closed enum of the keys upstream's soft keyboard and its
    // gamepad profiles can produce, and it has no entry for Super, Menu or
    // PrtScn — keys a Bluetooth keyboard has and a Windows guest reads. Going
    // around it via keyboard.setKeyPress directly would skip the lock these two
    // take, and the callback that runs under it reaches into windowManager.
    //
    // Additive only: nothing above changes, so upstream diffs still apply. The
    // byte is deliberate rather than an int silently narrowed — see
    // app.vessel.input.X11.MAX_KEYCODE for why 128 is not a keycode here.
    public void injectKeyPress(byte keycode, int keysym) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(keycode, keysym);
        }
    }

    public void injectKeyRelease(byte keycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(keycode);
        }
    }

    private Extension[] setupExtensions() {
        byte opcode = Extension.START_MAJOR_OPCODE;
        return new Extension[]{
            new BigReqExtension(this, opcode--),
            new MITSHMExtension(this, opcode--),
            new DRI3Extension(this, opcode--),
            new PresentExtension(this, opcode--),
            new SyncExtension(this, opcode--),
            new XComposite(this, opcode--),
            // VESSEL: XFIXES, for Mesa's DRI3 swapchain. Not optional and not
            // cosmetic — Mesa calls xcb_xfixes_create_region per swapchain
            // image without checking whether the server has the extension, and
            // libxcb answers a missing extension by closing the connection
            // client-side (XCB_CONN_CLOSED_EXT_NOTSUPPORTED) before a byte is
            // sent. That is unfindable from this side: no request arrives, so
            // no error can be logged. See XFixesExtension's own comment.
            new XFixesExtension(this, opcode--)
            // VESSEL: no GLXExtension. Upstream's implementation is a thin
            // dispatcher onto libgladiorenderer, a ~5k-line GL-over-a-socket
            // translator that exists because Winlator's guest opengl32 is a
            // stub. Vessel builds Mesa/Zink as a real ARM64EC opengl32.dll, so
            // the guest never speaks GLX in the first place — winex11 sees the
            // extension absent and does not offer a GLX visual, which is the
            // outcome we want. Dropping it also drops the second .so.
        };
    }

    public <T extends Extension> T getExtension(byte opcode) {
        int index = Extension.START_MAJOR_OPCODE - opcode;
        return (T)extensions[index];
    }

    public void debugPrint(String line) {
        Callback<String> sink = debugSink;
        if (sink != null) sink.call("xserver:"+line);
    }
}
