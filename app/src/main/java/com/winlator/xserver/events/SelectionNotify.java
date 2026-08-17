package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;

import java.io.IOException;

/**
 * VESSEL: `SelectionNotify` (event code 31), which the vendored server had no
 * implementation of.
 *
 * <p>The answer to a {@link SelectionRequest}, and also the answer this server
 * sends by itself when a `CONVERT_SELECTION` cannot be satisfied at all — no
 * owner, or an owner that is this server's own clipboard shim. The data is never
 * in the event: it is in {@code property} on {@code requestor}, which the
 * requestor reads with `GetProperty`. That indirection is how X11 moves a
 * clipboard, and it is why `CHANGE_PROPERTY` and `GET_PROPERTY` already existing
 * meant only the handshake was missing.
 *
 * <p>{@code property} is `None` (0) to mean <em>refused</em>. A requestor must
 * treat that as "the owner cannot give me this target", not as an error, which is
 * what makes offering a short target list honest rather than fragile.
 *
 * <p>Delivered to the requestor's client unconditionally, for the same reason
 * {@link SelectionRequest} is: nothing selects for it.
 */
public class SelectionNotify extends Event {
    private final int timestamp;
    private final Window requestor;
    private final int selection;
    private final int target;
    private final int property;

    public SelectionNotify(int timestamp, Window requestor, int selection, int target, int property) {
        super(31);
        this.timestamp = timestamp;
        this.requestor = requestor;
        this.selection = selection;
        this.target = target;
        this.property = property;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(timestamp);
            outputStream.writeInt(requestor.id);
            outputStream.writeInt(selection);
            outputStream.writeInt(target);
            outputStream.writeInt(property);
            outputStream.writePad(8);
        }
    }
}
