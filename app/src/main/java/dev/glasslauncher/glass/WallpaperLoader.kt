package dev.glasslauncher.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import coil3.request.allowHardware
import coil3.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.glasslauncher.data.Wallpaper
import dev.glasslauncher.data.WallpaperKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import kotlin.math.max

class Backdrop(
    val sharp: ImageBitmap,
    /** Blurred wallpaper for full-screen drawing. */
    val blurred: ImageBitmap,
    /**
     * Opaque wallpaper copies from sharp to fully blurred. Animating the blur steps through these, so
     * every frame is a single opaque full-screen draw instead of a two-image cross-fade.
     */
    val ladder: List<ImageBitmap>,
    /** Same blur as a software bitmap, for the glass BitmapShader. */
    val blurredSoftware: ImageBitmap,
    /**
     * Lightly blurred software copy (scrim included) for clear glass such as the dock tray: the art
     * stays readable through it, as on tvOS 27, instead of turning into a frosted wash.
     */
    val clearSoftware: ImageBitmap,
    /** [clearSoftware] in the text-safe range (Blur.legible): clear glass that carries text (Control Center). */
    val clearLegible: ImageBitmap = clearSoftware,
    /** Baked for light appearance: the blurred copies are washed milky so dark text reads on them. */
    val isLight: Boolean,
    /** Luminance of the art (as shown, scrims included) on a [LUMA_COLS]×[LUMA_ROWS] grid. */
    private val luma: FloatArray = FloatArray(0),
) {
    /**
     * Whether the art behind a region (fractions of the screen) is light, so text placed straight on it
     * (not on glass) should be dark. Uses the region's average (dark text over mostly dark art, the
     * brightest-cell rule, read worse). White text is flat (tvOS 27, no shadow), so mid-bright art counts.
     */
    fun artLight(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        if (luma.isEmpty()) return isLight
        var sum = 0f; var n = 0
        val c0 = (left * LUMA_COLS).toInt().coerceIn(0, LUMA_COLS - 1); val c1 = (right * LUMA_COLS).toInt().coerceIn(c0, LUMA_COLS - 1)
        val r0 = (top * LUMA_ROWS).toInt().coerceIn(0, LUMA_ROWS - 1); val r1 = (bottom * LUMA_ROWS).toInt().coerceIn(r0, LUMA_ROWS - 1)
        for (r in r0..r1) for (c in c0..c1) { sum += luma[r * LUMA_COLS + c]; n++ }
        return sum / n > 0.55f
    }

    companion object {
        const val LUMA_COLS = 32
        const val LUMA_ROWS = 18
    }
}

data class Preset(val id: String, val name: String, val light: Boolean, val base: Int, val blobs: List<Blob>)
data class Blob(val color: Int, val x: Float, val y: Float, val r: Float)

class WallpaperLoader(private val context: Context, private val http: OkHttpClient) {

    private val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }
    // RenderScript setup takes tens of ms, so it happens on the first bake (a background thread).
    private val blurReady by lazy { Blur.init(context) }

    suspend fun load(wallpaper: Wallpaper, light: Boolean): Backdrop = withContext(Dispatchers.Default) {
        val source = when (wallpaper.kind) {
            WallpaperKind.Preset -> renderPreset(presets.firstOrNull { it.id == wallpaper.value } ?: presets.first())
            WallpaperKind.File -> decode(File(wallpaper.value)) ?: renderPreset(presets.first())
            WallpaperKind.Url -> decode(cachedUrlFile(wallpaper.value)) ?: renderPreset(presets.first())
        }
        bake(source, Scene.Wallpaper, light) { true }
    }

    /** Full-bleed top-shelf art (or a video frame) as the Home backdrop. */
    suspend fun fromImage(image: Bitmap, scene: Scene = Scene.Hero, background: Boolean = false, light: Boolean = false): Backdrop =
        withContext(if (background) BakeDispatcher else Dispatchers.Default) {
            val job = coroutineContext[kotlinx.coroutines.Job]
            bake(cropToScreen(image, recycleSource = false), scene, light) { job?.isActive != false }
        }

    /**
     * A cheap backdrop for an app's own Top Shelf hero: from a small, already soft image (the app's blurred
     * screenshot, or a wash of its banner), darkened and baked at 480×270 rather than 1080p. The sharp
     * layer is drawn stretched, which is invisible on a blurred picture, so it costs ~5 MB, not ~14.
     */
    suspend fun fromAppArt(image: Bitmap, light: Boolean = false, darken: Boolean = true): Backdrop = withContext(BakeDispatcher) {
        val small = Bitmap.createBitmap(APP_HERO_W, APP_HERO_H, Bitmap.Config.ARGB_8888)
        val scale = max(APP_HERO_W / image.width.toFloat(), APP_HERO_H / image.height.toFloat())
        val w = (APP_HERO_W / scale).toInt(); val h = (APP_HERO_H / scale).toInt()
        val x = (image.width - w) / 2; val y = (image.height - h) / 2
        Canvas(small).apply {
            drawBitmap(image, android.graphics.Rect(x, y, x + w, y + h), android.graphics.Rect(0, 0, APP_HERO_W, APP_HERO_H), Paint(Paint.FILTER_BITMAP_FLAG))
            if (darken) drawColor(Color.argb(90, 0, 0, 0))
        }
        val job = coroutineContext[kotlinx.coroutines.Job]
        bake(small, Scene.Hero, light) { job?.isActive != false }
    }

    /** Starts the expensive one-time setup (RenderScript, the image loader) before the first bake needs it. */
    fun prewarm() {
        blurReady
        coil3.SingletonImageLoader.get(context)
    }

    enum class Scene { Wallpaper, Hero }

    /** Downloads (or reuses Coil's cache for) a featured image and bakes it as the Home backdrop. */
    suspend fun fromUrl(url: String, background: Boolean = false, light: Boolean = false): Backdrop? {
        val request = coil3.request.ImageRequest.Builder(context)
            .data(url)
            .size(SHARP_W, SHARP_H)
            .allowHardware(false)
            // Baked into the backdrop right away; keeping the 3.6 MB source in Coil's memory cache only costs PSS.
            .memoryCachePolicy(coil3.request.CachePolicy.DISABLED)
            .build()
        val result = coil3.SingletonImageLoader.get(context).execute(request)
        val image = (result as? coil3.request.SuccessResult)?.image ?: return null
        val bitmap = image.toBitmap()
        return fromImage(bitmap, background = background, light = light).also { bitmap.recycle() }
    }

    /**
     * Bakes everything Home draws from one source image: the sharp screen image, a ladder of
     * progressively blurred opaque copies for the scroll blur, and the small blurred texture the glass
     * surfaces sample (which is what tints glass from the content behind it).
     */
    /** [alive] is checked between steps, so a superseded bake (fast browsing, startup) stops early. */
    private fun bake(source: Bitmap, scene: Scene, light: Boolean, alive: () -> Boolean): Backdrop {
        fun check() { if (!alive()) { throw kotlinx.coroutines.CancellationException("bake superseded") } }
        blurReady
        // Every blurred copy comes from one half-size intermediate: cheaper than re-sampling the 1080p
        // source for each, and a cleaner downscale for the small ones.
        val mid = Blur.backdrop(source, SHARP_W / 2, SHARP_H / 2, radius = 0, saturation = 1f)
        check()
        val blurred = Blur.backdrop(mid, BLUR_W, BLUR_H, radius = 5)
        // Appearance changes the blurred copies, not the art: in light appearance they wash towards milky
        // white (more for dark art), in dark towards charcoal (only light art needs it), as tvOS's grid does.
        // Washes grow with each rung, so the scroll blur also fades into the appearance.
        val luminance = Blur.luminance(blurred)
        val wash = if (light) (0.62f - luminance * 0.45f).coerceIn(0.3f, 0.6f) else ((luminance - 0.35f) * 0.9f).coerceIn(0f, 0.4f)
        // Ten rungs from sharp to fully blurred, so the blur ramps smoothly as Home scrolls to the grid
        // (six rungs read as visible jumps on the long tvOS scroll curve).
        val rungs = listOf(
            Triple(800, 450, 1), Triple(640, 360, 1), Triple(544, 306, 2), Triple(448, 252, 2),
            Triple(384, 216, 3), Triple(320, 180, 3), Triple(272, 153, 4), Triple(240, 135, 4),
        )
        val steps = rungs.mapIndexed { i, (w, h, r) ->
            val t = (i + 1f) / (rungs.size + 1)
            Blur.backdrop(mid, w, h, radius = r, saturation = 1f + 0.35f * t).also { bakeScrim(it, light, scene, wash * t); Blur.legible(it, light, t * t); it.setHasAlpha(false) }
        }
        check()
        val clear = Blur.backdrop(mid, CLEAR_W, CLEAR_H, radius = 2, saturation = 1.15f).also { bakeScrim(it, light, scene, 0f); it.setHasAlpha(false) }
        val clearText = clear.copy(Bitmap.Config.ARGB_8888, true).also { Blur.legible(it, light) }
        mid.recycle()
        // Scrims are baked in and the bitmaps are marked opaque, so drawing the backdrop is a single
        // non-blended full-screen pass. TV-stick GPUs only afford about two full-screen passes per frame.
        bakeScrim(source, light, scene, 0f)
        val luma = Blur.lumaGrid(source, Backdrop.LUMA_COLS, Backdrop.LUMA_ROWS)
        // The grid and every glass surface carry text, so both get the legibility range (see Blur.legible).
        val blurredScreen = blurred.copy(Bitmap.Config.ARGB_8888, true).also { bakeScrim(it, light, scene, wash); Blur.legible(it, light) }
        // The glass texture gets the appearance wash but not the scrims, so panels keep their own tint.
        if (wash > 0f) Canvas(blurred).drawColor(washColor(light, wash))
        Blur.legible(blurred, light)
        source.setHasAlpha(false)
        blurredScreen.setHasAlpha(false)
        blurred.setHasAlpha(false)
        val sharp = source.copy(Bitmap.Config.HARDWARE, false)?.also { source.recycle() } ?: source
        val blurredGpu = blurredScreen.copy(Bitmap.Config.HARDWARE, false)?.also { blurredScreen.recycle() } ?: blurredScreen
        val ladder = listOf(sharp) + steps.map { b -> b.copy(Bitmap.Config.HARDWARE, false)?.also { b.recycle() } ?: b } + blurredGpu
        // The glass samples these through a BitmapShader every frame: as software bitmaps the GPU re-uploads
        // them each frame of a scroll (measured: glass off cut janky frames from 39% to 15%), so they live
        // on the GPU too. The names say "Software" for history; they're only read by shaders.
        val glassBlur = blurred.copy(Bitmap.Config.HARDWARE, false)?.also { blurred.recycle() } ?: blurred
        val glassClear = clear.copy(Bitmap.Config.HARDWARE, false)?.also { clear.recycle() } ?: clear
        val glassClearText = clearText.copy(Bitmap.Config.HARDWARE, false)?.also { clearText.recycle() } ?: clearText
        return Backdrop(sharp.asImageBitmap(), blurredGpu.asImageBitmap(), ladder.map { it.asImageBitmap() }, glassBlur.asImageBitmap(), glassClear.asImageBitmap(), glassClearText.asImageBitmap(), light, luma)
    }

    /** [wash] (0..1) is the appearance wash: white in light appearance, black in dark. */
    private fun bakeScrim(bitmap: Bitmap, light: Boolean, scene: Scene, wash: Float) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val canvas = Canvas(bitmap)
        // A light wash replaces the darkening scrims as it builds up.
        val scrim = if (light) (1f - wash / 0.4f).coerceIn(0f, 1f) else 1f
        if (scene == Scene.Hero) {
            // Top-shelf art darkens under the tray and behind the title, as on tvOS.
            canvas.drawRect(0f, 0f, w, h, Paint().apply {
                shader = android.graphics.LinearGradient(0f, h * 0.45f, 0f, h, Color.TRANSPARENT, Color.argb((120 * scrim).toInt(), 0, 0, 0), Shader.TileMode.CLAMP)
            })
            canvas.drawRect(0f, 0f, w, h, Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, w * 0.5f, 0f, Color.argb((115 * scrim).toInt(), 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
        } else {
            val end = if (light) Color.argb(38, 255, 255, 255) else Color.argb(102, 0, 0, 0)
            canvas.drawRect(0f, 0f, w, h, Paint().apply {
                shader = android.graphics.LinearGradient(0f, h * 0.45f, 0f, h, Color.TRANSPARENT, end, Shader.TileMode.CLAMP)
            })
        }
        if (wash > 0f) canvas.drawColor(washColor(light, wash))
    }

    private fun washColor(light: Boolean, wash: Float): Int {
        val c = if (light) 255 else 0
        return Color.argb((wash * 255).toInt(), c, c, c)
    }

    /** Downloads the image so it survives offline boots; returns false if it isn't a decodable image. */
    suspend fun downloadUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        val target = cachedUrlFile(url)
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val tmp = File(dir, target.name + ".tmp")
                tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                val ok = isImage(tmp)
                if (ok) tmp.renameTo(target) else tmp.delete()
                ok
            }
        }.getOrDefault(false)
    }

    /** Downloads an image to app storage under [name]; returns its path or null if it isn't an image. */
    suspend fun downloadImage(url: String, name: String): String? =
        if (downloadUrl(url)) importFile(cachedUrlFile(url), name) else null

    /** Copies a user-picked image into app storage and returns its path. */
    suspend fun importImage(uri: Uri, name: String): String? = withContext(Dispatchers.IO) {
        val target = File(dir, name)
        runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
            if (isImage(target)) target.path else { target.delete(); null }
        }.getOrNull()
    }

    fun importFile(source: File, name: String): String? {
        val target = File(dir, name)
        return runCatching { source.copyTo(target, overwrite = true); if (isImage(target)) target.path else null }.getOrNull()
    }

    private fun isImage(file: File): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    private fun cachedUrlFile(url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "url-$hash")
    }

    private fun decode(file: File): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SHARP_W && bounds.outHeight / (sample * 2) >= SHARP_H) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return cropToScreen(decoded)
    }

    private fun cropToScreen(src: Bitmap, recycleSource: Boolean = true): Bitmap {
        val out = Bitmap.createBitmap(SHARP_W, SHARP_H, Bitmap.Config.ARGB_8888)
        val scale = max(SHARP_W / src.width.toFloat(), SHARP_H / src.height.toFloat())
        val w = (SHARP_W / scale).toInt()
        val h = (SHARP_H / scale).toInt()
        val left = (src.width - w) / 2
        val top = (src.height - h) / 2
        Canvas(out).drawBitmap(src, Rect(left, top, left + w, top + h), Rect(0, 0, SHARP_W, SHARP_H), Paint(Paint.FILTER_BITMAP_FLAG))
        if (recycleSource && src != out) src.recycle()
        return out
    }

    fun renderPreset(preset: Preset, width: Int = SHARP_W, height: Int = SHARP_H): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(preset.base)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val scale = max(width, height).toFloat()
        for (blob in preset.blobs) {
            val cx = blob.x * width
            val cy = blob.y * height
            val r = blob.r * scale
            paint.shader = RadialGradient(cx, cy, r, intArrayOf(blob.color, Color.argb(0, Color.red(blob.color), Color.green(blob.color), Color.blue(blob.color))), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
        return bitmap
    }

    companion object {
        // Native 1080p: hero art is shown 1:1, and a 720p bake looked soft next to the tiles.
        /** One low-priority thread for slideshow bakes: they must never compete with drawing or input. */
        val BakeDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "glass-bake")
        }.asCoroutineDispatcher()

        const val SHARP_W = 1920
        const val SHARP_H = 1080
        const val BLUR_W = 192
        const val BLUR_H = 108
        const val CLEAR_W = 480
        const val APP_HERO_W = 480
        const val APP_HERO_H = 270
        const val CLEAR_H = 270

        val presets = listOf(
            Preset("aurora", "Aurora", false, 0xFF0B1026.toInt(), listOf(
                Blob(0xCC3A2E8F.toInt(), 0.18f, 0.22f, 0.55f), Blob(0xB30E7C86.toInt(), 0.88f, 0.28f, 0.5f),
                Blob(0xA67B2F7F.toInt(), 0.62f, 0.95f, 0.55f), Blob(0x991D4ED8.toInt(), 0.05f, 0.95f, 0.45f),
            )),
            Preset("ember", "Ember", false, 0xFF140A0E.toInt(), listOf(
                Blob(0xCCB4232C.toInt(), 0.15f, 0.85f, 0.55f), Blob(0xB3E07A2F.toInt(), 0.8f, 0.15f, 0.45f),
                Blob(0x995B1A7A.toInt(), 0.55f, 0.55f, 0.5f),
            )),
            Preset("abyss", "Abyss", false, 0xFF030712.toInt(), listOf(
                Blob(0xB3075985.toInt(), 0.25f, 0.3f, 0.5f), Blob(0x99115E59.toInt(), 0.85f, 0.85f, 0.55f),
                Blob(0x661E3A8A.toInt(), 0.7f, 0.1f, 0.4f),
            )),
            Preset("graphite", "Graphite", false, 0xFF101114.toInt(), listOf(
                Blob(0x803F4552.toInt(), 0.2f, 0.2f, 0.6f), Blob(0x66272B33.toInt(), 0.85f, 0.9f, 0.55f),
            )),
            Preset("dawn", "Dawn", true, 0xFFF4EEF8.toInt(), listOf(
                Blob(0xE6FFD3C2.toInt(), 0.15f, 0.2f, 0.55f), Blob(0xD9D6CCFF.toInt(), 0.85f, 0.3f, 0.5f),
                Blob(0xCCC4E6FF.toInt(), 0.55f, 0.95f, 0.55f),
            )),
            Preset("mist", "Mist", true, 0xFFEEF2F5.toInt(), listOf(
                Blob(0xCCC8F0E0.toInt(), 0.2f, 0.85f, 0.55f), Blob(0xCCCFE3FF.toInt(), 0.8f, 0.2f, 0.5f),
                Blob(0x99FFFFFF.toInt(), 0.5f, 0.45f, 0.4f),
            )),
        )
    }
}
