package com.winlator.xserver;

import android.util.Log;

import com.winlator.xserver.events.SelectionNotify;
import com.winlator.xserver.events.SelectionRequest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * VESSEL: clipboard, both ways, as far as the X11 side of it goes.
 *
 * <p>This class is the server's own participant in the selection protocol. It
 * plays both roles, which is the whole shape of the problem:
 *
 * <ul>
 *   <li><b>Owner</b>, so a guest program pasting reaches Android's clipboard. It
 *       claims `CLIPBOARD` and `PRIMARY` for {@link #serverWindow} whenever
 *       Android says its clipboard changed, and answers the resulting
 *       `ConvertSelection` out of {@link Bridge#getText()}.
 *   <li><b>Requestor</b>, so a copy inside the guest reaches Android. When a
 *       client claims `CLIPBOARD`, this sends that client a
 *       {@link SelectionRequest} naming {@link #serverWindow} as the requestor,
 *       waits for the owner's `ChangeProperty` and `SelectionNotify` to arrive,
 *       and pushes the text out through {@link Bridge#setText}.
 * </ul>
 *
 * <h2>Text only, and that is a decision rather than an unfinished edge</h2>
 *
 * The advertised target list is `TARGETS`, `UTF8_STRING`, `STRING`, `TEXT` and
 * nothing else. Images and arbitrary formats are refused with a
 * {@link SelectionNotify} whose property is `None`, which is the protocol's
 * "cannot supply that", not an error. Bitmaps would mean carrying pixels between
 * a `ClipData` and an X property with a format negotiation on each side, and the
 * paste that people actually want on a phone is a string — a licence key, a path,
 * a command. `TIMESTAMP`, `MULTIPLE` and `INCR` are refused too, deliberately:
 * this server has no honest selection timestamp (everything here uses
 * `CurrentTime`), nothing needs `MULTIPLE` once the list is this short, and `INCR`
 * is a whole second state machine. None of the four is advertised, because
 * advertising a target and then refusing it at the first call is the failure shape
 * `README.md` item 20 exists to remember.
 *
 * <h2>Nothing here reads Android's clipboard eagerly</h2>
 *
 * {@link Bridge#getText()} is called at <em>conversion</em> time and nowhere else.
 * That is not a performance choice. Android logs an access notification every time
 * an app reads the clipboard, and on recent releases shows the user a toast
 * saying so — so a poll, or a read on every change notification, would produce a
 * stream of them while the user did nothing. Ownership is claimed without looking
 * at the content; the content is fetched only when a guest program actually
 * pastes.
 *
 * <h2>The echo, and why breaking it is not optional</h2>
 *
 * Pushing text to Android makes Android's clipboard change, which fires the
 * listener, which would claim the X selection back, which sends the guest a
 * `SelectionClear` for the copy it just made. Two guards, both needed because they
 * catch different halves:
 *
 * <ul>
 *   <li>{@link #textForGuest} — the last string handed <em>to</em> the guest. If a
 *       guest claim produces that same string back, it is Android's own text
 *       coming home and it is not written back. Costs nothing and needs no
 *       clipboard read, because it is a string this class already had.
 *   <li>The Android side suppresses the callback its own write causes; see
 *       `app.vessel.display.AndroidClipboard`. It has to live there rather than
 *       here, because only that side knows a write happened at all — this class
 *       hands the text over and returns.
 * </ul>
 *
 * Without them: guest copies, we push to Android, Android notifies, we claim the
 * selection back and clear the guest's ownership, and every subsequent paste in
 * the guest is served by us out of a stale Android clip. It does not spin forever
 * on its own — the cycle needs the guest to claim again, and only a user copying
 * does that — but the selection flaps on every copy and the guest is left unable
 * to hold its own clipboard. With a Wine that re-asserted ownership on
 * `SelectionClear` it would spin, and that is not a property of this code to rely
 * on.
 *
 * <h2>Nothing in here has been run</h2>
 *
 * There is no way to exercise a clipboard round trip off the device: it needs a
 * live Wine, a real `ClipboardManager` and a user copying something.
 * `SelectionProtocolTest` covers the wire encodings and the atom set, which is the
 * part a JVM can see. Everything about whether Wine is satisfied by these answers
 * is <b>unverified</b>.
 */
public class ClipboardSelection {

    /**
     * The Android half, kept behind an interface so `com.winlator` names no
     * Android clipboard type and `app.vessel` keeps its single import of this
     * package.
     *
     * <p>Both methods are called from an X client's request thread with the
     * `WINDOW_MANAGER` lock held, so <b>neither may block</b>. The implementation
     * is expected to hop to its own thread for anything that could.
     */
    public interface Bridge {
        /**
         * Android's clipboard text, right now, or null when there is none.
         *
         * <p>Called only while answering a conversion — see the class comment on
         * why this must not be turned into a poll or a cache refresh.
         */
        String getText();

        /** Put text on Android's clipboard. Must return promptly. */
        void setText(String text);
    }

    private static final String TAG = "VesselClipboard";

    /** Where a fetch from the guest lands on {@link #serverWindow}. */
    private static final String TRANSFER_PROPERTY = "_VESSEL_CLIPBOARD_IN";

    /**
     * Latin-1, which is what the `STRING` atom means in X11. Named from
     * {@link StandardCharsets} rather than through {@link XServer#LATIN1_CHARSET}
     * — the same charset either way — so that the static half of this class can be
     * exercised in a JVM test without loading a server that needs `libwinlator`.
     */
    private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;

    /**
     * The conventional selection and target atoms, interned once at class load.
     *
     * <p>`static` because {@link Atom}'s table is itself static and process-wide,
     * so an id is stable for the life of the process however many servers are
     * built; and because it lets {@link #offeredTargets()} and
     * {@link #decodeText(int, byte[])} be pure functions a test can reach.
     *
     * <p>`CLIPBOARD`, `TARGETS`, `UTF8_STRING` and `TEXT` are <em>not</em>
     * predefined atoms — the protocol's fixed table stops at 68 and none of them is
     * in it — so they are interned by name, which is exactly what every client does
     * too. `PRIMARY` and `STRING` are predefined, and are taken from the constants
     * so that a shift in that table cannot silently rename them.
     */
    public static final int ATOM_CLIPBOARD = Atom.internAtom("CLIPBOARD");
    public static final int ATOM_PRIMARY = Atom.PRIMARY;
    public static final int ATOM_TARGETS = Atom.internAtom("TARGETS");
    public static final int ATOM_UTF8_STRING = Atom.internAtom("UTF8_STRING");
    public static final int ATOM_STRING = Atom.STRING;
    public static final int ATOM_TEXT = Atom.internAtom("TEXT");
    public static final int ATOM_ATOM = Atom.ATOM;

    /**
     * Interned so the check against it is well defined even if no client ever
     * names it. A property arriving with this type is a chunked transfer this class
     * does not implement, and it has to be recognised to be refused intelligibly
     * rather than decoded as if it were the text.
     */
    public static final int ATOM_INCR = Atom.internAtom("INCR");

    public static final int ATOM_TRANSFER_PROPERTY = Atom.internAtom(TRANSFER_PROPERTY);

    private final XServer xServer;

    /**
     * Volatile because it is written from Android's main thread — a session
     * starting or stopping — and read from every X client's request thread. The
     * rest of this class's mutable state is guarded by `WINDOW_MANAGER`, which
     * every entry point either holds or takes; this one field is not, because
     * installing the bridge happens before any lock exists to take.
     */
    private volatile Bridge bridge;

    /** Created on first use — see {@link WindowManager#createServerWindow()}. */
    private Window serverWindow;

    /**
     * The last text served out of Android to a guest program, or null.
     *
     * <p>Echo guard: text that comes back from a guest claim unchanged is
     * Android's own and is not written back. Cleared by {@link #announce()},
     * because a fresh Android clip has served nothing yet.
     */
    private String textForGuest;

    /** The last text pushed to Android, for the log line and for symmetry. */
    private String textFromGuest;

    /** Which selection a fetch is outstanding on, or 0. */
    private int fetching;

    public ClipboardSelection(XServer xServer) {
        this.xServer = xServer;
    }

    /**
     * Install the Android half. Null uninstalls it, which leaves every path here
     * inert rather than half-working — an unset bridge means the shim never claims
     * a selection, so the guest's own clipboard behaves exactly as it did before
     * any of this existed.
     */
    public void setBridge(Bridge bridge) {
        this.bridge = bridge;
    }

    /** Whether {@code window} is the shim's own, i.e. whether the server owns it. */
    public boolean isServerWindow(Window window) {
        return window != null && window == serverWindow;
    }

    /**
     * Android's clipboard changed: take `CLIPBOARD` and `PRIMARY` over.
     *
     * <p>Called from Android's main thread, so it takes the lock the request
     * threads take. Nothing is read from the clipboard here.
     *
     * <p>Both selections rather than only `CLIPBOARD`, because a guest paste can
     * come from either and the two mean the same thing on a device with one
     * clipboard: `CLIPBOARD` is Ctrl+V, `PRIMARY` is the X11 select-and-middle-
     * click buffer that `winex11.drv` also reads.
     */
    public void announce() {
        if (bridge == null) return;
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            Window window = serverWindow();
            // A new clip has served nothing, so nothing coming back from the guest
            // can be an echo of it yet.
            textForGuest = null;
            int timestamp = timestamp();
            xServer.selectionManager.setSelection(ATOM_CLIPBOARD, window, null, timestamp);
            xServer.selectionManager.setSelection(ATOM_PRIMARY, window, null, timestamp);
        }
    }

    /**
     * Answer a `ConvertSelection` aimed at a selection this shim owns.
     *
     * <p>The value goes into {@code property} on {@code requestor} and the
     * requestor is told with a {@link SelectionNotify}; it then reads the property
     * with `GetProperty`. Writing the property and <em>not</em> sending the notify
     * would hang the paste, and sending the notify without the property would make
     * the requestor read a property that is not there — so both happen here or
     * neither does.
     */
    public void answer(Window requestor, int selection, int target, int property, int timestamp) {
        if (requestor == null) return;

        if (target == ATOM_TARGETS) {
            requestor.modifyProperty(property, ATOM_ATOM, Property.Format.INT_ARRAY,
                    Property.Mode.REPLACE, encodeTargets());
            notifyRequestor(requestor, selection, target, property, timestamp);
            return;
        }

        if (!isTextTarget(target)) {
            // Not advertised, so nobody should be asking; refused with a reply
            // rather than a silence, because a requestor with no answer waits.
            Log.d(TAG, "refusing target " + named(target) + " — text only");
            refuse(requestor, selection, target, timestamp);
            return;
        }

        Bridge current = bridge;
        // **The one read of Android's clipboard, and it happens here.** See the
        // class comment: reading logs an access notification and can show the user
        // a toast, so it is done when a program actually pastes and at no other
        // time.
        String text = current != null ? current.getText() : null;
        if (text == null || text.isEmpty()) {
            refuse(requestor, selection, target, timestamp);
            return;
        }

        requestor.modifyProperty(property, replyType(target), Property.Format.BYTE_ARRAY,
                Property.Mode.REPLACE, encodeText(target, text));
        textForGuest = text;
        notifyRequestor(requestor, selection, target, property, timestamp);
    }

    /** The targets this shim will answer, in the order it advertises them. */
    public static int[] offeredTargets() {
        return new int[]{ATOM_TARGETS, ATOM_UTF8_STRING, ATOM_STRING, ATOM_TEXT};
    }

    /** Whether {@code target} is one of the three text targets. */
    public static boolean isTextTarget(int target) {
        return target == ATOM_UTF8_STRING || target == ATOM_STRING || target == ATOM_TEXT;
    }

    /**
     * The property type a text target is answered with.
     *
     * <p>`TEXT` means "whatever encoding you like, and name it in the reply", so it
     * is answered as `UTF8_STRING` — the only choice that cannot lose a character
     * the user copied. `STRING` is Latin-1 by definition, and a requestor that
     * asked for it gets it.
     */
    public static int replyType(int target) {
        return target == ATOM_STRING ? ATOM_STRING : ATOM_UTF8_STRING;
    }

    /**
     * Text as the bytes of the property that answers {@code target}.
     *
     * <p>No terminating NUL. X properties carry a length, so a NUL is not a
     * terminator here but a character — and a requestor that pastes it puts a box
     * in somebody's document.
     */
    public static byte[] encodeText(int target, String text) {
        return replyType(target) == ATOM_STRING
                ? text.getBytes(LATIN1)
                : text.getBytes(StandardCharsets.UTF_8);
    }

    /** The offered target list as the body of an `ATOM` property. */
    public static byte[] encodeTargets() {
        int[] targets = offeredTargets();
        ByteBuffer buffer = ByteBuffer.allocate(targets.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int target : targets) buffer.putInt(target);
        return buffer.array();
    }

    /**
     * A property's bytes as text, or null when the type is not one this handles.
     *
     * <p>Null for `INCR`, for `ATOM`, for an image — anything that is not
     * `UTF8_STRING` or `STRING`. The caller distinguishes those cases for the log;
     * this function's only job is to refuse to guess.
     *
     * <p>Truncated at the first NUL. Properties are not NUL-terminated, but Wine's
     * exporters have been seen to pad, and a trailing NUL pasted into an Android
     * text field is a visible box rather than nothing.
     */
    public static String decodeText(int type, byte[] bytes) {
        if (bytes == null) return null;
        String text;
        if (type == ATOM_STRING) {
            text = new String(bytes, LATIN1);
        }
        else if (type == ATOM_UTF8_STRING) {
            text = new String(bytes, StandardCharsets.UTF_8);
        }
        else return null;

        int nul = text.indexOf('\0');
        return nul >= 0 ? text.substring(0, nul) : text;
    }

    /**
     * A client claimed or released a selection.
     *
     * <p>Claimed by somebody other than the shim, and it is `CLIPBOARD` or
     * `PRIMARY`: go and get what was copied. That is the only trigger — there is
     * no polling of the guest's clipboard and there could not be, since X11 has no
     * "what is in the selection" question that does not go through an owner.
     *
     * <p>Released (owner `None`) while a bridge is installed: offer Android's
     * clipboard again, so a guest that emptied its own clipboard leaves paste
     * working rather than leaving the selection unowned.
     */
    public void onSelectionClaimed(int atom, Window owner, XClient client) {
        if (bridge == null) return;
        if (atom != ATOM_CLIPBOARD && atom != ATOM_PRIMARY) return;

        if (owner == null) {
            // Not from inside this call chain's lock nesting — `announce` takes
            // the same reentrant lock, which is already held here.
            announce();
            return;
        }

        if (isServerWindow(owner) || client == null) return;
        fetch(atom, owner, client);
    }

    /**
     * A 32-byte event a client sent to {@link #serverWindow}, delivered here
     * instead of to a socket.
     *
     * <p>The shim has no connection, so `SendEvent` cannot reach it the ordinary
     * way; {@link com.winlator.xserver.requests.WindowRequests#sendEvent} routes
     * events for this window here. Only `SelectionNotify` (31) is of interest.
     * `PropertyNotify` (28) would be the `INCR` path and is ignored, which is the
     * same refusal the class comment records.
     */
    public void onServerWindowEvent(byte[] event) {
        if (event == null || event.length < 32) return;
        // The high bit of the code says the event was synthetic, which every
        // SelectionNotify from an owner is — it is sent with SendEvent.
        int code = event[0] & 0x7f;
        if (code != 31) return;

        ByteBuffer buffer = ByteBuffer.wrap(event).order(ByteOrder.LITTLE_ENDIAN);
        int selection = buffer.getInt(12);
        int property = buffer.getInt(20);
        int wanted = fetching;
        fetching = 0;

        if (property == 0) {
            // The owner refused. Ordinary: a program can hold the selection and
            // have nothing this shim asked for.
            Log.d(TAG, "guest refused to convert selection " + named(selection));
            return;
        }
        if (wanted != 0 && selection != wanted) {
            Log.d(TAG, "ignoring a SelectionNotify for " + named(selection) +
                    " while fetching " + named(wanted));
            return;
        }

        Window window = serverWindow;
        if (window == null) return;
        Property value = window.getProperty(property);
        // Read once and taken away: leaving it would make the next transfer's
        // failure look like a success, since a refused conversion writes nothing
        // and the stale value would still be sitting there.
        window.removeProperty(property);
        if (value == null) return;

        if (value.type == ATOM_INCR) {
            // Wine switches to INCR above its own selection-size limit, so this is
            // what a very large copy looks like. Named rather than decoded, because
            // the property holds a byte count in this case and pasting "1048576"
            // would be worse than pasting nothing.
            Log.w(TAG, "the guest offered the clipboard as INCR; chunked transfers are " +
                    "not implemented, so this copy is dropped");
            return;
        }

        String text = decodeText(value.type, value.data.array());
        if (text == null) {
            Log.d(TAG, "ignoring clipboard of type " + named(value.type) + " — text only");
            return;
        }
        if (text.isEmpty()) return;

        // Echo guard. This is Android's own text finding its way home — the user
        // copied inside the guest something that came from Android — and writing it
        // back would fire Android's change listener for no change at all.
        if (text.equals(textForGuest)) return;

        textFromGuest = text;
        Bridge current = bridge;
        if (current != null) current.setText(text);
    }

    /** The text last pushed to Android, for tests and for logging. */
    public String lastTextFromGuest() {
        return textFromGuest;
    }

    /** The text last served to the guest, for tests and for logging. */
    public String lastTextForGuest() {
        return textForGuest;
    }

    /**
     * Ask an owner for its text.
     *
     * <p>`UTF8_STRING` and not `TARGETS` first. Asking what is on offer would cost
     * a second round trip to learn something the answer already tells us: an owner
     * that cannot supply UTF-8 replies with `None`, which is exactly what a
     * `TARGETS` list lacking it would have said. Every Windows program's clipboard
     * text is UTF-16 in `CF_UNICODETEXT` and `winex11.drv` exports it as
     * `UTF8_STRING`, so the one-shot ask is the common case and not a gamble.
     *
     * <p>Fire and forget. The reply arrives later as a `ChangeProperty` and a
     * `SendEvent` on some other request, and it has to: this runs on the owner's
     * own request thread, inside its `SetSelectionOwner`, so waiting for it here
     * would be waiting for a client that is waiting for us.
     */
    private void fetch(int selection, Window owner, XClient client) {
        Window window = serverWindow();
        // Any leftover from a transfer that never completed. Otherwise a refused
        // conversion would be read as the previous copy.
        window.removeProperty(ATOM_TRANSFER_PROPERTY);
        fetching = selection;
        client.sendEvent(new SelectionRequest(timestamp(), owner, window,
                selection, ATOM_UTF8_STRING, ATOM_TRANSFER_PROPERTY));
    }

    /**
     * An atom's name, for a log line, without the chance of throwing.
     *
     * <p>{@link Atom#getName} indexes a list, and every atom named in the three
     * log lines above arrives from a client — a `SelectionNotify` this server did
     * not construct, or a property type from a `ChangeProperty` that never
     * validated it. An `IndexOutOfBoundsException` raised while formatting a debug
     * message would unwind the request thread and drop the connection, which is a
     * spectacular way for a log statement to fail.
     */
    private static String named(int atom) {
        return Atom.isValid(atom) ? Atom.getName(atom) : "atom " + atom;
    }

    private void refuse(Window requestor, int selection, int target, int timestamp) {
        notifyRequestor(requestor, selection, target, 0, timestamp);
    }

    private void notifyRequestor(Window requestor, int selection, int target, int property, int timestamp) {
        XClient client = requestor.originClient;
        // No client means the requestor is the shim's own window or the root, and
        // neither ever asks itself a question. Dropping is right; there is nowhere
        // to send it.
        if (client == null) return;
        client.sendEvent(new SelectionNotify(timestamp, requestor, selection, target, property));
    }

    /**
     * Created on first use rather than in the constructor, so a session that never
     * touches the clipboard carries no extra window in the server at all.
     *
     * <p>Callers hold `WINDOW_MANAGER`; the lock is reentrant and
     * {@link #announce()} takes it explicitly for the Android-thread case.
     */
    private Window serverWindow() {
        if (serverWindow == null) {
            serverWindow = xServer.windowManager.createServerWindow();
            Log.d(TAG, "clipboard requestor window is " + serverWindow.id);
        }
        return serverWindow;
    }

    /**
     * A server timestamp, in the same units X uses.
     *
     * <p>`CurrentTime` (0) would be simpler and is what the rest of this tree
     * sends, but a selection timestamp is one of the few that a client compares:
     * `SetSelectionOwner` is defined to be refused if the time is earlier than the
     * current owner's. Truncating `currentTimeMillis` is what
     * {@link com.winlator.xserver.events.PropertyNotify} already does, so both
     * halves of a comparison come from the same clock.
     */
    private int timestamp() {
        return (int) System.currentTimeMillis();
    }
}
