package app.vessel.data

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileDescriptor
import java.io.IOException

/**
 * One FIFO that every long-lived guest process writes to, for the whole session.
 *
 * ## The bug this exists to fix
 *
 * `ProcessBuilder` gives each child a pipe, and Vessel read it per child. That
 * works only while the child that owns the pipe is the process doing the
 * talking, and for a program started into a running desktop it never is.
 *
 * Launching a program goes through `explorer.exe /desktop=vessel,WxH prog.exe`.
 * Wine's explorer sees that the named desktop already exists, starts `prog.exe`
 * — which inherits its stderr — and **exits, about a second later**. The game
 * is now a grandchild with no parent of ours, and two separate mechanisms then
 * throw its output away:
 *
 *  - [WineProcessRunner.drain] closes the read end 250 ms after the direct child
 *    exits, on purpose, because a pipe held open by surviving descendants has no
 *    EOF and a thread parked in `read(2)` cannot be cancelled; and
 *  - even without that, `ProcessImpl$ProcessPipeInputStream.processExited()`
 *    drains what is buffered and closes the descriptor as soon as the reaper
 *    sees the child go.
 *
 * Measured on the device with Metro 2033 Redux: the session log stopped at
 * `cryptbase.dll` — the last module of the loader's import walk, which is
 * roughly when the launcher exits — while the game went on to create its window,
 * load DXVK's `dxgi` and `d3d11`, and put a dialog on screen. Every line of
 * that was written to a descriptor nobody was reading. Hours went into "the game
 * dies silently at COM init"; the game was fine and the log was deaf.
 *
 * ## Why a FIFO and not a file
 *
 * A regular file would work for the writers and be miserable for the reader:
 * growth has to be polled, a partial line has to be held back, and truncation
 * between sessions is a race. A FIFO gives the reader the same blocking stream
 * it already has, and writes up to `PIPE_BUF` stay atomic, so two guest
 * processes still cannot interleave halves of a line.
 *
 * It is opened **`O_RDWR`** rather than `O_RDONLY`, and that is the detail that
 * makes it work at all:
 *
 *  - `O_RDONLY` on a FIFO blocks until a writer arrives, which would deadlock
 *    the session before the first process starts; and
 *  - a reader that holds only read ends sees EOF every time the last writer
 *    closes — between `wineboot` finishing and the desktop starting, say. With
 *    a write end of our own there is never a last writer, so the stream simply
 *    stays open for the session and [close] is the only thing that ends it.
 *
 * `init_options()` in Wine's `dlls/ntdll/unix/debug.c` `fstat`s fd 2 and gives
 * up on `WINEDEBUG` entirely if it is the null device. A FIFO is not, so the
 * channel set survives the redirect — see `docs/LOGGING.md`, which has the rest
 * of that story.
 */
class GuestOutputPipe private constructor(
    /** Pass this to [ProcessSpec.output]; children append to it. */
    val file: File,
    private val descriptor: FileDescriptor,
) {

    private val stream = FileInputStream(descriptor)

    /**
     * Read lines until [close], handing each to [onLine].
     *
     * Never returns on its own: the pipe has no EOF while this object holds a
     * write end, which is the whole point. Cancelling the coroutine does not
     * unblock a thread parked in `read(2)` either — [close] is what ends it, and
     * teardown calls it.
     */
    fun drain(onLine: (String) -> Unit) {
        try {
            stream.bufferedReader(Charsets.UTF_8).forEachLine(onLine)
        } catch (_: IOException) {
            // Closed underneath us by [close]. That is how this ends.
        }
    }

    /** Unblock [drain] and remove the FIFO. Safe to call twice. */
    fun close() {
        runCatching { Os.close(descriptor) }
        runCatching { stream.close() }
        runCatching { file.delete() }
    }

    companion object {
        /**
         * Create the FIFO at [path], replacing a stale one.
         *
         * Returns null rather than throwing: a session with no FIFO should fall
         * back to per-process pipes and log less, not fail to start.
         */
        fun create(path: File): GuestOutputPipe? {
            path.parentFile?.mkdirs()
            runCatching { path.delete() }
            return try {
                Os.mkfifo(path.absolutePath, MODE)
                val fd = Os.open(path.absolutePath, OsConstants.O_RDWR, 0)
                GuestOutputPipe(path, fd)
            } catch (_: ErrnoException) {
                runCatching { path.delete() }
                null
            }
        }

        /** `0600`: the app is the only reader and every writer is one of its children. */
        private const val MODE = 384
    }
}
