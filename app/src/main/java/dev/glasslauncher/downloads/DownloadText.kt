package dev.glasslauncher.downloads

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.glasslauncher.system.RemoteNotice
import java.io.File
import java.util.Locale

/** How the manager words a download: size, speed, time left, and what a row says in each state. */
object DownloadText {
    fun bytes(n: Long): String = when {
        n < 1_000 -> "$n B"
        n < 1_000_000 -> "%.0f KB".format(Locale.US, n / 1_000.0)
        n < 1_000_000_000 -> "%.1f MB".format(Locale.US, n / 1_000_000.0)
        else -> "%.2f GB".format(Locale.US, n / 1_000_000_000.0)
    }

    fun speed(bytesPerSecond: Long) = "${bytes(bytesPerSecond)}/s"

    /** "2 min left"; null when the size or the speed isn't known. */
    fun left(item: DownloadItem): String? {
        if (item.state != DownloadState.Active || item.size <= 0 || item.bytesPerSecond <= 0) return null
        val s = ((item.size - item.downloaded) / item.bytesPerSecond).coerceAtLeast(1)
        return when {
            s < 60 -> "$s s left"
            s < 3_600 -> "${(s + 30) / 60} min left"
            else -> "${s / 3_600} h ${(s % 3_600 + 30) / 60} min left"
        }
    }

    fun fraction(item: DownloadItem): Float = when {
        item.state == DownloadState.Done -> 1f
        item.size > 0 -> (item.downloaded.toFloat() / item.size).coerceIn(0f, 1f)
        else -> 0f
    }

    fun name(item: DownloadItem): String =
        item.file ?: item.name ?: item.url.substringBefore('?').substringAfterLast('/').ifBlank { null } ?: DownloadInput.host(item.url) ?: item.url

    fun host(item: DownloadItem): String = DownloadInput.host(item.finalUrl ?: item.url) ?: ""

    fun isApk(item: DownloadItem) = name(item).endsWith(".apk", ignoreCase = true)

    /** The line a code's confirm card adds for an app that comes with no checksum; empty otherwise. */
    fun checksumNote(url: String, name: String?, sha256: String?): String =
        if (sha256 == null && (url.substringBefore('?').endsWith(".apk", true) || name?.endsWith(".apk", true) == true)) " No checksum is published for this app." else ""

    /** The row's middle text: host and how much is there. A failed row says why instead. */
    fun detail(item: DownloadItem): String = when (item.state) {
        DownloadState.Failed -> item.error ?: "Failed"
        DownloadState.Done -> listOf(host(item), bytes(item.size)).filter { it.isNotEmpty() }.joinToString(" · ")
        else -> listOf(host(item), when {
            item.size <= 0 -> bytes(item.downloaded)
            item.downloaded == 0L -> bytes(item.size)
            else -> "${bytes(item.downloaded)} of ${bytes(item.size)}"
        }).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** The row's right-hand text: speed and time left while it runs, else the state. */
    fun status(item: DownloadItem): String = when (item.state) {
        DownloadState.Active -> listOfNotNull(if (item.bytesPerSecond > 0) speed(item.bytesPerSecond) else "Starting", left(item)).joinToString(" · ")
        DownloadState.Queued -> "Waiting"
        DownloadState.Paused -> "Paused"
        DownloadState.Done -> "Done"
        DownloadState.Failed -> "Failed"
    }

    /** Control Center's value: the speed of a lone download, else how many run. Null when none do. */
    fun summary(items: List<DownloadItem>): String? {
        val active = items.filter { it.state == DownloadState.Active }
        return when {
            active.isEmpty() -> null
            active.size == 1 && active[0].bytesPerSecond > 0 -> speed(active[0].bytesPerSecond)
            else -> "${active.size} active"
        }
    }

    /** Hands a finished file to the system chooser, by its MIME type. False when nothing can open it. */
    fun open(context: Context, file: File): Boolean {
        val mime = FileBrowser.mime(file.name) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", file)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(view, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { RemoteNotice.show(context, "No app here can open ${file.name}") }.isSuccess
    }
}
