package dev.glasslauncher.shots

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import dev.glasslauncher.GlassApp
import dev.glasslauncher.app
import dev.glasslauncher.data.FeaturedConfig
import dev.glasslauncher.data.LauncherConfig
import kotlinx.coroutines.runBlocking
import coil3.SingletonImageLoader
import coil3.imageDecoderEnabled
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Puts the Robolectric device into a known state before [dev.glasslauncher.MainActivity] starts:
 * a fixed set of installed TV apps, a seeded featured-shelf cache with local fixture art, and no
 * network, so every render is deterministic.
 */
object TvHarness {

    private class FakeApp(val label: String, val from: Int, val to: Int, val ink: Int = 0xFFFFFFFF.toInt())

    private val fakeApps = linkedMapOf(
        "com.netflix.ninja" to FakeApp("Netflix", 0xFF141414.toInt(), 0xFF2B0A0C.toInt(), 0xFFE50914.toInt()),
        "com.amazon.firetv.youtube" to FakeApp("YouTube", 0xFFFFFFFF.toInt(), 0xFFF2F2F2.toInt(), 0xFFFF0033.toInt()),
        "com.stremio.one" to FakeApp("Stremio", 0xFF1B1340.toInt(), 0xFF3A2A8C.toInt()),
        "com.spotify.tv.android" to FakeApp("Spotify", 0xFF121212.toInt(), 0xFF1E3A26.toInt(), 0xFF1ED760.toInt()),
        "com.plexapp.android" to FakeApp("Plex", 0xFF1F1F1F.toInt(), 0xFF3A3A3A.toInt(), 0xFFE5A00D.toInt()),
        "org.jellyfin.androidtv" to FakeApp("Jellyfin", 0xFF101828.toInt(), 0xFF5B2A86.toInt()),
        "com.disney.disneyplus" to FakeApp("Disney+", 0xFF0B1A3A.toInt(), 0xFF1D3F8F.toInt()),
        "com.hbo.hbonow" to FakeApp("Max", 0xFF002BE7.toInt(), 0xFF0016A0.toInt()),
        "com.apple.atve.amazon.appletv" to FakeApp("Apple TV", 0xFF000000.toInt(), 0xFF1C1C1E.toInt()),
        "tv.twitch.android.viewer" to FakeApp("Twitch", 0xFF6441A5.toInt(), 0xFF9146FF.toInt()),
        "org.videolan.vlc" to FakeApp("VLC", 0xFFFF8800.toInt(), 0xFFFFA733.toInt()),
        "com.amazon.tv.settings.v2" to FakeApp("Settings", 0xFF2C2C2E.toInt(), 0xFF48484A.toInt()),
        "com.esaba.downloader" to FakeApp("Downloader", 0xFFF57C00.toInt(), 0xFFFF9800.toInt()),
        "com.estrongs.android.pop" to FakeApp("ES File Explorer", 0xFF1E88E5.toInt(), 0xFF42A5F5.toInt()),
    )

    /** The dock HomeModel's first run would seed from [fakeApps] (Netflix first). */
    private val DOCK: List<String> by lazy {
        dev.glasslauncher.home.HomeModel.DEFAULT_DOCK.filter { it in fakeApps }.distinct().take(dev.glasslauncher.home.DOCK_SIZE)
    }

    /**
     * The clock, date and status pill read [dev.glasslauncher.widgets.WallClock] (java.util.Date ignores
     * Robolectric's clock), so pin it: a fixed zone and instant, never the machine's. GLASS_SHOTS_TIME=HH:mm
     * (UTC, on 2026-10-07) overrides the default 09:41; the baselines must hold at every time (the clock is
     * masked), which is how that is proven.
     */
    private fun pinClock() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        val (h, m) = (System.getenv("GLASS_SHOTS_TIME")?.takeIf { it.isNotBlank() } ?: "09:41").split(":").map { it.toInt() }
        val at = java.time.LocalDate.of(2026, 10, 7).atTime(h, m).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        dev.glasslauncher.widgets.WallClock.now = { at }
    }

    /** Package -> label, in install order. */
    val apps: Map<String, String> get() = fakeApps.mapValues { it.value.label }

    /**
     * Call before launching the activity. [config] edits the stored [LauncherConfig]; by default the
     * first-run tips are marked seen so they don't trap focus.
     */
    fun setUp(featured: Boolean = true, config: (LauncherConfig) -> LauncherConfig = { it }) {
        blockNetwork()
        pinClock()
        useBitmapFactoryForImages()
        installApps()
        if (featured) seedFeatured()
        val app = ApplicationProvider.getApplicationContext<GlassApp>().app
        // From the defaults every time: the stored config outlives a test, so one test's text size or theme
        // leaked into the next.
        // Process-wide caches would carry one test's scene into the next.
        dev.glasslauncher.home.HeroCache.app.trim()
        // The dock is seeded here, not by HomeModel.seedDefaults: that runs after the first layout (a real
        // first-run bug, see the report: focus and hero start on the wrong dock app, then jump), so which app
        // got the first focus and hero depended on whether the test was the JVM's first (cold) or not.
        runBlocking { app.config.update { config(LauncherConfig(tipsSeen = true, seededDefaults = true, dock = DOCK)) } }
    }

    /** Any request fails fast against a dead proxy, so sources fall back to the seeded cache. */
    private fun blockNetwork() {
        for (scheme in listOf("http", "https")) {
            System.setProperty("$scheme.proxyHost", "127.0.0.1")
            System.setProperty("$scheme.proxyPort", "9")
        }
    }

    /** Robolectric's native graphics lacks ImageDecoder, which Coil uses on API 28+; fall back to BitmapFactory. */
    private fun useBitmapFactoryForImages() {
        val context = ApplicationProvider.getApplicationContext<GlassApp>()
        SingletonImageLoader.setUnsafe(
            context.newImageLoader(context).newBuilder().imageDecoderEnabled(false).build(),
        )
    }

    private fun installApps() {
        val context = ApplicationProvider.getApplicationContext<GlassApp>()
        val pm = shadowOf(context.packageManager)
        for ((index, entry) in fakeApps.entries.withIndex()) {
            val (pkg, fake) = entry
            val label = fake.label
            val component = ComponentName(pkg, "$pkg.Main")
            val bannerId = 0x7f080000 + index
            val app = ApplicationInfo().apply {
                packageName = pkg
                name = label
                nonLocalizedLabel = label
                flags = ApplicationInfo.FLAG_INSTALLED
            }
            pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = app })
            pm.addOrUpdateActivity(ActivityInfo().apply {
                packageName = pkg
                name = component.className
                nonLocalizedLabel = label
                applicationInfo = app
                exported = true
                banner = bannerId
            })
            pm.addDrawableResolution(pkg, bannerId, BitmapDrawable(context.resources, banner(fake)))
            val filter = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) }
            pm.addIntentFilterForActivity(component, filter)
        }
    }

    /** A 320x180 stand-in for the app's TV banner: brand-ish gradient with the name set in bold. */
    private fun banner(app: FakeApp): Bitmap {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, 320f, 180f, Paint().apply {
            shader = LinearGradient(0f, 0f, 320f, 180f, app.from, app.to, Shader.TileMode.CLAMP)
        })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = app.ink
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = if (app.label.length > 10) 30f else 46f
        }
        canvas.drawText(app.label, 160f, 90f - (text.descent() + text.ascent()) / 2, text)
        return bitmap
    }

    /** Writes the featured cache in [dev.glasslauncher.featured.FeaturedRepository]'s format. */
    private fun seedFeatured() {
        val context = ApplicationProvider.getApplicationContext<GlassApp>()
        val titles = listOf(
            "Northern Lights" to "A cartographer maps the last uncharted coastline.",
            "The Long Quiet" to "Two keepers, one lighthouse, and a storm that won't end.",
            "Glass City" to "A heist across the rooftops of a city made of mirrors.",
            "Deep Field" to "The night shift at an observatory finds a signal.",
            "Saltwater" to "A family restaurant on the edge of the sea.",
            "Paper Planes" to "Kids build a glider club in a town with no wind.",
            "Low Orbit" to "A repair crew is stranded between two stations.",
            "Midnight Market" to "Every stall sells something you lost.",
        )
        val items = titles.mapIndexed { i, (title, description) ->
            val image = File(fixtures(), "featured${i + 1}.jpg").toURI().toString()
            """{"id":"fixture-$i","title":"$title","subtitle":"2026 · ${90 + i * 7} min","description":"$description","image":"$image"}"""
        }
        val key = dev.glasslauncher.featured.cacheKey(FeaturedConfig()).replace("\"", "\\\"")
        File(context.cacheDir, "featured.json").writeText(
            """{"key":"$key","heading":"Popular Movies","items":[${items.joinToString(",")}]}""",
        )
    }

    private fun fixtures(): File =
        File(requireNotNull(javaClass.classLoader?.getResource("fixtures/featured1.jpg")).toURI()).parentFile!!
}
