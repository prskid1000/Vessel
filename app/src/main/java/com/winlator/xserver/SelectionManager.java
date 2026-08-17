package com.winlator.xserver;

import android.util.SparseArray;

import com.winlator.xserver.events.SelectionClear;

public class SelectionManager implements XResourceManager.OnResourceLifecycleListener {
    private final SparseArray<Selection> selections = new SparseArray<>();

    public SelectionManager(WindowManager windowManager) {
        windowManager.addOnResourceLifecycleListener(this);
    }

    public static class Selection {
        public Window owner;
        private XClient client;

        /**
         * VESSEL: the connection that owns this selection, or null when the
         * server itself does.
         *
         * <p>`ConvertSelection` has to send a `SelectionRequest` to whoever holds
         * the selection, and this field was package-private with no reader — the
         * only code that touched it was {@link SelectionManager#setSelection},
         * because nothing in this tree could ask an owner for anything. Null is a
         * real answer and not "unset": Vessel's clipboard shim owns `CLIPBOARD`
         * on behalf of Android's `ClipboardManager` and has no socket.
         */
        public XClient getClient() {
            return client;
        }
    }

    /**
     * VESSEL: two corrections, both of which this tree could not previously
     * reach because nothing ever converted a selection.
     *
     * <p>**A null owner no longer throws.** `SetSelectionOwner` with owner `None`
     * is how a client <em>releases</em> a selection, and `winex11.drv` does
     * exactly that when the Windows clipboard is emptied. The event constructed
     * here used to be handed the incoming {@code owner}, so releasing produced a
     * `SelectionClear` carrying null and `SelectionClear.send` dereferenced it —
     * an NPE out of the request thread, which is not an X error and so drops the
     * connection instead of replying.
     *
     * <p>**The event now names the window that is losing the selection**, which
     * is what the protocol says the `owner` field is. Passing the new owner was
     * wrong even when it was non-null; a client uses that field to decide whether
     * the clear is about a window it still cares about.
     *
     * <p>**A null previous client is skipped.** The server can be the owner now,
     * and it has no output stream to send a clear to. Losing it is not silent
     * either way — Vessel's shim is told through
     * {@link ClipboardSelection#onSelectionClaimed}.
     */
    public void setSelection(int atom, Window owner, XClient client, int timestamp) {
        Selection selection = getSelection(atom);
        if (selection.owner != null && (owner == null || selection.client != client)) {
            if (selection.client != null) {
                selection.client.sendEvent(new SelectionClear(timestamp, selection.owner, atom));
            }
        }
        selection.owner = owner;
        selection.client = client;
    }

    public Selection getSelection(int atom) {
        Selection selection = selections.get(atom);
        if (selection != null) return selection;
        selection = new Selection();
        selections.put(atom, selection);
        return selection;
    }

    @Override
    public void onFreeResource(XResource resource) {
        for (int i = 0; i < selections.size(); i++) {
            Selection selection = selections.valueAt(i);
            if (selection.owner == resource) {
                selection.owner = null;
                // VESSEL: and the client with it. A departed owner whose client
                // pointer survived would be handed the next ConvertSelection —
                // written to a closed stream at best, and to a recycled XClient
                // at worst, since ResourceIDs reissues a departing client's base.
                selection.client = null;
            }
        }
    }
}
