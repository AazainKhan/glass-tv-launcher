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
private const val FEED_VERSION = 3

/** The cache key for [cfg]'s feed, versioned so a feed cached before items gained a field (logos) is fetched again. */
fun cacheKey(cfg: FeaturedConfig) = "$FEED_VERSION:$cfg"

data class FeaturedState(val feed: FeaturedFeed? = null, val error: String? = null)

/** Loads the selected source, keeps the last good result on disk so the shelf is filled instantly at boot. */
class FeaturedRepository(context: Context, private val http: OkHttpClient) {
    private val app = context.applicationContext

    @Serializable
    private data class Cached(val key: String, val heading: String, val items: List<FeaturedItem>)

    private val file = File(context.cacheDir, "featured.json")
    private val json = Json { ignoreUnknownKeys = true }
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
            _state.value = FeaturedState(feed)
            loadedKey = key; loadedAt = at
            if (!force && now - at < 30 * 60_000L) return@withLock
        }
        val fresh = loadedKey == key && now - loadedAt < 30 * 60_000L
        if (fresh && !force) return@withLock
        // Switching source: show its cached feed if there is one; otherwise keep the current shelf up until
        // the new one arrives (blanking it would drop the backdrop to the wallpaper for a moment).
        if (loadedKey != key) readCache(key)?.let { _state.value = FeaturedState(it) }
        val result = when (cfg.source) {
            FeaturedSourceId.ContinueWatching -> runCatching { TvRows.continueWatching(app).withCinemeta(http) }
            FeaturedSourceId.TvApp -> runCatching { TvRows.appRow(app, cfg.appPackage, appLabel(cfg.appPackage)).withCinemeta(http) }
            else -> {
                val source = Sources.of(cfg.source) ?: run { _state.value = FeaturedState(); return@withLock }
                runCatching { source.load(http, cfg) }
            }
        }
        result.onSuccess { feed ->
            _state.value = FeaturedState(feed)
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

    private suspend fun readCache(key: String): FeaturedFeed? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(Cached.serializer(), file.readText()) }.getOrNull()
            ?.takeIf { it.key == key }
            ?.let { FeaturedFeed(it.heading, it.items) }
    }

    private suspend fun writeCache(key: String, feed: FeaturedFeed) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(json.encodeToString(Cached.serializer(), Cached(key, feed.heading, feed.items))) }
    }
}
