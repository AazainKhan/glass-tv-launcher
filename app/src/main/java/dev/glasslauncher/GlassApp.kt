package dev.glasslauncher

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.glasslauncher.apps.AppRepository
import dev.glasslauncher.apps.IconPacks
import dev.glasslauncher.apps.TileArt
import dev.glasslauncher.data.ConfigStore
import dev.glasslauncher.featured.FeaturedRepository
import dev.glasslauncher.glass.WallpaperLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class GlassApp : Application(), SingletonImageLoader.Factory {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Home is hidden (an app opened) or memory is tight: give back what rebuilds quickly on the way back.
        // The switcher's previews and the decoded logos reload from disk; the cached app heroes re-bake in
        // a fraction of a second. (The system trims the renderer's textures itself at these levels.)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            dev.glasslauncher.system.AppPreviews.trim()
            dev.glasslauncher.home.HeroCache.app.trim()
            dev.glasslauncher.home.HeroCache.cover.trim()
            tileArt.trim()
            SingletonImageLoader.get(this).memoryCache?.clear()
        }
    }

    override fun onCreate() {
        super.onCreate()
        dev.glasslauncher.ui.NetworkEpoch.register(this)
        // RenderScript and the image loader take ~150 ms to set up cold; do it while the activity starts,
        // not on the first bake's critical path.
        chore(Dispatchers.Default) { wallpapers.prewarm() }
        // The tile shadows bake once (a few ms to tens of ms on the stick): off the UI thread, before the first tile draws.
        chore(Dispatchers.Default) { dev.glasslauncher.ui.TileShadow.focus; dev.glasslauncher.ui.TileShadow.contact }
        // Old tiles on disk (changed apps, removed packs) are cleared away once per start, off the main thread.
        chore(Dispatchers.IO) { tileArt.trimDisk() }
        chore(Dispatchers.IO) { dev.glasslauncher.system.HomeSetup.ensureRemoteKeys(this@GlassApp) }
        chore(Dispatchers.IO) { dev.glasslauncher.system.RootFeatures.reapplyAtStart(this@GlassApp) }
        // A store visit cut short (Glass restarted): put Glass back as Home.
        chore(Dispatchers.IO) { dev.glasslauncher.system.AmazonStore.close(this@GlassApp, bringHome = false) }
    }

    /**
     * A best-effort start-up task, fire and forget. It must not take the process down if it fails, and in tests the
     * app is torn down while these may still be running (SharedPreferences gone): the exception went uncaught on
     * a pool thread and kotlinx-coroutines-test blamed whichever test ran next ("uncaught exceptions before the
     * test started": the flaky CoverFlowTest, FeaturedRowShadowTest, MoveHintsTest, CardTitleTest, P70).
     */
    private fun chore(dispatcher: kotlin.coroutines.CoroutineContext, block: suspend () -> Unit) {
        scope.launch(dispatcher) {
            try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { android.util.Log.w("GlassApp", "start-up task failed", e) }
        }
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val imageHttp: OkHttpClient by lazy {
        http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    }
    val config by lazy { ConfigStore(this, scope) }
    val apps by lazy { AppRepository(this) }
    val iconPacks by lazy { IconPacks(this) }
    val tileArt by lazy { TileArt(this, iconPacks) }
    val wallpapers by lazy { WallpaperLoader(this, http) }
    val featured by lazy { FeaturedRepository(this, http) }
    val weather by lazy { dev.glasslauncher.widgets.WeatherRepository(http, scope) }
    val nowPlaying by lazy { dev.glasslauncher.widgets.NowPlayingRepository(this, scope) }

    override fun newImageLoader(context: android.content.Context): ImageLoader =
        ImageLoader.Builder(context)
            // Images get tighter timeouts than the API calls: a stalled fetch must fail (and be retried) in seconds,
            // not hold a card empty for the 30 s a feed or tarball may need.
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.06).build() }
            .crossfade(true)
            .build()
}

val android.content.Context.app: GlassApp get() = applicationContext as GlassApp
