// VESSEL: this file is Vessel's, not upstream's.
//
// Upstream WinHandler is a ~700-line UDP client for `winhandler.exe`, a helper
// Winlator injects into the container to do raw mouse input, gamepad forwarding
// and window activation through the Win32 API. Vessel does not ship that helper
// yet, so what is vendored here is only the *shape* of the dependency: the two
// methods the X server actually calls, as an interface.
//
// Keeping the type name and package means DesktopHelper.java and
// InputDeviceManager.java stay byte-identical to upstream, so their future diffs
// still apply. When Vessel grows an equivalent helper, implement this interface
// and hand it to XServer.setWinHandler(); nothing else has to change.
package com.winlator.winhandler;

public interface WinHandler {
    /**
     * Injects a Win32 mouse_event into the guest. Called only while the X server
     * is in relative-pointer mode, where the X protocol's absolute coordinates
     * are useless and the guest wants raw deltas instead.
     *
     * @param flags one or more {@link MouseEventFlags} values
     * @param dx    relative X movement, in pixels
     * @param dy    relative Y movement, in pixels
     * @param wheelDelta signed wheel notches times 120, or 0
     */
    void mouseEvent(int flags, int dx, int dy, int wheelDelta);

    /**
     * Asks the guest to activate a top-level window. The X server knows which
     * window the user focused; only the guest can make Win32 agree.
     *
     * @param processName the window's WM_CLASS
     * @param handle      the guest HWND, as recorded on the X window
     */
    void bringToFront(String processName, long handle);

    /**
     * Used until a real helper exists. XServer returns this rather than null so
     * the upstream call sites, which never null-check, cannot crash.
     */
    WinHandler NULL = new WinHandler() {
        @Override
        public void mouseEvent(int flags, int dx, int dy, int wheelDelta) {}

        @Override
        public void bringToFront(String processName, long handle) {}
    };
}
