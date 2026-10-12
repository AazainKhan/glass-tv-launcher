package dev.glasslauncher.featured

import android.content.Context
import android.os.SystemClock
import dev.glasslauncher.data.FeaturedConfig
import dev.glasslauncher.data.FeaturedSourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

/** Bump when FeaturedItem gains data a cached feed wouldn't have. */
// 4: JustWatch rows resolve the service's per-country package code (P89); the 4-title Prime feed cached under 3 is dropped.
private const val FEED_VERSION = 4

/** The cache key for [cfg]'s feed, versioned so a feed cached before items gained a field (logos) is fetched again. */
fun cacheKey(cfg: FeaturedConfig) = "$FEED_VERSION:$cfg"

/** [key] is the [cacheKey] the [feed] was loaded for: a failed fetch keeps the previous feed up, and it is not the new app's. */
data class FeaturedState(val feed: FeaturedFeed? = null, val error: String? = null, val key: String? = null)

/** Loads the selected source, keeps the last good result on disk so the shelf is filled instantly at boot. */
class FeaturedRepository(context: Context, private val http: OkHttpClient) {
    private val app = context.applicationContext

    /** Several feeds on disk (one per shelf app, 24 at most), so a restart or an evicted app comes back at once (P105 #12). */
    private val disk = DiskFeedCache(File(context.cacheDir, "featured"), legacy = File(context.cacheDir, "featured.json"))
    private val mutex = Mutex()
    private val _state = MutableStateFlow(FeaturedState())
    val state: StateFlow<FeaturedState> = _state
    private var loadedKey: String? = null
    private var loadedAt = 0L
    /** Recent feeds by config, so the "Focused app" shelf switches between apps without refetching. */
    private val memory = object : LinkedHashMap<String, Pair<FeaturedFeed, Long>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<FeaturedFeed, Long>>) = size > 8
    }

    suspend fun refresh(cfg: FeaturedConfig, force: Boolean = false) = mutex.withLock {
        val key = cacheKey(cfg)
        val now = SystemClock.elapsedRealtime()
        memory[key]?.let { (feed, at) ->
            _state.value = FeaturedState(feed, key = key)
            loadedKey = key; loadedAt = at
            if (!force && now - at < 30 * 60_000L) return@withLock
        }
        val fresh = loadedKey == key && now - loadedAt < 30 * 60_000L
        if (fresh && !force) return@withLock
        // Switching source: show its cached feed if there is one; otherwise keep the current shelf up until
        // the new one arrives (blanking it would drop the backdrop to the wallpaper for a moment).
        if (loadedKey != key) readCache(key)?.let {
            _state.value = FeaturedState(it, key = key)
            // Seen again soon: the memory copy is marked old enough that a fresh fetch still follows.
            memory[key] = it to (now - 25 * 60_000L)
        }
        val result = when (cfg.source) {
            FeaturedSourceId.ContinueWatching -> runCatching { TvRows.continueWatching(app).withCinemeta(http) }
            FeaturedSourceId.TvApp -> runCatching { TvRows.appRow(app, cfg.appPackage, appLabel(cfg.appPackage)).withCinemeta(http) }
            else -> {
                val source = Sources.of(cfg.source) ?: run { _state.value = FeaturedState(); return@withLock }
                runCatching { source.load(http, cfg) }
            }
        }
        result.onSuccess { feed ->
            _state.value = FeaturedState(feed, key = key)
            loadedKey = key
            loadedAt = SystemClock.elapsedRealtime()
            memory[key] = feed to loadedAt
            writeCache(key, feed)
        }.onFailure { e ->
            _state.value = _state.value.copy(error = if (_state.value.feed == null) e.message ?: "Couldn't load featured content" else null)
            loadedKey = key
            loadedAt = SystemClock.elapsedRealtime() - 25 * 60_000L
        }
    }

    private fun appLabel(pkg: String) = runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private suspend fun readCache(key: String): FeaturedFeed? = withContext(Dispatchers.IO) { disk.read(key) }

    private suspend fun writeCache(key: String, feed: FeaturedFeed) = withContext(Dispatchers.IO) { disk.write(key, feed) }
}

/**
 * The feeds kept on disk, one file per cache key, newest-used kept: a shelf for an app seen before is filled from here
 * the first frame, not after a network round trip (Apple TV's art took over 600 ms uncached). Plain files, so it is
 * tested on the JVM.
 */
class DiskFeedCache(private val dir: File, private val max: Int = 24, private val legacy: File? = null) {
    @Serializable
    private data class Cached(val key: String, val heading: String, val items: List<FeaturedItem>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun fileFor(key: String) = File(dir, key.hashCode().toUInt().toString(16) + ".json")

    fun read(key: String): FeaturedFeed? {
        val f = fileFor(key)
        fun parse(file: File) = runCatching { json.decodeFromString(Cached.serializer(), file.readText()) }.getOrNull()?.takeIf { it.key == key }
        // The single file the previous version wrote (and the device tests seed) is still read, for its own key.
        val cached = parse(f) ?: legacy?.let { parse(it) } ?: return null
        runCatching { if (f.exists()) f.setLastModified(System.currentTimeMillis()) }
        return FeaturedFeed(cached.heading, cached.items)
    }

    fun write(key: String, feed: FeaturedFeed) {
        runCatching {
            dir.mkdirs()
            fileFor(key).writeText(json.encodeToString(Cached.serializer(), Cached(key, feed.heading, feed.items)))
            trim()
        }
    }

    /** Keeps the [max] most recently used files. */
    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= max) return
        files.sortedByDescending { it.lastModified() }.drop(max).forEach { it.delete() }
    }
}
