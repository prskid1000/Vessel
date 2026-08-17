package com.winlator.xserver;

import android.util.SparseArray;

import com.winlator.core.Bitmask;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.winlator.xconnector.XInputStream;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadValue;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.events.ConfigureNotify;
import com.winlator.xserver.events.ConfigureRequest;
import com.winlator.xserver.events.DestroyNotify;
import com.winlator.xserver.events.Event;
import com.winlator.xserver.events.Expose;
import com.winlator.xserver.events.MapNotify;
import com.winlator.xserver.events.MapRequest;
import com.winlator.xserver.events.ResizeRequest;
import com.winlator.xserver.events.UnmapNotify;

import java.util.ArrayList;
import java.util.List;

public class WindowManager extends XResourceManager {
    public enum FocusRevertTo {NONE, POINTER_ROOT, PARENT}
    public final Window rootWindow;
    private final SparseArray<Window> windows = new SparseArray<>();
    public final DrawableManager drawableManager;
    private Window focusedWindow;
    private FocusRevertTo focusRevertTo = FocusRevertTo.NONE;
    private final ArrayList<OnWindowModificationListener> onWindowModificationListeners = new ArrayList<>();

    public interface OnWindowModificationListener {
        default void onMapWindow(Window window) {}

        default void onUnmapWindow(Window window) {}

        default void onChangeWindowZOrder(Window window) {}

        default void onUpdateWindowContent(Window window) {}

        default void onUpdateWindowGeometry(Window window, boolean resized) {}

        default void onUpdateWindowAttributes(Window window, Bitmask mask) {}

        default void onModifyWindowProperty(Window window, Property property) {}
    }

    public WindowManager(ScreenInfo screenInfo, DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
        int id = IDGenerator.generate();
        Drawable drawable = drawableManager.createDrawable(id, screenInfo.width, screenInfo.height, drawableManager.getVisual());
        rootWindow = new Window(id, drawable, 0, 0, screenInfo.width, screenInfo.height, null);
        rootWindow.attributes.setMapped(true);
        windows.put(id, rootWindow);
    }

    public Window getWindow(int id) {
        return windows.get(id);
    }

    public ArrayList<Window> findDialogWindows(int id) {
        ArrayList<Window> result = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) {
            Window window = windows.valueAt(i);
            if (window != null && window.getTransientFor() == id && window.isDialogBox()) result.add(window);
        }
        return result;
    }

    public Window findWindowWithProcessId(int processId) {
        for (int i = 0; i < windows.size(); i++) {
            Window window = windows.valueAt(i);
            if (window != null && window.getProcessId() == processId) return window;
        }
        return null;
    }

    public void destroyWindow(int id) {
        Window window = getWindow(id);
        if (window != null && rootWindow.id != id) {
            unmapWindow(window);
            removeAllSubwindowsAndWindow(window);
        }
    }

    private void removeAllSubwindowsAndWindow(Window window) {
        List<Window> children = new ArrayList<>(window.getChildren());
        for (Window child : children) removeAllSubwindowsAndWindow(child);

        Window parent = window.getParent();
        window.sendEvent(Event.STRUCTURE_NOTIFY, new DestroyNotify(window, window));
        parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new DestroyNotify(parent, window));
        windows.remove(window.id);
        if (window.isInputOutput()) drawableManager.removeDrawable(window.getContent().id);
        triggerOnFreeResourceListener(window);
        if (window == focusedWindow) revertFocus();
        parent.removeChild(window);
    }

    public void mapWindow(Window window) {
        if (!window.attributes.isMapped()) {
            Window parent = window.getParent();
            if (!parent.hasEventListenerFor(Event.SUBSTRUCTURE_REDIRECT) || window.attributes.isOverrideRedirect()) {
                window.attributes.setMapped(true);
                window.sendEvent(Event.STRUCTURE_NOTIFY, new MapNotify(window, window));
                parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new MapNotify(parent, window));
                window.sendEvent(Event.EXPOSURE, new Expose(window));
                setWmState(window, WM_STATE_NORMAL); // VESSEL
                triggerOnMapWindow(window);
            }
            else parent.sendEvent(Event.SUBSTRUCTURE_REDIRECT, new MapRequest(parent, window));
        }
    }

    public void unmapWindow(Window window) {
        if (rootWindow.id != window.id && window.attributes.isMapped()) {
            window.attributes.setMapped(false);
            Window parent = window.getParent();
            window.sendEvent(Event.STRUCTURE_NOTIFY, new UnmapNotify(window, window));
            parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new UnmapNotify(parent, window));
            setWmState(window, WM_STATE_ICONIC); // VESSEL
            if (window == focusedWindow) revertFocus();
            triggerOnUnmapWindow(window);
        }
    }

    public void mapSubWindows(Window window) {
        for (Window child : window.getChildren()) mapSubWindows(child);
        mapWindow(window);
    }

    public Window getFocusedWindow() {
        return focusedWindow;
    }

    public void revertFocus() {
        switch (focusRevertTo) {
            case NONE:
                focusedWindow = null;
                break;
            case POINTER_ROOT:
                focusedWindow = rootWindow;
                break;
            case PARENT:
                if (focusedWindow.getParent() != null) focusedWindow = focusedWindow.getParent();
                break;
        }
    }

    public void setFocus(Window focusedWindow, FocusRevertTo focusRevertTo) {
        this.focusedWindow = focusedWindow;
        this.focusRevertTo = focusRevertTo;
    }

    public FocusRevertTo getFocusRevertTo() {
        return focusRevertTo;
    }

    public Window createWindow(int id, Window parent, short x, short y, short width, short height, WindowAttributes.WindowClass windowClass, Visual visual, byte depth, XClient client) throws XRequestError {
        if (windows.indexOfKey(id) >= 0) throw new BadIdChoice(id);

        boolean isInputOutput = false;
        switch (windowClass) {
            case COPY_FROM_PARENT:
                depth = (depth != 0 || !parent.isInputOutput()) ? depth : parent.getContent().visual.depth;
                isInputOutput = parent.isInputOutput();
                break;
            case INPUT_OUTPUT:
                if (parent.isInputOutput()) {
                    depth = depth == 0 ? parent.getContent().visual.depth : depth;
                    isInputOutput = true;
                } else throw new BadMatch();
                break;
            case INPUT_ONLY:
                isInputOutput = false;
                break;
        }

        if (isInputOutput) {
            visual = visual == null ? parent.getContent().visual : visual;
            if (depth != visual.depth) throw new BadMatch();
        }

        Drawable drawable = null;
        if (isInputOutput) {
            drawable = drawableManager.createDrawable(id, width, height, visual);
            if (drawable == null) throw new BadIdChoice(id);
        }

        final Window window = new Window(id, drawable, x, y, width, height, client);
        window.attributes.setWindowClass(windowClass);
        if (drawable != null) drawable.setOnDrawListener(() -> triggerOnUpdateWindowContent(window));
        windows.put(id, window);
        parent.addChild(window);
        triggerOnCreateResourceListener(window);
        return window;
    }

    /**
     * VESSEL: a window that belongs to the server rather than to a client.
     *
     * <p>Clipboard needs one and there was no way to make one. Every route into
     * {@link #windows} starts from `CreateWindow` with an {@link XClient} to bill
     * it to, and X11 moves selection data <em>through a property on the
     * requestor's window</em> — so a server acting as a requestor, which is what
     * the container-to-Android direction requires, has to own a window that the
     * guest can `ChangeProperty` onto and `SendEvent` to.
     *
     * <p>Deliberately **not** parented, and it is the one interesting decision
     * here. Both the compositor ({@link com.winlator.renderer.GLRenderer}) and
     * Vessel's taskbar walk the window tree from the root, and a window in that
     * tree is a window somebody has to decide to skip. Left out of it entirely, it
     * is reachable by id — which is all the protocol needs — and invisible to
     * everything that draws or lists. It has no {@link Drawable} for the same
     * reason: nothing will ever paint it, so allocating a backing buffer would be
     * a pixel-sized lie.
     *
     * <p>Borrowing a client's window instead was the alternative and it is worse
     * in a way that shows up late: the client owns its lifetime, so the shim's
     * requestor would vanish mid-transfer whenever that program exited, and the
     * property the guest is about to write would land on a freed id.
     *
     * <p>Never freed. It lives as long as the server, which is also as long as
     * the clipboard shim.
     */
    public Window createServerWindow() {
        int id = IDGenerator.generate();
        Window window = new Window(id, null, 0, 0, 1, 1, null);
        windows.put(id, window);
        return window;
    }

    private void changeWindowGeometry(Window window, short x, short y, short width, short height) {
        boolean resized = window.getWidth() != width || window.getHeight() != height;
        if (resized && window.hasEventListenerFor(Event.RESIZE_REDIRECT)) {
            window.sendEvent(Event.SUBSTRUCTURE_REDIRECT, new ResizeRequest(window, width, height));
            width = window.getWidth();
            height = window.getHeight();
            resized = false;
        }

        if (resized && window.isInputOutput()) {
            Drawable oldContent = window.getContent();
            drawableManager.removeDrawable(oldContent.id);
            Drawable newContent = drawableManager.createDrawable(oldContent.id, width, height, oldContent.visual);
            newContent.setOffscreenStorage(oldContent.isOffscreenStorage());
            newContent.setOnDrawListener(() -> triggerOnUpdateWindowContent(window));
            window.setContent(newContent);
        }

        if (resized || window.getX() != x || window.getY() != y) {
            window.setX(x);
            window.setY(y);
            window.setWidth(width);
            window.setHeight(height);
            triggerOnUpdateWindowGeometry(window, resized);
        }

        if (resized && window.isInputOutput() && window.attributes.isMapped()) {
            window.sendEvent(new Expose(window));
        }
    }

    private void changeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        Window parent = window.getParent();
        switch (stackMode) {
            case ABOVE:
                parent.moveChildAbove(window, sibling);
                break;
            case BELOW:
                parent.moveChildBelow(window, sibling);
                break;
        }
        triggerOnChangeWindowZOrder(window);
    }

    // VESSEL: the ICCCM half of being a window manager — WM_STATE.
    //
    // Wine decides whether a top-level window is *managed* by asking whether a
    // window manager exists, and a managed Wine then reads WM_STATE to learn
    // what the WM did: NormalState means mapped and ordinary, IconicState means
    // minimised. `can_activate_window` (dlls/winex11.drv/event.c) refuses to
    // activate a window it believes is iconic, and `window_wm_state_notify`
    // waits on the property changing.
    //
    // Without this the vendored server set no WM_STATE at all, so
    // patches/wine/0011 could not be switched on: Wine would treat every window
    // as managed and then wait for state transitions from a window manager that
    // never spoke. The two changes only make sense together.
    //
    // Set on map and on unmap, which is what iconifying *is* in X11 and what
    // the shell's Minimize already does.
    private void setWmState(Window window, int state) {
        if (window == null || window == rootWindow) return;
        int atom = Atom.internAtom(WM_STATE_ATOM_NAME);
        // WM_STATE is two 32-bit values: the state, and the icon window (None).
        byte[] data = new byte[8];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(state).putInt(0);
        window.modifyProperty(atom, atom, Property.Format.INT_ARRAY, Property.Mode.REPLACE, data);
    }

    /** ICCCM WM_STATE values. */
    private static final int WM_STATE_NORMAL = 1;
    private static final int WM_STATE_ICONIC = 3;
    private static final String WM_STATE_ATOM_NAME = "WM_STATE";

    // VESSEL: window-manager-initiated move and resize, for the shell's drag
    // borders. Every existing way into changeWindowGeometry arrives from a
    // client request with an XInputStream to parse, and that method is private,
    // so there was no way for the *server side* to place a window at all.
    //
    // Vessel needs one because patches/wine/0010 strips WS_CAPTION and
    // WS_THICKFRAME from every top-level window: on a phone a caption is too
    // small to hit and cost 41 unpainted rows, so the shell draws temporary drag
    // borders instead and calls this. It is deliberately the same shape as the
    // non-redirect branch of configureWindow below — geometry, then
    // ConfigureNotify to the window and to its parent — because a client must
    // not be able to tell a shell drag from an ordinary WM configure.
    public void moveResizeWindow(Window window, short x, short y, short width, short height) {
        if (width <= 0 || height <= 0) return;

        Window parent = window.getParent();
        if (parent == null) return;

        changeWindowGeometry(window, x, y, width, height);

        boolean overrideRedirect = window.attributes.isOverrideRedirect();
        Window previousSibling = window.previousSibling();
        window.sendEvent(Event.STRUCTURE_NOTIFY, new ConfigureNotify(window, window, previousSibling, x, y, width, height, window.getBorderWidth(), overrideRedirect));
        parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new ConfigureNotify(parent, window, previousSibling, x, y, width, height, window.getBorderWidth(), overrideRedirect));

        // VESSEL: and then ask the client to repaint, which is the half that was
        // missing.
        //
        // A ConfigureNotify tells a client its geometry changed; it does not
        // tell it to draw. A real X server also sends Expose for anything the
        // resize left undefined, and `changeWindowGeometry` above reallocates
        // the backing Drawable at the new size — so after a resize the window's
        // pixels are whatever the new buffer happens to hold until somebody
        // paints them. Wine erases the frame with the class background brush
        // (COLOR_WINDOW, white) and then waits for a WM_PAINT that never comes.
        //
        // Reported as: a white region after dragging a resize border, which
        // clears if you minimise and restore. That is the diagnosis in one
        // sentence — `mapWindow` above *does* send Expose (:110), so remapping
        // produces the repaint that resizing never asked for.
        //
        // The whole window, not the newly exposed strip: the Drawable was
        // reallocated, so none of it is known good, and a shrink exposes no new
        // area yet still needs the client to lay out again.
        window.sendEvent(Event.EXPOSURE, new Expose(window));
    }

    public void configureWindow(Window window, Bitmask valueMask, XInputStream inputStream) throws XRequestError {
        short x = window.getX();
        short y = window.getY();
        short width = window.getWidth();
        short height = window.getHeight();
        short borderWidth = window.getBorderWidth();
        Window sibling = null;
        Window.StackMode stackMode = null;

        for (int index : valueMask) {
            switch (index) {
                case Window.FLAG_X:
                    x = (short)inputStream.readInt();
                    break;
                case Window.FLAG_Y:
                    y = (short)inputStream.readInt();
                    break;
                case Window.FLAG_WIDTH:
                    width = (short)inputStream.readInt();
                    break;
                case Window.FLAG_HEIGHT:
                    height = (short)inputStream.readInt();
                    break;
                case Window.FLAG_BORDER_WIDTH:
                    borderWidth = (short)inputStream.readInt();
                    break;
                case Window.FLAG_SIBLING:
                    sibling = getWindow(inputStream.readInt());
                    break;
                case Window.FLAG_STACK_MODE:
                    stackMode = Window.StackMode.values()[inputStream.readInt()];
                    break;
            }
        }

        if (width <= 0) throw new BadValue(width);
        if (height <= 0) throw new BadValue(height);

        Window parent = window.getParent();
        boolean overrideRedirect = window.attributes.isOverrideRedirect();
        if (!parent.hasEventListenerFor(Event.SUBSTRUCTURE_REDIRECT) || overrideRedirect) {
            changeWindowGeometry(window, x, y, width, height);

            window.setBorderWidth(borderWidth);
            if (stackMode != null) changeWindowZOrder(stackMode, window, sibling);

            Window previousSibling = window.previousSibling();
            window.sendEvent(Event.STRUCTURE_NOTIFY, new ConfigureNotify(window, window, previousSibling, x, y, width, height, borderWidth, overrideRedirect));
            parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new ConfigureNotify(parent, window, previousSibling, x, y, width, height, borderWidth, overrideRedirect));
        }
        else parent.sendEvent(Event.SUBSTRUCTURE_REDIRECT, new ConfigureRequest(parent, window, window.previousSibling(), x, y, width, height, borderWidth, stackMode, valueMask));
    }

    public void reparentWindow(Window window, Window newParent) {
        Window oldParent = window.getParent();
        if (oldParent != null) oldParent.removeChild(window);
        newParent.addChild(window);
    }

    public Window findPointWindow(short rootX, short rootY) {
        return findPointWindow(rootWindow, rootX, rootY, false);
    }

    public Window findPointWindow(short rootX, short rootY, boolean useFullscreenTransformation) {
        return findPointWindow(rootWindow, rootX, rootY, useFullscreenTransformation);
    }

    private Window findPointWindow(Window window, short rootX, short rootY, boolean useFullscreenTransformation) {
        if (!(window.attributes.isMapped() && window.containsPoint(rootX, rootY, useFullscreenTransformation))) return null;
        Window child = window.getChildByCoords(rootX, rootY, useFullscreenTransformation);
        return child != null ? findPointWindow(child, rootX, rootY, useFullscreenTransformation) : window;
    }

    public void addOnWindowModificationListener(OnWindowModificationListener onWindowModificationListener) {
        onWindowModificationListeners.add(onWindowModificationListener);
    }

    public void removeOnWindowModificationListener(OnWindowModificationListener onWindowModificationListener) {
        onWindowModificationListeners.remove(onWindowModificationListener);
    }

    public void triggerOnMapWindow(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onMapWindow(window);
        }
    }

    public void triggerOnUnmapWindow(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUnmapWindow(window);
        }
    }

    public void triggerOnChangeWindowZOrder(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onChangeWindowZOrder(window);
        }
    }

    public void triggerOnUpdateWindowContent(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUpdateWindowContent(window);
        }
    }

    public void triggerOnUpdateWindowGeometry(Window window, boolean resized) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUpdateWindowGeometry(window, resized);
        }
    }

    public void triggerOnUpdateWindowAttributes(Window window, Bitmask mask) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUpdateWindowAttributes(window, mask);
        }
    }

    public void triggerOnModifyWindowProperty(Window window, Property property) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onModifyWindowProperty(window, property);
        }
    }
}