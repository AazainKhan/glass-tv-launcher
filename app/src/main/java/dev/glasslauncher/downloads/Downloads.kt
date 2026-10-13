package dev.glasslauncher.downloads

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Process-wide entry point: the queue the UI observes and the service keeps alive. */
object Downloads {
    @Volatile private var queue: DownloadQueue? = null

    fun queue(context: Context): DownloadQueue = queue ?: synchronized(this) {
        queue ?: create(context.applicationContext).also { queue = it }
    }

    /** `Android/data/<pkg>/files/Downloads`: no permission needed, removed with Glass. */
    fun folder(context: Context): File =
        context.getExternalFilesDir("Downloads") ?: File(context.filesDir, "Downloads").also { it.mkdirs() }

    /** Set when the system refused a foreground start from the background; tried again once Glass is on screen. */
    @Volatile internal var retryInForeground = false

    /** Brings the service up for downloads still waiting after a process death or reboot. */
    fun resumePending(context: Context) {
        if (queue(context).items.value.any { it.state == DownloadState.Queued }) startService(context)
    }

    /** Glass is visible, so a foreground start is allowed: bring back the service that was refused earlier. */
    fun onForeground(context: Context) {
        if (!retryInForeground) return
        retryInForeground = false
        if (queue(context).items.value.any { it.state == DownloadState.Active || it.state == DownloadState.Queued }) startService(context)
    }

    internal fun startService(context: Context) {
        try {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        } catch (e: Exception) {
            // API 31+ throws here from the background. The queue keeps going in the process; retry when Glass is shown.
            Log.w("Downloads", "download service did not start", e)
            retryInForeground = true
        }
    }

    private fun create(app: Context): DownloadQueue {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return DownloadQueue(scope, DownloadEngine(), folder(app), File(app.filesDir, "downloads/queue.json")) { startService(app) }
            .also { it.load() }
    }
}
