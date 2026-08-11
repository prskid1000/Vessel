package app.vessel.display

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The server half of the guest's gamepad bus.
 *
 * **The app is the only thing on this device that can see a controller, so the
 * app has to be the bus.** `/dev/input` is `root:input` 0660 and an untrusted
 * app is in neither group — measured, not assumed — which is why Wine is built
 * `--without-sdl --without-udev --without-usb` and why `XInputGetState` inside
 * the container has always returned `ERROR_DEVICE_NOT_CONNECTED`. Android's
 * `InputDevice` API sees the pad; nothing in Wine can. This carries the gap.
 *
 * The client half is `patches/wine/0016`, a fourth `winebus` backend. It
 * connects, and what arrives here becomes a real HID gamepad inside the guest —
 * which is the whole reason for doing it this way rather than shipping our own
 * `xinput1_3.dll`. One HID device reaches `hidclass` and `winexinput`, so
 * XInput, DirectInput, winmm and raw HID all light up together, and rumble has
 * somewhere to come back to.
 *
 * **Frames are a fixed twenty bytes, native byte order, in both directions.**
 * Fixed rather than length-prefixed because there is one producer, one consumer
 * and four message shapes; a short read is then a torn frame the reader keeps
 * accumulating rather than a stream that has lost its place. The layout is
 * spelled out in [frame].
 *
 * One client at a time, and the newest wins. A container has one Wine in it;
 * a second connection means the first session's `winebus` has not noticed it is
 * dead yet, and serving the stale one would send a live pad to a dead guest.
 */
internal class PadBridge(private val socketName: String) {

    /** What one pad is, as the wire carries it. */
    data class State(
        /** Bit *n* is HID button usage *n + 1*: A, B, X, Y, L1, R1, Select, Start, L3, R3. */
        val buttons: Int = 0,
        /** 1 up, 2 down, 4 left, 8 right — a mask, because two can be held at once. */
        val hat: Int = 0,
        val lx: Int = 0,
        val ly: Int = 0,
        val rx: Int = 0,
        val ry: Int = 0,
        val lt: Int = 0,
        val rt: Int = 0,
    )

    /**
     * Rumble, as the guest asked for it. Intensities are 0..65535 and the
     * duration is milliseconds; zero everywhere means stop.
     */
    var onRumble: (index: Int, low: Int, high: Int, durationMs: Int) -> Unit = { _, _, _, _ -> }

    /**
     * Whether a guest is listening.
     *
     * **This is what decides whether a pad also gets translated into keystrokes.**
     * Until now the only way a pad reached a Windows program was `GamepadTranslator`
     * turning it into `W`, `A`, `S`, `D` and mouse buttons, and that has to keep
     * working — a container running a Wine without this patch, or one started
     * before the guest connects, has nothing else. But a game reading both would
     * walk twice as far per stick, so the moment a real HID pad exists in the
     * guest the keyboard impersonation stops. Nothing to configure, and the old
     * behaviour is exactly what happens when the new path is absent.
     */
    val attached: Boolean get() = client != null

    /**
     * Whether the socket is bound and the guest could dial it.
     *
     * Separate from [attached] because the environment variable has to be
     * exported before the guest starts, and at that moment nothing has connected
     * yet. Exporting a path that was never bound would make every session pay a
     * failed `connect(2)` in `winebus` for nothing.
     */
    val listening: Boolean get() = server != null

    private val running = AtomicBoolean(false)
    private var server: LocalServerSocket? = null
    private var accepting: Thread? = null

    @Volatile private var client: LocalSocket? = null
    @Volatile private var out: OutputStream? = null

    /** The last state written per slot, so an unchanged pad costs no syscall. */
    private val sent = arrayOfNulls<State>(SLOTS)
    private val present = BooleanArray(SLOTS)

    private val lock = Any()

    /**
     * Bind, listen, and start accepting.
     *
     * Returns false rather than throwing: a session with no pad socket is a
     * session that behaves exactly as it did before this existed, and that is a
     * better outcome than refusing to start a game.
     */
    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        try {
            // **The abstract namespace, like the X socket beside it.** The
            // filesystem one would have been the closer match to the SysV-SHM
            // socket, but `android.system.UnixSocketAddress` is not public API
            // and `LocalServerSocket` binds nothing else. Abstract is better
            // here anyway: the name never touches the filesystem, so there is no
            // stale node to delete after a kill, no directory to create, and no
            // 108-byte path to fit a sandbox path into. `winebus` dials it the
            // same way libxcb already dials the X server on this device.
            server = LocalServerSocket(socketName)
        } catch (e: Throwable) {
            Log.w(TAG, "could not open the pad socket @$socketName", e)
            running.set(false)
            server = null
            return false
        }
        accepting = Thread({ acceptLoop() }, "vessel-pad").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { server?.close() }
        runCatching { client?.close() }
        server = null
        client = null
        out = null
        accepting?.interrupt()
        accepting = null
    }

    /** A pad appeared in, or vanished from, [index]. */
    fun setPresent(index: Int, value: Boolean) {
        if (index !in 0 until SLOTS) return
        synchronized(lock) {
            if (present[index] == value) return
            present[index] = value
            sent[index] = null
            write(frame(if (value) MSG_ADDED else MSG_REMOVED, index, State()))
        }
    }

    /** The pad in [index] is now in [state]. Silent if it already was. */
    fun submit(index: Int, state: State) {
        if (index !in 0 until SLOTS) return
        synchronized(lock) {
            if (!present[index]) return
            if (sent[index] == state) return
            sent[index] = state
            write(frame(MSG_STATE, index, state))
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                server?.accept() ?: return
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "the pad socket stopped accepting", e)
                return
            }
            Log.i(TAG, "a guest attached to the pad socket")
            synchronized(lock) {
                // The newest wins; see the class comment.
                runCatching { client?.close() }
                client = socket
                out = runCatching { socket.outputStream }.getOrNull()
                // Everything the guest missed by not being here yet. Its bus has
                // just started and knows about nothing at all.
                for (i in 0 until SLOTS) {
                    if (!present[i]) continue
                    sent[i] = null
                    write(frame(MSG_ADDED, i, State()))
                }
            }
            runCatching { readLoop(socket) }
                .onFailure { if (running.get()) Log.w(TAG, "the pad socket read failed", it) }
            Log.i(TAG, "the guest detached from the pad socket")
            synchronized(lock) {
                if (client === socket) {
                    client = null
                    out = null
                }
            }
            runCatching { socket.close() }
        }
    }

    /** Rumble, and nothing else — the guest has no other reason to speak. */
    private fun readLoop(socket: LocalSocket) {
        val input: InputStream = socket.inputStream
        val frame = ByteArray(FRAME)
        while (running.get()) {
            var filled = 0
            while (filled < FRAME) {
                val got = input.read(frame, filled, FRAME - filled)
                if (got <= 0) return
                filled += got
            }
            if (frame[0].toInt() and 0xFF != MSG_RUMBLE) continue
            onRumble(
                frame[1].toInt() and 0xFF,
                u16(frame, 2),
                u16(frame, 4),
                u16(frame, 6) or (u16(frame, 8) shl 16),
            )
        }
    }

    private fun write(frame: ByteArray) {
        val stream = out ?: return
        try {
            stream.write(frame)
            stream.flush()
        } catch (e: IOException) {
            // Not fatal and not worth a log line per frame: the accept loop is
            // already blocked on a read that is about to return end-of-stream.
            out = null
        }
    }

    private fun u16(buffer: ByteArray, at: Int): Int =
        (buffer[at].toInt() and 0xFF) or ((buffer[at + 1].toInt() and 0xFF) shl 8)

    internal companion object {
        /**
         * The twenty bytes.
         *
         * ```
         *  0     type        1 added, 2 removed, 3 state
         *  1     index       the slot, 0..3
         *  2..3  buttons     u16, bit n is HID button usage n+1
         *  4     hat         1 up, 2 down, 4 left, 8 right
         *  5     reserved
         *  6..13 lx ly rx ry i16 each, full scale, Y positive downwards
         * 14..17 lt rt       u16 each, 0..32767
         * 18..19 reserved
         * ```
         *
         * Y downwards because that is what Android reports and what SDL reports,
         * and the bus applies the same `-v - 1` flip on the way into the HID
         * report that `bus_sdl.c` does. Copying a known-correct implementation
         * rather than inventing a third convention for the two ends to disagree
         * about.
         *
         * On the companion so that a test can check the bytes without a socket.
         * The other end of this is C in a Wine patch, so a field at the wrong
         * offset is invisible until a controller is in somebody's hands — which
         * is the most expensive place in this project to find a typo.
         */
        fun frame(type: Int, index: Int, state: State): ByteArray {
            val f = ByteArray(FRAME)
            f[0] = type.toByte()
            f[1] = index.toByte()
            putShort(f, 2, state.buttons)
            f[4] = state.hat.toByte()
            putShort(f, 6, state.lx)
            putShort(f, 8, state.ly)
            putShort(f, 10, state.rx)
            putShort(f, 12, state.ry)
            putShort(f, 14, state.lt)
            putShort(f, 16, state.rt)
            return f
        }

        /** Little-endian, which is the native order on every target this runs on. */
        private fun putShort(buffer: ByteArray, at: Int, value: Int) {
            buffer[at] = (value and 0xFF).toByte()
            buffer[at + 1] = ((value shr 8) and 0xFF).toByte()
        }

        private const val TAG = "VesselPad"

        /**
         * The abstract socket name, per display.
         *
         * Per display because two sessions would otherwise fight over one name,
         * and the second `bind` is the one that fails -- silently, from the
         * user's point of view, as a container whose pad does nothing.
         */
        fun socketName(display: Int): String = "vessel-pad-$display"

        /** Four, because XInput has four slots and there is no fifth thing to be. */
        const val SLOTS = 4

        const val FRAME = 20
        private const val BACKLOG = 2

        const val MSG_ADDED = 0x01
        const val MSG_REMOVED = 0x02
        const val MSG_STATE = 0x03
        const val MSG_RUMBLE = 0x81

        /** Full scale for a stick axis, and for a trigger's upper half. */
        const val AXIS_MAX = 32767

        /** Bit positions in [State.buttons], in the order `xinput1_3` reads them. */
        const val BTN_A = 0
        const val BTN_B = 1
        const val BTN_X = 2
        const val BTN_Y = 3
        const val BTN_L1 = 4
        const val BTN_R1 = 5
        const val BTN_SELECT = 6
        const val BTN_START = 7
        const val BTN_THUMB_L = 8
        const val BTN_THUMB_R = 9

        const val HAT_UP = 1
        const val HAT_DOWN = 2
        const val HAT_LEFT = 4
        const val HAT_RIGHT = 8
    }
}
