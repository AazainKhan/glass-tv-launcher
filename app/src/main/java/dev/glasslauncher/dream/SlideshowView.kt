package dev.glasslauncher.dream

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.glasslauncher.data.ScreensaverConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The photo screensaver: the chosen album's photos, shuffled, cross-fading one into the next, each with a
 * slow zoom and drift (Ken Burns) when enabled. One decoded photo at a time (two during a fade), at most
 * 1080p, so a large library costs nothing extra.
 */
class SlideshowView(context: Context, private val cfg: ScreensaverConfig) : FrameLayout(context), Saver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val views = Array(2) { ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f } }
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private var front = 0
    private val message = TextView(context).apply {
        setTextColor(Color.argb(220, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
    }

    init {
        setBackgroundColor(Color.BLACK)
        views.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        addView(message, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    override fun start() {
        scope.launch {
            val photos = photos(context, cfg.album).shuffled()
            if (photos.isEmpty()) {
                message.text = "No photos found. Choose an album in Settings › Screen Saver › Slideshow."
                return@launch
            }
            val hold = cfg.photoSeconds.coerceIn(3, 60) * 1000L
            var i = 0
            while (true) {
                val bitmap = decode(context, photos[i % photos.size])
                i++
                if (bitmap == null) { if (i > photos.size && bitmaps.all { it == null }) break else continue }
                show(bitmap, hold)
                delay(hold)
            }
        }
    }

    private fun show(bitmap: Bitmap, hold: Long) {
        val back = 1 - front
        val view = views[back]
        view.animate().cancel()
        view.setImageBitmap(bitmap)
        bitmaps[back] = bitmap
        view.alpha = 0f
        view.scaleX = 1f; view.scaleY = 1f; view.translationX = 0f; view.translationY = 0f
        view.bringToFront()
        android.animation.ObjectAnimator.ofFloat(view, ALPHA, 0f, 1f).apply {
            duration = FADE_MS
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // The old photo is fully covered: drop it.
                    val old = front
                    front = back
                    if (old != back) {
                        views[old].animate().cancel()
                        views[old].setImageDrawable(null)
                        bitmaps[old]?.recycle()
                        bitmaps[old] = null
                    }
                }
            })
        }.start()
        if (cfg.kenBurns) {
            // A slow push in, drifting toward a random corner, lasting through the next cross-fade.
            val drift = width * 0.03f
            view.animate().scaleX(1.12f).scaleY(1.12f)
                .translationX(if (Math.random() < 0.5) -drift else drift)
                .translationY(if (Math.random() < 0.5) -drift / 2 else drift / 2)
                .setDuration(hold + FADE_MS * 2)
                .setInterpolator(android.view.animation.LinearInterpolator())
                .start()
        }
    }

    override fun stop() {
        scope.cancel()
        views.forEach { it.animate().cancel(); it.setImageDrawable(null) }
        bitmaps.forEachIndexed { i, b -> b?.recycle(); bitmaps[i] = null }
    }

    companion object {
        private const val FADE_MS = 1_200L

        /** Photo albums on the device (MediaStore buckets), with how many photos each holds. */
        suspend fun albums(context: Context): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
            val counts = LinkedHashMap<String, Int>()
            runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME), null, null,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
                )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { counts[it] = (counts[it] ?: 0) + 1 } }
            }
            counts.toList()
        }

        suspend fun photos(context: Context, album: String?): List<Uri> = withContext(Dispatchers.IO) {
            val out = ArrayList<Uri>()
            runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    album?.let { "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?" },
                    album?.let { arrayOf(it) },
                    null,
                )?.use { c -> while (c.moveToNext() && out.size < 2000) out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) }
            }
            out
        }

        /** Decodes at no more than 1920×1080 (a power-of-two subsample), off the main thread. */
        private suspend fun decode(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1920 || bounds.outHeight / (sample * 2) >= 1080) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }.getOrNull()
        }
    }
}

/** A full-screen screensaver view (Aerials or the photo slideshow). */
interface Saver {
    fun start()
    fun stop()
}
