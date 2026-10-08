package dev.glasslauncher.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.concurrent.Executors

/**
 * The app switcher's card previews: a small, blurred snapshot of each app as it was last on screen,
 * taken by RemoteKeysService (AccessibilityService.takeScreenshot) and kept in the cache as a ~25 KB JPEG.
 * Blurred on purpose: it reads as the app at a glance, costs nothing to draw, and keeps what was on
 * screen (a message, an account page) unreadable. Nothing leaves the TV.
 */
object AppPreviews {
    private const val W = 480
    private const val H = 270
    private const val RADIUS = 5
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "glass-previews").apply { priority = Thread.MIN_PRIORITY } }

    private fun dir(context: Context) = File(context.cacheDir, "previews").apply { mkdirs() }
    fun file(context: Context, pkg: String) = File(dir(context), "$pkg.jpg")

    /** Shrinks, blurs and saves [shot] off the main thread; [shot] is recycled. */
    fun save(context: Context, pkg: String, shot: Bitmap) {
        val app = context.applicationContext
        worker.execute {
            runCatching {
                val soft = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false) else shot
                // A real (Gaussian) blur at half the card's size: smooth when drawn at 1000 px, unlike the
                // stepped downscale it replaced, which looked blocky. Still recognisable at a glance.
                val probe = Bitmap.createScaledBitmap(soft, 64, 36, true)
                val blank = isMostlyBlack(probe)
                probe.recycle()
                if (blank) { if (soft !== shot) soft.recycle(); return@runCatching } // DRM video or a blank frame: keep the last one
                val b = dev.glasslauncher.glass.Blur.backdrop(soft, W, H, radius = RADIUS, saturation = 1.1f)
                if (soft !== shot) soft.recycle()
                val tmp = File(dir(app), "$pkg.tmp")
                tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                tmp.renameTo(file(app, pkg))
                b.recycle()
            }
            shot.recycle()
        }
    }

    // Decoded previews, kept while the file is unchanged: the switcher's cards are complete on its first
    // frame instead of decoding mid-animation on every open (~0.5 MB each, six at most).
    private val decoded = android.util.LruCache<String, Pair<Long, Bitmap>>(6)

    /** Under memory pressure: they decode again from disk on the next open. */
    fun trim() = decoded.evictAll()

    /** A preview already decoded and still current, for drawing without waiting. */
    fun cached(context: Context, pkg: String): Bitmap? {
        val f = file(context, pkg)
        return decoded.get(pkg)?.takeIf { it.first == f.lastModified() }?.second
    }

    fun load(context: Context, pkg: String): Bitmap? {
        val f = file(context, pkg).takeIf { it.exists() } ?: return null
        cached(context, pkg)?.let { return it }
        val b = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() ?: return null
        decoded.put(pkg, f.lastModified() to b)
        return b
    }

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
