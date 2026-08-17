package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;

import java.io.IOException;

/**
 * VESSEL: `SelectionRequest` (event code 30), which the vendored server had no
 * implementation of.
 *
 * <p>This is the event that asks a selection owner to hand its data over, and
 * without it a selection in this server was write-only: `SET_SELECTION_OWNER`
 * recorded an owner, `GET_SELECTION_OWNER` reported it, and nothing could ever
 * ask what the owner was holding. That is precisely why clipboard did not work —
 * Wine claimed `CLIPBOARD` on every copy and no question ever reached it.
 *
 * <p>The owner answers by writing the value into {@code property} on
 * {@code requestor} with `ChangeProperty`, then sending a
 * {@link SelectionNotify} back to the requestor. If it cannot supply
 * {@code target} it sends the notify with the property set to `None`. Refusing by
 * silence is allowed by the protocol and is the reason requestors time out; both
 * halves of that are the owner's business, not this server's.
 *
 * <p>Delivered to the owner's client unconditionally rather than through an event
 * mask. A `SelectionRequest` is not selected for — owning a selection is the
 * subscription — which is the same reasoning {@link ClientMessage} carries.
 */
public class SelectionRequest extends Event {
    private final int timestamp;
    private final Window owner;
    private final Window requestor;
    private final int selection;
    private final int target;
    private final int property;

    public SelectionRequest(int timestamp, Window owner, Window requestor, int selection, int target, int property) {
        super(30);
        this.timestamp = timestamp;
        this.owner = owner;
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
            outputStream.writeInt(owner.id);
            outputStream.writeInt(requestor.id);
            outputStream.writeInt(selection);
            outputStream.writeInt(target);
            outputStream.writeInt(property);
            outputStream.writePad(4);
        }
    }
}
