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

    override fun onCreate() {
        super.onCreate()
        // RenderScript and the image loader take ~150 ms to set up cold; do it while the activity starts,
        // not on the first bake's critical path.
        scope.launch(Dispatchers.Default) { runCatching { wallpapers.prewarm() } }
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
