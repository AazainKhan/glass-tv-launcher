package dev.glasslauncher.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Produces a full-bleed 16:9 tile for every app: the TV banner when one exists,
 * otherwise a generated tile so phone-style square icons never look out of place.
 */
class TileArt(context: Context) {

    private val pm: PackageManager = context.packageManager
    private val cache = LruCache<String, ImageBitmap>(64)

    suspend fun load(app: AppEntry): ImageBitmap {
        cache.get(app.packageName)?.let { return it }
        val art = withContext(Dispatchers.Default) { render(app).asImageBitmap() }
        cache.put(app.packageName, art)
        return art
    }

    fun invalidate() = cache.evictAll()

    private fun render(app: AppEntry): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val banner = runCatching { pm.getActivityBanner(app.component) }.getOrNull()
            ?: runCatching { pm.getApplicationBanner(app.packageName) }.getOrNull()
        if (banner != null) {
            drawCover(canvas, banner)
            return bitmap
        }
        val icon = runCatching { pm.getActivityIcon(app.component) }.getOrNull()
            ?: pm.defaultActivityIcon
        if (icon is AdaptiveIconDrawable && icon.background != null) {
            drawAdaptive(canvas, icon)
        } else {
            drawGenerated(canvas, icon)
        }
        return bitmap
    }

    private fun drawCover(canvas: Canvas, d: Drawable) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: WIDTH
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: HEIGHT
        val scale = max(WIDTH / iw.toFloat(), HEIGHT / ih.toFloat())
        val w = (iw * scale).toInt()
        val h = (ih * scale).toInt()
        val left = (WIDTH - w) / 2
        val top = (HEIGHT - h) / 2
        d.setBounds(left, top, left + w, top + h)
        d.draw(canvas)
    }

    private fun drawAdaptive(canvas: Canvas, icon: AdaptiveIconDrawable) {
        // Stretch the background layer across the tile; keep the foreground's safe zone centred.
        icon.background.setBounds(-WIDTH / 4, -HEIGHT, WIDTH + WIDTH / 4, HEIGHT * 2)
        icon.background.draw(canvas)
        val size = (HEIGHT * 1.35f).toInt()
        val left = (WIDTH - size) / 2
        val top = (HEIGHT - size) / 2
        icon.foreground?.let {
            it.setBounds(left, top, left + size, top + size)
            it.draw(canvas)
        }
    }

    private fun drawGenerated(canvas: Canvas, icon: Drawable) {
        val base = dominantColor(icon)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
                shade(base, 1.15f), shade(base, 0.7f), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)

        val size = (HEIGHT * 0.58f).toInt()
        val left = (WIDTH - size) / 2
        val top = (HEIGHT - size) / 2
        val iconBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(iconBitmap).also { c ->
            icon.setBounds(0, 0, size, size)
            icon.draw(c)
        }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 0, 0, 0)
            setShadowLayer(18f, 0f, 6f, Color.argb(90, 0, 0, 0))
        }
        val radius = size * 0.22f
        canvas.drawRoundRect(RectF(left.toFloat(), top.toFloat(), (left + size).toFloat(), (top + size).toFloat()), radius, radius, shadow)
        val clip = android.graphics.Path().apply {
            addRoundRect(RectF(left.toFloat(), top.toFloat(), (left + size).toFloat(), (top + size).toFloat()), radius, radius, android.graphics.Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(iconBitmap, left.toFloat(), top.toFloat(), null)
        canvas.restore()
        iconBitmap.recycle()
    }

    private fun dominantColor(icon: Drawable): Int {
        val s = 24
        val small = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, s, s)
        icon.draw(Canvas(small))
        var r = 0.0; var g = 0.0; var b = 0.0; var weight = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until s) for (x in 0 until s) {
            val c = small.getPixel(x, y)
            if (Color.alpha(c) < 200) continue
            Color.colorToHSV(c, hsv)
            if (hsv[2] < 0.12f || (hsv[1] < 0.08f && hsv[2] > 0.92f)) continue
            val w = 0.15 + hsv[1] * hsv[2]
            r += Color.red(c) * w; g += Color.green(c) * w; b += Color.blue(c) * w; weight += w
        }
        small.recycle()
        if (weight == 0.0) return Color.rgb(58, 64, 82)
        return Color.rgb((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt())
    }

    private fun shade(color: Int, factor: Float): Int = Color.rgb(
        min(255, (Color.red(color) * factor).toInt()),
        min(255, (Color.green(color) * factor).toInt()),
        min(255, (Color.blue(color) * factor).toInt()),
    )

    companion object {
        const val WIDTH = 400
        const val HEIGHT = 225
    }
}
