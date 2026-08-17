package com.winlator.xserver.requests;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Atom;
import com.winlator.xserver.SelectionManager;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadAtom;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.events.SelectionNotify;
import com.winlator.xserver.events.SelectionRequest;

import java.io.IOException;

public abstract class SelectionRequests {
    public static void setSelectionOwner(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int atom = inputStream.readInt();
        int timestamp = inputStream.readInt();

        // VESSEL: window id 0 is `None`, and it is how a client gives a selection
        // up. Upstream looked it up unconditionally and threw BadWindow, so
        // `winex11.drv` emptying the Windows clipboard — which calls
        // XSetSelectionOwner(CLIPBOARD, None) — produced a protocol error rather
        // than a release, and this server went on believing a dead window owned
        // the clipboard.
        Window owner = null;
        if (windowId != 0) {
            owner = client.xServer.windowManager.getWindow(windowId);
            if (owner == null) throw new BadWindow(windowId);
        }
        if (!Atom.isValid(atom)) throw new BadAtom(atom);

        client.xServer.selectionManager.setSelection(atom, owner, client, timestamp);
        // VESSEL: and tell the clipboard shim, which is the only thing that wants
        // to know. A guest claiming CLIPBOARD is the signal to go and fetch what
        // it copied; a guest releasing it is the signal to offer Android's
        // clipboard again. See ClipboardSelection.onSelectionClaimed.
        client.xServer.clipboard.onSelectionClaimed(atom, owner, client);
    }

    public static void getSelectionOwner(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int atom = inputStream.readInt();
        if (!Atom.isValid(atom)) throw new BadAtom(atom);
        Window owner = client.xServer.selectionManager.getSelection(atom).owner;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(owner != null ? owner.id : 0);
            outputStream.writePad(20);
        }
    }

    /**
     * VESSEL: `ConvertSelection`, core request 24, which this tree never
     * implemented — the opcode was not even declared, so it fell through
     * `XClientRequestHandler`'s default and took the connection down.
     *
     * <p>This is the request that makes a selection readable, and it is the whole
     * reason clipboard did not work: `SET_SELECTION_OWNER` and
     * `GET_SELECTION_OWNER` were both here, so Wine could claim `CLIPBOARD` on
     * every copy and nothing could ever ask what was in it.
     *
     * <p>The server's job here is only routing; it never carries the data.
     *
     * <ul>
     *   <li><b>No owner</b> — answer the requestor itself with a
     *       {@link SelectionNotify} whose property is `None`. That is the
     *       protocol's "nobody has this", and it must be sent rather than
     *       dropped: a requestor with no reply waits, and a paste that hangs is
     *       worse than a paste that does nothing.
     *   <li><b>The owner is Vessel's clipboard shim</b> — the server answers on
     *       Android's behalf. See {@link com.winlator.xserver.ClipboardSelection}.
     *   <li><b>Any other owner</b> — forward a {@link SelectionRequest} to it and
     *       stop. The owner writes the property and sends the notify; nothing
     *       here waits for either, and the requestor's timeout is its own
     *       business. That asynchrony is not a shortcut, it is required: the
     *       owner may be the very client whose request thread is running this.
     * </ul>
     *
     * <p>A `property` of `None` means the requestor does not care where the value
     * lands, and the convention every toolkit follows is to use the target atom as
     * the property name. Substituted here rather than refused, because refusing it
     * would be refusing a legal request.
     */
    public static void convertSelection(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int requestorId = inputStream.readInt();
        int selection = inputStream.readInt();
        int target = inputStream.readInt();
        int property = inputStream.readInt();
        int timestamp = inputStream.readInt();

        Window requestor = client.xServer.windowManager.getWindow(requestorId);
        if (requestor == null) throw new BadWindow(requestorId);
        if (!Atom.isValid(selection)) throw new BadAtom(selection);
        if (!Atom.isValid(target)) throw new BadAtom(target);
        // `None` is legal for the property alone. Anything else has to be a real
        // atom, because the owner is about to be asked to write to it.
        if (property != 0 && !Atom.isValid(property)) throw new BadAtom(property);
        if (property == 0) property = target;

        SelectionManager.Selection owned = client.xServer.selectionManager.getSelection(selection);
        Window owner = owned.owner;

        if (owner == null) {
            client.sendEvent(new SelectionNotify(timestamp, requestor, selection, target, 0));
            return;
        }

        if (client.xServer.clipboard.isServerWindow(owner)) {
            client.xServer.clipboard.answer(requestor, selection, target, property, timestamp);
            return;
        }

        XClient ownerClient = owned.getClient();
        // An owner with no client and that is not the shim's window cannot
        // happen today; refusing is still the only honest answer if it ever does,
        // and it is a reply rather than a silence.
        if (ownerClient == null) {
            client.sendEvent(new SelectionNotify(timestamp, requestor, selection, target, 0));
            return;
        }

        ownerClient.sendEvent(new SelectionRequest(timestamp, owner, requestor, selection, target, property));
    }
}
