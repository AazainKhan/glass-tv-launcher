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
    private val location = label(20f).apply { gravity = Gravity.START }
    private val clock = label(34f)
    private var player: ExoPlayer? = null
    private var videos: List<AerialVideo> = emptyList()

    init {
        setBackgroundColor(Color.BLACK)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
                    clock.text = DateFormat.getTimeFormat(context).format(Date())
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
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = showCurrent()
                override fun onRenderedFirstFrame() {
                    fade.animate().alpha(0f).setDuration(1200).start()
                }
            })
            exo.prepare()
            exo.play()
            showCurrent()
        }
    }

    fun stop() {
        scope.cancel()
        player?.release()
        player = null
    }

    private fun showCurrent() {
        val id = player?.currentMediaItem?.mediaId
        location.text = videos.firstOrNull { it.id == id }?.label.orEmpty()
        fade.alpha = 1f
        fade.animate().alpha(0f).setDuration(1200).setStartDelay(200).start()
    }

    private fun label(size: Float) = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setShadowLayer(12f, 0f, 2f, Color.argb(160, 0, 0, 0))
        // TextView measures the glyphs only; leave room so the shadow isn't cut off at the sides.
        setPadding(dp(10), dp(4), dp(10), dp(8))
        typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, dev.glasslauncher.R.font.inter_semibold) }.getOrNull()
            ?: android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
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
