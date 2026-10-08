package dev.glasslauncher.dream

import android.content.Context
import android.graphics.Color
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.glasslauncher.app
import dev.glasslauncher.data.ScreensaverConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date

/**
 * Full-screen Aerial player shared by the system screensaver and the in-app preview: shuffles
 * Apple's aerial videos, caches them on disk for offline replay, and shows the location and time.
 */
@OptIn(UnstableApi::class)
class AerialView(context: Context, private val cfg: ScreensaverConfig) : FrameLayout(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val surface = SurfaceView(context)
    private val fade = View(context).apply { setBackgroundColor(Color.BLACK) }
    // tvOS-style: a light clock without am/pm and a small caption, each with only a soft contact shadow.
    private val location = label(13f, alpha = 0.85f).apply { gravity = Gravity.START; letterSpacing = 0.01f }
    private val clock = label(26f, alpha = 0.95f).apply { letterSpacing = -0.01f }
    private var player: ExoPlayer? = null
    private var videos: List<AerialVideo> = emptyList()

    init {
        setBackgroundColor(Color.BLACK)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // tvOS text is flat (no shadows): the labels read on a soft darkening at the top and bottom edges.
        addView(android.view.View(context).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(90, 0, 0, 0), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(110, 0, 0, 0)),
            )
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(location, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(46), 0, 0, dp(36)) // text sits at 56/44 after the shadow padding
        })
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(32), dp(46), 0)
        })
        location.visibility = if (cfg.showLocation) View.VISIBLE else View.GONE
        clock.visibility = if (cfg.showClock) View.VISIBLE else View.GONE
    }

    fun start() {
        val app = context.app
        scope.launch {
            launch {
                while (true) {
                    clock.text = java.text.SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", java.util.Locale.getDefault()).format(Date())
                    delay(60_000L - System.currentTimeMillis() % 60_000L + 50)
                }
            }
            videos = AerialCatalog(context, app.http).videos().shuffled()
            if (videos.isEmpty()) {
                location.text = "Connect to the internet to download Aerial videos"
                location.visibility = View.VISIBLE
                return@launch
            }
            val exo = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory(context)))
                .build()
            player = exo
            exo.setVideoSurfaceView(surface)
            exo.volume = 0f
            exo.repeatMode = Player.REPEAT_MODE_ALL
            exo.setMediaItems(videos.map { MediaItem.Builder().setUri(it.url(cfg.quality)).setMediaId(it.id).build() })
            exo.addListener(object : Player.Listener {
                // The next clip's first frame is on screen (still under black): fade it in.
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    fadingOut = false
                    updateLabel()
                    // Not every decoder reports a first frame per item: fade in anyway shortly after.
                    postDelayed({ if (fade.alpha > 0.99f) fadeIn() }, 500)
                }
                override fun onRenderedFirstFrame() { fadeIn() }
            })
            exo.prepare()
            exo.play()
            updateLabel()
            // Clips used to hard-cut to black when the next one had already started. Now the current one
            // dims to black over its last 1.4 s, the switch happens in the dark, and the next fades up from
            // its first rendered frame. The clip's end is watched four times a second.
            launch {
                while (true) {
                    delay(250)
                    val p = player ?: break
                    val left = p.duration - p.currentPosition
                    if (!fadingOut && p.duration > 0 && left in 1..FADE_OUT_MS) {
                        fadingOut = true
                        fade.animate().cancel()
                        fade.animate().alpha(1f).setDuration(left.coerceAtMost(FADE_OUT_MS)).setStartDelay(0).start()
                    }
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
        player?.release()
        player = null
    }

    private var fadingOut = false

    private fun updateLabel() {
        val id = player?.currentMediaItem?.mediaId
        location.text = videos.firstOrNull { it.id == id }?.label.orEmpty()
    }

    private fun fadeIn() {
        fade.animate().cancel()
        fade.alpha = 1f
        fade.animate().alpha(0f).setDuration(1400).setStartDelay(150).start()
    }

    private fun label(size: Float, alpha: Float) = TextView(context).apply {
        setTextColor(Color.argb((alpha * 255).toInt(), 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setPadding(dp(10), dp(4), dp(10), dp(8))
        typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, dev.glasslauncher.R.font.inter_medium) }.getOrNull()
            ?: android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val FADE_OUT_MS = 1_400L
        private var cache: SimpleCache? = null

        @Synchronized
        private fun cacheFactory(context: Context): CacheDataSource.Factory {
            val c = cache ?: SimpleCache(
                File(context.cacheDir, "aerials"),
                LeastRecentlyUsedCacheEvictor(600L * 1024 * 1024),
                StandaloneDatabaseProvider(context),
            ).also { cache = it }
            return CacheDataSource.Factory()
                .setCache(c)
                .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
    }
}
