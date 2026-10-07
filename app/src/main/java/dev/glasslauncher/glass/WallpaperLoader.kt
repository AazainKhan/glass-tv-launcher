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
import androidx.compose.ui.graphics.asImageBitmap
import dev.glasslauncher.data.Wallpaper
import dev.glasslauncher.data.WallpaperKind
import kotlinx.coroutines.Dispatchers
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
    /** True when the wallpaper is light enough that content should use dark text. */
    val isLight: Boolean,
)

data class Preset(val id: String, val name: String, val light: Boolean, val base: Int, val blobs: List<Blob>)
data class Blob(val color: Int, val x: Float, val y: Float, val r: Float)

class WallpaperLoader(private val context: Context, private val http: OkHttpClient) {

    private val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }

    suspend fun load(wallpaper: Wallpaper): Backdrop = withContext(Dispatchers.Default) {
        val source = when (wallpaper.kind) {
            WallpaperKind.Preset -> renderPreset(presets.firstOrNull { it.id == wallpaper.value } ?: presets.first())
            WallpaperKind.File -> decode(File(wallpaper.value)) ?: renderPreset(presets.first())
            WallpaperKind.Url -> decode(cachedUrlFile(wallpaper.value)) ?: renderPreset(presets.first())
        }
        val blurred = Blur.backdrop(source, BLUR_W, BLUR_H, radius = 5)
        val isLight = Blur.luminance(blurred) > 0.62f
        val steps = listOf(
            Triple(640, 360, 1), Triple(448, 252, 2), Triple(320, 180, 3), Triple(240, 135, 4),
        ).mapIndexed { i, (w, h, r) ->
            Blur.backdrop(source, w, h, radius = r, saturation = 1f + 0.35f * (i + 1) / 5f).also { bakeScrim(it, isLight); it.setHasAlpha(false) }
        }
        // The screen-bottom scrim is baked in and the bitmaps are marked opaque, so drawing the wallpaper
        // is a single non-blended full-screen pass. TV-stick GPUs only afford about two full-screen passes per frame.
        bakeScrim(source, isLight)
        val blurredScreen = blurred.copy(Bitmap.Config.ARGB_8888, true).also { bakeScrim(it, isLight) }
        source.setHasAlpha(false)
        blurredScreen.setHasAlpha(false)
        blurred.setHasAlpha(false)
        val sharp = source.copy(Bitmap.Config.HARDWARE, false)?.also { source.recycle() } ?: source
        val blurredGpu = blurredScreen.copy(Bitmap.Config.HARDWARE, false)?.also { blurredScreen.recycle() } ?: blurredScreen
        val ladder = listOf(sharp) + steps.map { b -> b.copy(Bitmap.Config.HARDWARE, false)?.also { b.recycle() } ?: b } + blurredGpu
        Backdrop(sharp.asImageBitmap(), blurredGpu.asImageBitmap(), ladder.map { it.asImageBitmap() }, blurred.asImageBitmap(), isLight)
    }

    private fun bakeScrim(bitmap: Bitmap, light: Boolean) {
        val h = bitmap.height.toFloat()
        val end = if (light) Color.argb(38, 255, 255, 255) else Color.argb(102, 0, 0, 0)
        Canvas(bitmap).drawRect(0f, 0f, bitmap.width.toFloat(), h, Paint().apply {
            shader = android.graphics.LinearGradient(0f, h * 0.45f, 0f, h, Color.TRANSPARENT, end, Shader.TileMode.CLAMP)
        })
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

    private fun cropToScreen(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(SHARP_W, SHARP_H, Bitmap.Config.ARGB_8888)
        val scale = max(SHARP_W / src.width.toFloat(), SHARP_H / src.height.toFloat())
        val w = (SHARP_W / scale).toInt()
        val h = (SHARP_H / scale).toInt()
        val left = (src.width - w) / 2
        val top = (src.height - h) / 2
        Canvas(out).drawBitmap(src, Rect(left, top, left + w, top + h), Rect(0, 0, SHARP_W, SHARP_H), Paint(Paint.FILTER_BITMAP_FLAG))
        if (src != out) src.recycle()
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
        const val SHARP_W = 1280
        const val SHARP_H = 720
        const val BLUR_W = 192
        const val BLUR_H = 108

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
