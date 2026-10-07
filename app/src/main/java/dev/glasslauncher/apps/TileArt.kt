package dev.glasslauncher.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** What a tile should be drawn from; part of the cache key so changes re-render. */
data class TileSpec(val app: AppEntry, val customIcon: String?, val iconPack: String?)

/**
 * Produces a full-bleed 16:9 tile for every app: a custom image, the TV banner, or a generated
 * tile so phone-style square icons never look out of place.
 */
class TileArt(context: Context, private val iconPacks: IconPacks) {

    private val res = context.resources
    private val pm: PackageManager = context.packageManager
    private val cache = object : LruCache<TileSpec, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: TileSpec, value: ImageBitmap) = value.width * value.height * 4
    }

    fun peek(spec: TileSpec): ImageBitmap? = cache.get(spec)

    suspend fun load(spec: TileSpec): ImageBitmap {
        cache.get(spec)?.let { return it }
        val art = withContext(Dispatchers.Default) {
            // GPU-only copy: a software tile would be held twice (native heap plus its texture).
            val soft = render(spec)
            (soft.copy(Bitmap.Config.HARDWARE, false)?.also { soft.recycle() } ?: soft).asImageBitmap()
        }
        cache.put(spec, art)
        return art
    }

    /** Icon on a coloured backing, used for folder previews and menus. */
    fun icon(app: AppEntry): Drawable =
        runCatching { pm.getActivityIcon(app.component) }.getOrNull() ?: pm.defaultActivityIcon

    private fun render(spec: TileSpec): Bitmap {
        val app = spec.app
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        spec.customIcon?.let { path ->
            val custom = decodeFile(path)
            if (custom != null) {
                val drawable = BitmapDrawable(res, custom)
                if (custom.width >= custom.height * 1.4f) drawCover(canvas, drawable) else drawGenerated(canvas, drawable)
                return bitmap
            }
        }
        spec.iconPack?.let { pack ->
            iconPacks.iconFor(pack, app.component)?.let { drawGenerated(canvas, it); return bitmap }
        }
        val banner = runCatching { pm.getActivityBanner(app.component) }.getOrNull()
            ?: runCatching { pm.getApplicationBanner(app.packageName) }.getOrNull()
        if (banner != null) {
            drawCover(canvas, banner)
            return bitmap
        }
        val icon = icon(app)
        if (icon is AdaptiveIconDrawable && icon.background != null && icon.foreground != null) {
            drawAdaptive(canvas, icon)
        } else {
            drawGenerated(canvas, icon)
        }
        return bitmap
    }

    private fun decodeFile(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WIDTH && bounds.outHeight / (sample * 2) >= HEIGHT) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
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
        icon.foreground.setBounds(left, top, left + size, top + size)
        icon.foreground.draw(canvas)
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
        val left = (WIDTH - size) / 2f
        val top = (HEIGHT - size) / 2f
        val rect = RectF(left, top, left + size, top + size)
        val radius = size * 0.22f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 0, 0, 0)
            setShadowLayer(16f, 0f, 5f, Color.argb(90, 0, 0, 0))
        }
        canvas.drawRoundRect(rect, radius, radius, shadow)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        icon.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        icon.draw(canvas)
        canvas.restore()
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
        const val WIDTH = 336
        const val HEIGHT = 189
    }
}
