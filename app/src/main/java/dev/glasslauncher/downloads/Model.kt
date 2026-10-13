package dev.glasslauncher.downloads

import kotlinx.serialization.Serializable
import java.io.File

/** One byte range of the file; [end] is inclusive, or -1 when the size is unknown. */
@Serializable
data class Segment(val start: Long, val end: Long, val written: Long = 0) {
    val done get() = end >= 0 && written >= end - start + 1
}

/** Sidecar `<id>.part.json`: what a later run needs to trust and continue the `.part` file. */
@Serializable
data class PartMeta(
    val url: String,
    val size: Long,
    val etag: String?,
    val lastModified: String?,
    val segments: List<Segment>,
)

data class Probe(
    val finalUrl: String,
    val size: Long,
    val ranges: Boolean,
    val etag: String?,
    val lastModified: String?,
    val fileName: String?,
)

data class DownloadRequest(
    val id: String,
    val url: String,
    val dir: File,
    val name: String? = null,
    val sha256: String? = null,
    val segments: Int = DEFAULT_SEGMENTS,
)

data class Progress(val downloaded: Long, val size: Long, val bytesPerSecond: Long, val restarted: Boolean)

data class Finished(val file: File, val size: Long, val sha256: String, val finalUrl: String, val restarted: Boolean)

sealed class DownloadFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Http(val code: Int) : DownloadFailure("Server answered HTTP $code") {
        val retriable get() = code >= 500 || code == 429 || code == 408
    }

    class NoSpace(val needed: Long, val free: Long) :
        DownloadFailure("Not enough space: needs ${megabytes(needed)} more, ${megabytes(free)} free")

    class HashMismatch(val expected: String, val actual: String) :
        DownloadFailure("SHA-256 does not match (expected $expected, got $actual)")

    class Network(detail: String, cause: Throwable? = null) : DownloadFailure("Network error: $detail", cause)
}

private fun megabytes(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)

enum class DownloadState { Queued, Active, Paused, Done, Failed }

@Serializable
data class DownloadItem(
    val id: String,
    val url: String,
    val name: String? = null,
    val expectedSha256: String? = null,
    val state: DownloadState = DownloadState.Queued,
    val size: Long = -1,
    val downloaded: Long = 0,
    val bytesPerSecond: Long = 0,
    val error: String? = null,
    /** Final file name inside the downloads folder, once done. */
    val file: String? = null,
    val sha256: String? = null,
    val finalUrl: String? = null,
    /** The server could not continue a partial file, so it started over. */
    val restarted: Boolean = false,
)

const val DEFAULT_SEGMENTS = 4
const val DEFAULT_CONCURRENCY = 2
