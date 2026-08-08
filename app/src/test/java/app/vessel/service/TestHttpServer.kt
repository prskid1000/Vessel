package app.vessel.service

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A four-verb HTTP server, in the test source set, with no dependency behind it.
 *
 * `ComponentDownloader` is mostly a state machine over HTTP semantics — ranged
 * resume, a server that ignores the range, a 404, a body that does not hash to
 * what the registry said — and none of that can be tested against a pure
 * function. MockWebServer would be the obvious tool and would mean a new test
 * dependency in `app/build.gradle.kts`; this is sixty lines of `ServerSocket`
 * that needs nothing.
 *
 * Single connection at a time, `Connection: close` on every response, and the
 * request lines are recorded so a test can assert that a `Range` header was
 * actually sent rather than inferring it from the result.
 */
class TestHttpServer(
    private val body: ByteArray,
    /** Answered for every request when non-null; the body is not sent. */
    private val status: Int = 200,
    /** True to answer 200 with the whole body even when a Range was asked for. */
    private val ignoreRange: Boolean = false,
) : Closeable {

    private val socket = ServerSocket(0)
    private val accepting: Thread

    /** Every request line and header this server saw, newest last. */
    val requests: MutableList<List<String>> = CopyOnWriteArrayList()

    val url: String get() = "http://127.0.0.1:${socket.localPort}/component.wcp"

    init {
        accepting = thread(isDaemon = true, name = "TestHttpServer") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { serve(client) }
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        val head = readHead(client.getInputStream())
        if (head.isEmpty()) return
        requests += head

        val out = BufferedOutputStream(client.getOutputStream())
        if (status != 200) {
            out.write("HTTP/1.1 $status Nope\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        val range = head.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
            ?.substringAfter("bytes=")
            ?.substringBefore('-')
            ?.trim()
            ?.toLongOrNull()

        if (range == null || ignoreRange) {
            respond(out, 200, "OK", body, null)
            return
        }
        if (range >= body.size) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        val slice = body.copyOfRange(range.toInt(), body.size)
        respond(out, 206, "Partial Content", slice, "bytes $range-${body.size - 1}/${body.size}")
    }

    private fun respond(
        out: BufferedOutputStream,
        code: Int,
        reason: String,
        payload: ByteArray,
        contentRange: String?,
    ) {
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Accept-Ranges: bytes\r\n")
            contentRange?.let { append("Content-Range: $it\r\n") }
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        out.write(payload)
        out.flush()
    }

    /** Request line plus headers, stopping at the blank line. Bodies are never sent here. */
    private fun readHead(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return lines
            if (c == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                line.setLength(0)
                if (text.isEmpty()) return lines
                lines += text
            } else {
                line.append(c.toChar())
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
        accepting.interrupt()
    }
}
