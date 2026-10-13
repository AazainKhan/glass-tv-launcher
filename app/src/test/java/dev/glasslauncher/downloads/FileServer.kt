package dev.glasslauncher.downloads

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A file host that honours Range / If-Range like a real one, with switches for the awkward cases. */
class FileServer(var content: ByteArray, var etag: String? = "\"v1\"", private val ranges: Boolean = true) : Dispatcher() {
    val server = MockWebServer().also { it.dispatcher = this; it.start() }
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val gets get() = requests.filter { it.method == "GET" }

    /** Advertises ranges but answers every Range request with the whole file (a proxy in the way). */
    @Volatile var ignoreRange = false

    /** Answer the next N GETs with 503. */
    val failNext = AtomicInteger()

    /** Cut the next N GET bodies off halfway. */
    val cutNext = AtomicInteger()

    fun url(path: String = "/file.bin") = server.url(path).toString()

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val b = MockResponse.Builder().addHeader("Content-Type", "application/octet-stream")
        etag?.let { b.addHeader("ETag", it) }
        if (ranges) b.addHeader("Accept-Ranges", "bytes")
        if (request.method == "HEAD") return b.addHeader("Content-Length", content.size.toString()).build()
        if (failNext.getAndUpdate { maxOf(0, it - 1) } > 0) return MockResponse.Builder().code(503).build()

        val range = request.headers["Range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val ifRange = request.headers["If-Range"]
        val ranged = ranges && !ignoreRange && range != null && (ifRange == null || ifRange == etag)
        val from = if (ranged) range!!.groupValues[1].toInt() else 0
        val to = if (ranged) range!!.groupValues[2].toIntOrNull() ?: (content.size - 1) else content.size - 1
        if (ranged) b.code(206).addHeader("Content-Range", "bytes $from-$to/${content.size}")
        if (cutNext.getAndUpdate { maxOf(0, it - 1) } > 0) b.onResponseBody(SocketEffect.CloseSocket())
        return b.body(Buffer().write(content, from, to - from + 1)).build()
    }

    fun stop() = server.close()
}

fun testBytes(size: Int, seed: Int = 1) = ByteArray(size) { ((it * 31 + seed * 7 + (it shr 8)) and 0xff).toByte() }
