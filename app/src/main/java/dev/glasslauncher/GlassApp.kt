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
        // RenderScript and the image loader take ~150 ms to set up cold; do it while the activity starts,
        // not on the first bake's critical path.
        scope.launch(Dispatchers.Default) { runCatching { wallpapers.prewarm() } }
        // The tile shadows bake once (a few ms to tens of ms on the stick): off the UI thread, before the first tile draws.
        scope.launch(Dispatchers.Default) { dev.glasslauncher.ui.TileShadow.focus; dev.glasslauncher.ui.TileShadow.contact; dev.glasslauncher.ui.TileShadow.glow }
        // Old tiles on disk (changed apps, removed packs) are cleared away once per start, off the main thread.
        scope.launch(Dispatchers.IO) { runCatching { tileArt.trimDisk() } }
        scope.launch(Dispatchers.IO) { dev.glasslauncher.system.HomeSetup.ensureRemoteKeys(this@GlassApp) }
        scope.launch(Dispatchers.IO) { dev.glasslauncher.system.RootFeatures.reapplyAtStart(this@GlassApp) }
        // A store visit cut short (Glass restarted): put Glass back as Home.
        scope.launch(Dispatchers.IO) { dev.glasslauncher.system.AmazonStore.close(this@GlassApp, bringHome = false) }
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
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
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.06).build() }
            .crossfade(true)
            .build()
}

val android.content.Context.app: GlassApp get() = applicationContext as GlassApp
