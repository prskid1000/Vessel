package app.vessel.display

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android's clipboard, as the X server needs to see it.
 *
 * **The Android half of clipboard sync, and it deliberately knows nothing about
 * X11.** The vendored server owns the selection protocol
 * (`com.winlator.xserver.ClipboardSelection`); this owns the `ClipboardManager`,
 * the change listener and the echo suppression. `XServerDisplay` is the ten lines
 * that join them, which is what keeps it the only file in `app.vessel` that
 * imports `com.winlator`.
 *
 * ## Reading is a user-visible act, so it happens as late as possible
 *
 * Android logs an access notification every time an app reads the clipboard, and
 * from Android 12 it shows the user a toast saying so. [currentText] is therefore
 * called by the server only while answering a paste — never on a change
 * notification, never on a timer. [onChanged] carries no content for exactly that
 * reason: the server takes ownership of the X selection knowing only that
 * *something* changed, and asks what it is if and when a guest program pastes.
 *
 * ## The echo, and why a counter rather than a comparison
 *
 * [publish] writes to the clipboard, which makes Android fire the change listener,
 * which would tell the server to take the X selection back from the guest that
 * just copied — clearing its ownership and serving every later paste out of a
 * stale clip. The obvious guard is to compare the new clip against what we wrote,
 * and it is the wrong one: comparing means reading, and reading is the thing this
 * class exists to avoid doing casually. So a self-write is *counted* instead, and
 * the next callback consumes the count without looking at anything.
 *
 * [SELF_WRITE_WINDOW_MS] is what stops a leaked count from swallowing a real
 * change forever. A `setPrimaryClip` that somehow produced no callback would
 * otherwise leave the counter permanently positive and the clipboard permanently
 * one-way; after the window the count is discarded and the change is treated as
 * the user's.
 *
 * ## Known limits, none of them worked around
 *
 * - **Text only.** A clip with no `text/plain` item is not read; images and
 *   arbitrary formats are out of scope on both sides of the seam.
 * - **Focus.** From Android 10, `getPrimaryClip` returns null for an app that does
 *   not have the input focus, and logs a denial. During a session Vessel has it,
 *   so a paste inside the guest works; a paste triggered while the app is
 *   backgrounded cannot, and reports empty rather than pretending.
 * - `Item.text` rather than `coerceToText`, because coercing a `content://` item
 *   opens a `ContentResolver` — a blocking call, and [currentText] runs on an X
 *   client's request thread with a server lock held.
 *
 * **Nothing here has been run.** It needs a device, a real clipboard and a user
 * copying something; see the report in `com/winlator/README.md` item 30.
 */
class AndroidClipboard(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Null on a context with no clipboard service, which is not a configuration
     * this app ships into but is cheaper to tolerate than to assert.
     */
    private val manager: ClipboardManager? =
        appContext.getSystemService(ClipboardManager::class.java)

    private val main = Handler(Looper.getMainLooper())

    /**
     * Called when Android's clipboard changed and it was not our own write.
     *
     * Carries no text. See the class comment: the content is fetched only when
     * something actually pastes.
     */
    var onChanged: (() -> Unit)? = null

    /** Self-writes not yet accounted for by a callback. */
    private val pendingSelfWrites = AtomicInteger(0)

    /** When the most recent self-write was issued, for [SELF_WRITE_WINDOW_MS]. */
    @Volatile
    private var lastSelfWriteAt = 0L

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (consumeSelfWrite()) return@OnPrimaryClipChangedListener
        onChanged?.invoke()
    }

    private var listening = false

    /**
     * Start watching. Idempotent, and safe to call on the main thread only —
     * `addPrimaryClipChangedListener` delivers on the thread that registered.
     */
    fun start() {
        val clipboard = manager ?: run {
            Log.w(TAG, "no clipboard service; clipboard sync is off for this session")
            return
        }
        if (listening) return
        runCatching { clipboard.addPrimaryClipChangedListener(listener) }
            .onSuccess { listening = true }
            .onFailure { Log.w(TAG, "could not watch the clipboard", it) }
    }

    /** Stop watching. Called on session teardown; the listener would outlive it. */
    fun stop() {
        val clipboard = manager
        if (clipboard != null && listening) {
            runCatching { clipboard.removePrimaryClipChangedListener(listener) }
                .onFailure { Log.w(TAG, "could not stop watching the clipboard", it) }
        }
        listening = false
        onChanged = null
        pendingSelfWrites.set(0)
    }

    /**
     * The clipboard's text, or null.
     *
     * **Called at paste time and nowhere else.** Every call is an access the user
     * may be shown; see the class comment.
     */
    fun currentText(): String? {
        val clipboard = manager ?: return null
        return runCatching {
            val clip = clipboard.primaryClip ?: return null
            if (!clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                !clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            ) {
                return null
            }
            (0 until clip.itemCount)
                .asSequence()
                .mapNotNull { clip.getItemAt(it).text?.toString() }
                .firstOrNull { it.isNotEmpty() }
        }.onFailure {
            // Includes the security denial an unfocused app gets on Android 10+.
            Log.d(TAG, "could not read the clipboard", it)
        }.getOrNull()
    }

    /**
     * Put text on the clipboard, and arrange for the callback it causes to be
     * ignored.
     *
     * Posted to the main thread rather than run inline: this is called from an X
     * client's request thread with a server lock held, and `setPrimaryClip` is a
     * binder round trip. The count is incremented on that thread too, so it can
     * never be consumed by a callback that arrives before the write.
     */
    fun publish(text: String) {
        val clipboard = manager ?: return
        main.post {
            pendingSelfWrites.incrementAndGet()
            lastSelfWriteAt = SystemClock.elapsedRealtime()
            runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, text)) }
                .onFailure {
                    // The write did not happen, so no callback is coming and the
                    // count would sit there suppressing a real change.
                    pendingSelfWrites.decrementAndGet()
                    Log.w(TAG, "could not put the guest's clipboard on Android's", it)
                }
        }
    }

    /**
     * True when this notification is the echo of our own [publish].
     *
     * Stale counts are dropped rather than consumed — see [SELF_WRITE_WINDOW_MS].
     */
    private fun consumeSelfWrite(): Boolean {
        if (pendingSelfWrites.get() <= 0) return false
        if (SystemClock.elapsedRealtime() - lastSelfWriteAt > SELF_WRITE_WINDOW_MS) {
            pendingSelfWrites.set(0)
            return false
        }
        return pendingSelfWrites.decrementAndGet() >= 0
    }

    private companion object {
        const val TAG = "VesselClipboard"

        /** What the clip is labelled as in Android's own clipboard UI. */
        const val LABEL = "Vessel"

        /**
         * How long a self-write may go unexplained before its suppression lapses.
         *
         * Two seconds. `setPrimaryClip` calls the listener synchronously in
         * practice, so this is never reached on a healthy path; it exists so that a
         * write whose callback never arrives costs one ignored change rather than
         * every change for the rest of the session. Chosen rather than measured —
         * there is no threshold to find here, only a margin.
         */
        const val SELF_WRITE_WINDOW_MS = 2_000L
    }
}
