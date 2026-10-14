package dev.glasslauncher.system

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.Executors

/**
 * The app's last frame, taken when Home or Back is pressed in it (RemoteKeysService), for the close to shrink back
 * into its tile (P147, as tvOS does). 480×270, in memory only, never saved; [take] hands it over once and forgets it.
 * Without the accessibility service (or when the system refuses a screenshot) there is none, and the close falls back
 * to the app's starting-window colour.
 */
object CloseSnapshot {
    private const val W = 480
    private const val H = 270
    /** A snapshot older than this isn't of the close that is playing. */
    private const val FRESH_MS = 3_000L
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "glass-close-snapshot") }

    private class Shot(val pkg: String, val image: ImageBitmap, val at: Long)

    /** Compose state: a close already playing picks the snapshot up as soon as it lands. */
    private var latest by mutableStateOf<Shot?>(null)

    /** Shrinks [shot] off the main thread; [shot] is recycled. */
    fun offer(pkg: String, shot: Bitmap) {
        val at = android.os.SystemClock.uptimeMillis()
        worker.execute {
            val small = runCatching {
                val soft = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false) else shot
                val s = Bitmap.createScaledBitmap(soft, W, H, true)
                if (soft !== shot && soft !== s) soft.recycle()
                s.copy(Bitmap.Config.HARDWARE, false)?.also { if (it !== s) s.recycle() } ?: s
            }.getOrNull()
            shot.recycle()
            if (small != null) android.os.Handler(android.os.Looper.getMainLooper()).post { latest = Shot(pkg, small.asImageBitmap(), at) }
        }
    }

    /**
     * No snapshot of [pkg] yet: use the app switcher's preview of it (blurred, up to 30 s old; [AppPreviews]), which
     * still reads as the app shrinking away.
     */
    fun preferPreview(context: android.content.Context, pkg: String) {
        if (peek(pkg) != null) return
        val file = AppPreviews.file(context, pkg)
        val at = android.os.SystemClock.uptimeMillis()
        worker.execute {
            val b = runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull() ?: return@execute
            val hw = b.copy(Bitmap.Config.HARDWARE, false)?.also { b.recycle() } ?: b
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (peek(pkg) == null) latest = Shot(pkg, hw.asImageBitmap(), at) }
        }
    }

    /** The snapshot of [pkg] if one is fresh (reads Compose state, so a draw that calls it redraws when it lands). */
    fun peek(pkg: String): ImageBitmap? {
        val s = latest ?: return null
        return s.image.takeIf { s.pkg == pkg && android.os.SystemClock.uptimeMillis() - s.at < FRESH_MS }
    }

    /** Drops the snapshot (the close has played, or Home was left another way). */
    fun clear() { latest = null }
}
