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

    /** Under memory pressure: keep the most recently drawn half (the rest re-render when scrolled to). */
    fun trim() = cache.trimToSize(cache.maxSize() / 2)

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
    fun icon(app: AppEntry): Drawable = hiResIcon(app)
        ?: runCatching { pm.getActivityIcon(app.component) }.getOrNull() ?: pm.defaultActivityIcon

    /** The launcher icon at xxxhdpi: the default density on a TV is low and looks soft when enlarged. */
    private fun hiResIcon(app: AppEntry): Drawable? = runCatching {
        val info = pm.getActivityInfo(app.component, 0)
        val iconRes = info.iconResource.takeIf { it != 0 } ?: info.applicationInfo.icon
        val res = pm.getResourcesForApplication(info.applicationInfo)
        res.getDrawableForDensity(iconRes, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)
    }.getOrNull()

    /** The TV banner at xxxhdpi when the app ships one, so a re-centred logo stays crisp. */
    private fun hiResBanner(app: AppEntry): Drawable? = runCatching {
        val info = pm.getActivityInfo(app.component, 0)
        val bannerRes = info.bannerResource.takeIf { it != 0 } ?: info.applicationInfo.banner
        if (bannerRes == 0) return@runCatching null
        pm.getResourcesForApplication(info.applicationInfo).getDrawableForDensity(bannerRes, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)
    }.getOrNull()

    private fun render(spec: TileSpec): Bitmap = renderArt(spec).also(::bakeBevel)

    /**
     * A faint top light and bottom shade baked into the art, so every tile reads as a slightly raised
     * surface (tvOS 27) without an extra blended pass per tile at draw time.
     */
    private fun bakeBevel(bitmap: Bitmap) {
        val h = bitmap.height.toFloat()
        Canvas(bitmap).drawRect(0f, 0f, bitmap.width.toFloat(), h, Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(Color.argb(30, 255, 255, 255), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(26, 0, 0, 0)),
                floatArrayOf(0f, 0.42f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
        })
    }

    private fun renderArt(spec: TileSpec): Bitmap {
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
        val banner = hiResBanner(app)
            ?: runCatching { pm.getActivityBanner(app.component) }.getOrNull()
            ?: runCatching { pm.getApplicationBanner(app.packageName) }.getOrNull()
        if (banner != null) {
            drawBanner(canvas, banner)
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

    /**
     * Banners are cover-cropped to 5:3 as before, unless that would leave the logo cut off, crowding
     * an edge, or visibly off-centre (some ship the logo hugging one side). Those, when the banner's
     * background is one solid colour, are redrawn with the logo centred at the same size, never larger.
     */
    private fun drawBanner(canvas: Canvas, d: Drawable) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: WIDTH
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: HEIGHT
        val scale = min(1f, 640f / iw)
        val bw = (iw * scale).toInt().coerceAtLeast(1)
        val bh = (ih * scale).toInt().coerceAtLeast(1)
        val src = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, bw, bh)
        d.draw(Canvas(src))
        val bg = edgeColor(src)
        val box = bg?.let { contentBounds(src, it) }
        src.recycle()
        if (bg == null || box == null) return drawCover(canvas, d)

        // Where the logo lands under a plain cover crop, in tile pixels.
        val k = max(WIDTH / bw.toFloat(), HEIGHT / bh.toFloat())
        val left = (box.left - bw / 2f) * k + WIDTH / 2f
        val right = (box.right - bw / 2f) * k + WIDTH / 2f
        val top = (box.top - bh / 2f) * k + HEIGHT / 2f
        val bottom = (box.bottom - bh / 2f) * k + HEIGHT / 2f
        val marginX = WIDTH * 0.07f
        val marginY = HEIGHT * 0.07f
        val centred = kotlin.math.abs((left + right) / 2f - WIDTH / 2f) <= WIDTH * 0.035f &&
            kotlin.math.abs((top + bottom) / 2f - HEIGHT / 2f) <= HEIGHT * 0.05f
        if (centred && left >= marginX && right <= WIDTH - marginX && top >= marginY && bottom <= HEIGHT - marginY) return drawCover(canvas, d)

        val fit = min(k, min(WIDTH * 0.8f / box.width(), HEIGHT * 0.62f / box.height()))
        canvas.drawColor(bg)
        // Draw the whole banner (its own background continues around the logo) shifted so the logo is centred.
        val dx = WIDTH / 2f - box.exactCenterX() * fit
        val dy = HEIGHT / 2f - box.exactCenterY() * fit
        val u = fit * bw / iw.toFloat()
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(u, u)
        d.setBounds(0, 0, iw, ih)
        d.draw(canvas)
        canvas.restore()
    }

    /** Bounding box of pixels that differ from [bg], with a little breathing room; null if empty. */
    private fun contentBounds(bitmap: Bitmap, bg: Int): android.graphics.Rect? {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        var left = w; var right = -1; var top = h; var bottom = -1
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val diff = kotlin.math.abs(Color.red(c) - Color.red(bg)) + kotlin.math.abs(Color.green(c) - Color.green(bg)) +
                    kotlin.math.abs(Color.blue(c) - Color.blue(bg)) + (255 - Color.alpha(c)) / 2
                if (diff > 60) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    bottom = y
                }
            }
        }
        if (right < left || bottom < top) return null
        val pad = (min(w, h) * 0.02f).toInt()
        return android.graphics.Rect((left - pad).coerceAtLeast(0), (top - pad).coerceAtLeast(0), (right + pad + 1).coerceAtMost(w), (bottom + pad + 1).coerceAtMost(h))
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

    /**
     * A full-width tile in one consistent style for apps that ship no TV banner: icons with a solid
     * edge colour are extended across the tile so the icon merges into it; anything busier sits on a
     * blurred, colour-matched wash of itself, like the flat logo tiles on tvOS.
     */
    private fun drawGenerated(canvas: Canvas, icon: Drawable) {
        val probe = Bitmap.createBitmap(PROBE, PROBE, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, PROBE, PROBE)
        icon.draw(Canvas(probe))
        val edge = edgeColor(probe)

        if (edge != null) {
            canvas.drawColor(edge)
            val size = (HEIGHT * 1.02f).toInt()
            val left = (WIDTH - size) / 2
            val top = (HEIGHT - size) / 2
            icon.setBounds(left, top, left + size, top + size)
            icon.draw(canvas)
            probe.recycle()
            return
        }

        val wash = Bitmap.createBitmap(48, 29, Bitmap.Config.ARGB_8888)
        Canvas(wash).apply {
            drawColor(dominantColor(probe))
            drawBitmap(probe, android.graphics.Rect(0, 0, PROBE, PROBE), android.graphics.RectF(-6f, -16f, 54f, 44f), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        dev.glasslauncher.glass.Blur.blurInPlace(wash, 6)
        canvas.drawBitmap(wash, null, RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.drawColor(Color.argb(40, 0, 0, 0))
        wash.recycle()
        probe.recycle()

        val size = (HEIGHT * 0.64f).toInt()
        val left = (WIDTH - size) / 2f
        val top = (HEIGHT - size) / 2f
        val rect = RectF(left, top, left + size, top + size)
        val radius = size * 0.23f
        // No backing plate or shadow: icons with transparent padding showed it as a dark grey frame.
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        icon.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        icon.draw(canvas)
        canvas.restore()
    }

    /** The icon's border colour when the outer ring is (nearly) one solid colour, else null. */
    private fun edgeColor(probe: Bitmap): Int? {
        val samples = ArrayList<Int>()
        val pw = probe.width
        val ph = probe.height
        val inset = min(pw, ph) / 12
        val step = (max(pw, ph) / 32).coerceAtLeast(3)
        for (i in inset until pw - inset step step) {
            listOf(probe.getPixel(i, inset), probe.getPixel(i, ph - 1 - inset)).filter { Color.alpha(it) > 230 }.forEach { samples += it }
        }
        for (i in inset until ph - inset step step) {
            listOf(probe.getPixel(inset, i), probe.getPixel(pw - 1 - inset, i)).filter { Color.alpha(it) > 230 }.forEach { samples += it }
        }
        if (samples.size < 40) return null
        val r = samples.map { Color.red(it) }.sorted()[samples.size / 2]
        val g = samples.map { Color.green(it) }.sorted()[samples.size / 2]
        val b = samples.map { Color.blue(it) }.sorted()[samples.size / 2]
        val close = samples.count { kotlin.math.abs(Color.red(it) - r) + kotlin.math.abs(Color.green(it) - g) + kotlin.math.abs(Color.blue(it) - b) < 36 }
        return if (close >= samples.size * 0.85f) Color.rgb(r, g, b) else null
    }

    private fun dominantColor(probe: Bitmap): Int {
        val s = 24
        val small = Bitmap.createScaledBitmap(probe, s, s, true)
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
        // 5:3, the tvOS app tile shape.
        const val WIDTH = 340
        const val HEIGHT = 204
        private const val PROBE = 96
    }
}
