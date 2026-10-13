package dev.glasslauncher.downloads

import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLongArray

/** Downloads one file with parallel ranges and resume. No Android UI types; the queue and service sit on top. */
class DownloadEngine(
    private val http: OkHttpClient = OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build(),
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val rangeMinBytes: Long = 8L shl 20,
    private val retries: Int = 3,
    private val backoffMs: (attempt: Int) -> Long = { 1000L shl (it - 1) },
) {
    // The write path (eMMC) is the limit, and Home must not jank while it runs.
    private val io = Executors.newCachedThreadPool { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "download-io").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private class Restart : Exception()

    suspend fun probe(url: String): Probe = withContext(io) {
        withRetry {
            val head = try {
                http.newCall(request(url).head().build()).execute().use { r ->
                    if (!r.isSuccessful) null
                    else describe(r, r.header("Content-Length")?.toLongOrNull() ?: -1, r.header("Accept-Ranges").orEmpty().contains("bytes", true))
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive(); null
            }
            if (head != null && head.size >= 0 && head.ranges) return@withRetry head
            // A server that rejects HEAD, or does not say it takes ranges, gets the one-byte GET: it answers for real.
            http.newCall(request(url).header("Range", "bytes=0-0").build()).execute().use { r ->
                if (!r.isSuccessful) throw DownloadFailure.Http(r.code)
                if (r.code == 206) {
                    val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
                    describe(r, total, total > 0)
                } else {
                    describe(r, r.header("Content-Length")?.toLongOrNull() ?: -1, false)
                }
            }
        }
    }

    suspend fun download(req: DownloadRequest, onProgress: (Progress) -> Unit): Finished {
        val probe = probe(req.url)
        var fresh = false
        while (true) {
            try {
                return attempt(req, probe, fresh, onProgress)
            } catch (e: Restart) {
                if (fresh) throw DownloadFailure.Network("the server keeps refusing to continue")
                fresh = true
            }
        }
    }

    private suspend fun attempt(req: DownloadRequest, probe: Probe, fresh: Boolean, onProgress: (Progress) -> Unit): Finished =
        withContext(io) {
            val part = File(req.dir, "${req.id}.part")
            val metaFile = File(req.dir, "${req.id}.part.json")
            req.dir.mkdirs()
            val old = readMeta(metaFile)
            val usable = !fresh && old != null && part.exists() && probe.ranges && old.url == req.url && old.size == probe.size &&
                (old.etag != null || old.lastModified != null) && old.etag == probe.etag && old.lastModified == probe.lastModified
            val restarted = fresh || (!usable && (old?.segments?.sumOf { it.written } ?: 0) > 0)
            if (!usable) { part.delete(); metaFile.delete() }

            val ranged = probe.ranges && !fresh
            val segments = if (usable) old.segments else plan(probe, if (ranged && probe.size > rangeMinBytes) req.segments.coerceIn(1, 8) else 1)
            val have = segments.sumOf { it.written }
            if (probe.size > 0) {
                val needed = probe.size - have
                val free = freeSpace(req.dir)
                if (needed > free) throw DownloadFailure.NoSpace(needed, free)
            }
            val meta = PartMeta(req.url, probe.size, probe.etag, probe.lastModified, segments)
            RandomAccessFile(part, "rw").use { raf ->
                if (probe.size > 0 && raf.length() != probe.size) raf.setLength(probe.size)
                writeMeta(metaFile, meta)
                val written = AtomicLongArray(LongArray(segments.size) { segments[it].written })
                val calls = ConcurrentHashMap.newKeySet<Call>()
                fun snapshot() = meta.copy(segments = segments.mapIndexed { i, s -> s.copy(written = written[i]) })
                try {
                    coroutineScope {
                        // Blocking socket reads ignore coroutine cancellation; cancelling the calls frees them.
                        val watcher = launch(Dispatchers.Unconfined) {
                            try { awaitCancellation() } finally { calls.forEach { it.cancel() } }
                        }
                        val ticker = launch {
                            var last = have; var lastAt = System.nanoTime(); var ticks = 0
                            onProgress(Progress(have, probe.size, 0, restarted))
                            while (true) {
                                delay(PROGRESS_MS)
                                val now = System.nanoTime(); val total = written.sum()
                                val speed = (total - last) * 1_000_000_000L / (now - lastAt).coerceAtLeast(1)
                                last = total; lastAt = now
                                onProgress(Progress(total, probe.size, speed, restarted))
                                if (++ticks % 4 == 0) writeMeta(metaFile, snapshot())
                            }
                        }
                        try {
                            segments.indices.filter { !segments[it].done }.map { i ->
                                async(io) { runSegment(i, segments, req.url, probe, ranged, raf.channel, written, calls) }
                            }.awaitAll()
                        } finally {
                            ticker.cancel(); watcher.cancel()
                        }
                    }
                } finally {
                    // Paused, failed or done: what is on disk must match the table, so the next run continues from it.
                    writeMeta(metaFile, snapshot())
                }
                val total = written.sum()
                onProgress(Progress(total, probe.size, 0, restarted))
            }

            val actual = sha256(part)
            req.sha256?.let {
                if (!it.equals(actual, ignoreCase = true)) {
                    part.delete(); metaFile.delete()
                    throw DownloadFailure.HashMismatch(it.lowercase(), actual)
                }
            }
            val target = claim(part, req.dir, req.name?.let(::safeName) ?: probe.fileName?.let(::safeName) ?: nameFromUrl(probe.finalUrl))
            metaFile.delete()
            Finished(target, target.length(), actual, probe.finalUrl, restarted)
        }

    private fun AtomicLongArray.sum() = (0 until length()).fold(0L) { acc, i -> acc + get(i) }

    private fun plan(probe: Probe, n: Int): List<Segment> {
        if (probe.size <= 0) return listOf(Segment(0, -1))
        if (n == 1) return listOf(Segment(0, probe.size - 1))
        val each = probe.size / n
        return (0 until n).map { Segment(it * each, if (it == n - 1) probe.size - 1 else (it + 1) * each - 1) }
    }

    private suspend fun runSegment(
        i: Int, segments: List<Segment>, url: String, probe: Probe, ranged: Boolean,
        channel: FileChannel, written: AtomicLongArray, calls: MutableSet<Call>,
    ) {
        val seg = segments[i]
        withRetry {
            var done = written[i]
            if (!ranged && done > 0) { written[i] = 0; done = 0 } // cannot continue: refetch the stream
            val from = seg.start + done
            val useRange = ranged && seg.end >= 0 && (from > 0 || segments.size > 1)
            val builder = request(url)
            if (useRange) {
                builder.header("Range", "bytes=$from-${seg.end}")
                (probe.etag?.takeIf { !it.startsWith("W/") } ?: probe.lastModified)?.let { builder.header("If-Range", it) }
            }
            val call = http.newCall(builder.build())
            calls.add(call)
            try {
                call.execute().use { r ->
                    if (useRange && (r.code == 200 || r.code == 416)) throw Restart()
                    if (!r.isSuccessful) throw DownloadFailure.Http(r.code)
                    if (useRange) {
                        val start = r.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                        if (r.code != 206 || start != from) throw IOException("Unexpected range answer")
                    }
                    copy(r, i, from, if (seg.end >= 0) seg.end + 1 else Long.MAX_VALUE, channel, written)
                }
            } finally {
                calls.remove(call)
            }
        }
    }

    private suspend fun copy(r: Response, i: Int, from: Long, limit: Long, channel: FileChannel, written: AtomicLongArray) {
        val source = r.body.source()
        val buf = ByteArray(BUFFER)
        var pos = from
        val base = pos - written[i]
        while (pos < limit) {
            currentCoroutineContext().ensureActive()
            val want = minOf(buf.size.toLong(), limit - pos).toInt()
            var n = 0
            var failure: IOException? = null
            while (n < want) {
                val got = try { source.read(buf, n, want - n) } catch (e: IOException) { failure = e; break }
                if (got < 0) break
                n += got
            }
            if (n > 0) {
                try {
                    val bb = ByteBuffer.wrap(buf, 0, n)
                    var at = pos
                    while (bb.hasRemaining()) at += channel.write(bb, at)
                } catch (e: IOException) {
                    if (e.message.orEmpty().contains("No space left", true)) throw DownloadFailure.NoSpace(if (limit == Long.MAX_VALUE) 0 else limit - pos, 0)
                    throw e
                }
                pos += n
                written[i] = pos - base
            }
            failure?.let { throw it }
            if (n < want) break
        }
        if (limit != Long.MAX_VALUE && pos < limit) throw IOException("Connection closed early")
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var failures = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadFailure.Http) {
                if (!e.retriable || ++failures > retries) throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (++failures > retries) throw DownloadFailure.Network(e.message ?: e.javaClass.simpleName, e)
            }
            delay(backoffMs(failures))
        }
    }

    // Identity keeps OkHttp from gzip-decoding, which would break byte ranges.
    private fun request(url: String) = Request.Builder().url(url).header("Accept-Encoding", "identity")

    private fun describe(r: Response, size: Long, ranges: Boolean) = Probe(
        finalUrl = r.request.url.toString(),
        size = size,
        ranges = ranges && size > 0,
        etag = r.header("ETag"),
        lastModified = r.header("Last-Modified"),
        fileName = fileNameOf(r.header("Content-Disposition")),
    )

    @Synchronized
    private fun claim(part: File, dir: File, name: String): File {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var target = File(dir, name)
        var n = 1
        while (target.exists()) target = File(dir, "${name.substring(0, dot)} (${n++})${name.substring(dot)}")
        if (!part.renameTo(target)) throw DownloadFailure.Network("could not save the file")
        return target
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { s ->
            val buf = ByteArray(BUFFER)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val BUFFER = 256 * 1024
        private const val PROGRESS_MS = 250L
        private val json = Json { ignoreUnknownKeys = true }

        /** Removes a download's partial file and sidecar. */
        fun discard(dir: File, id: String) {
            File(dir, "$id.part").delete(); File(dir, "$id.part.json").delete()
        }

        private fun readMeta(f: File) = runCatching { json.decodeFromString<PartMeta>(f.readText()) }.getOrNull()

        private fun writeMeta(f: File, meta: PartMeta) {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(json.encodeToString(PartMeta.serializer(), meta))
            tmp.renameTo(f)
        }

        internal fun fileNameOf(header: String?): String? {
            header ?: return null
            Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let {
                val charset = it.groupValues[1].ifBlank { "UTF-8" }
                runCatching { return URLDecoder.decode(it.groupValues[2].trim().trim('"').replace("+", "%2B"), charset) }
            }
            return Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.trim()
        }

        internal fun safeName(raw: String): String {
            val clean = raw.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it !in ":*?\"<>|" }.trim().trim('.')
            return clean.ifBlank { "download" }
        }

        private fun nameFromUrl(url: String) =
            safeName(url.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotBlank() }.orEmpty())
    }
}
