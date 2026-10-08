package dev.glasslauncher.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.concurrent.Executors

/**
 * The app switcher's card previews: a small, blurred snapshot of each app as it was last on screen,
 * taken by RemoteKeysService (AccessibilityService.takeScreenshot) and kept in the cache as a ~10 KB JPEG.
 * Blurred on purpose: it reads as the app at a glance, costs nothing to draw, and keeps what was on
 * screen (a message, an account page) unreadable. Nothing leaves the TV.
 */
object AppPreviews {
    private const val W = 320
    private const val H = 180
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "glass-previews").apply { priority = Thread.MIN_PRIORITY } }

    private fun dir(context: Context) = File(context.cacheDir, "previews").apply { mkdirs() }
    fun file(context: Context, pkg: String) = File(dir(context), "$pkg.jpg")

    /** Shrinks, blurs and saves [shot] off the main thread; [shot] is recycled. */
    fun save(context: Context, pkg: String, shot: Bitmap) {
        val app = context.applicationContext
        worker.execute {
            runCatching {
                val soft = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false) else shot
                // Down to 80 px wide and back up in steps: a soft blur, done once per snapshot.
                var b = Bitmap.createScaledBitmap(soft, 80, 45, true)
                if (soft !== shot) soft.recycle()
                if (isMostlyBlack(b)) { b.recycle(); return@runCatching } // DRM video or a blank frame: keep the last one
                for ((w, h) in listOf(160 to 90, W to H)) {
                    val next = Bitmap.createScaledBitmap(b, w, h, true)
                    b.recycle(); b = next
                }
                val tmp = File(dir(app), "$pkg.tmp")
                tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                tmp.renameTo(file(app, pkg))
                b.recycle()
            }
            shot.recycle()
        }
    }

    fun load(context: Context, pkg: String): Bitmap? =
        file(context, pkg).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }

    private fun isMostlyBlack(b: Bitmap): Boolean {
        val tiny = Bitmap.createScaledBitmap(b, 8, 8, true)
        var sum = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val c = tiny.getPixel(x, y)
            sum += ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
        }
        tiny.recycle()
        return sum / (64 * 3) < 10
    }
}
