package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;

import java.io.IOException;

/**
 * VESSEL: `ClientMessage` (event code 33), which the vendored server had no
 * implementation of.
 *
 * <p>It is here for exactly one purpose and that purpose is worth stating.
 * Vessel's taskbar could focus a guest window and could not close one, and the
 * session rail's only other control ends the <em>whole session</em> — so the
 * answer to "this program has hung" was "stop everything you are running".
 * `WM_DELETE_WINDOW`, delivered as a `ClientMessage` on `WM_PROTOCOLS`, is the
 * one polite way to ask a window to go away, and it is what every window manager
 * on X11 sends when you press the close button.
 *
 * <p>It matters that it is polite. A program that has unsaved work gets to put
 * up its "save changes?" dialog, because on the Windows side this arrives as
 * `WM_CLOSE` — the message `winex11.drv` maps it to. Killing the client would
 * skip that, which is why {@link com.winlator.xserver.Window} offers this first
 * and the caller decides whether to escalate.
 *
 * <p>The wire format is fixed at 32 bytes for every event, so the five data
 * words are written whether or not they are used. Only the 32-bit form is
 * implemented ({@code format = 32}); the 8- and 16-bit forms exist in the
 * protocol and nothing in this project sends one.
 */
public class ClientMessage extends Event {
    private final Window window;
    private final int type;
    private final int[] data;

    /**
     * @param window the destination window, which is also the `window` field
     * @param type   the message type atom — `WM_PROTOCOLS` for a close request
     * @param data   up to five 32-bit words; shorter arrays are zero-padded
     */
    public ClientMessage(Window window, int type, int... data) {
        super(33);
        this.window = window;
        this.type = type;
        this.data = data;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            // The `detail` byte carries the format for this event, not a detail:
            // 32 says the five data words are 32 bits each.
            outputStream.writeByte(code);
            outputStream.writeByte((byte)32);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(window.id);
            outputStream.writeInt(type);
            for (int i = 0; i < 5; i++) outputStream.writeInt(i < data.length ? data[i] : 0);
        }
    }
}
