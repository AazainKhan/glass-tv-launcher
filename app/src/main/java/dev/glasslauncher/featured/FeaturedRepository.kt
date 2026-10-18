package dev.glasslauncher.featured

import android.content.Context
import android.os.SystemClock
import dev.glasslauncher.addons.AddonRefresh
import dev.glasslauncher.addons.AddonSnapshot
import dev.glasslauncher.addons.AddonSurfaces
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
// 5: a feed may carry headed sections (the YouTube shelf, P125b); a YouTube feed cached under 4 is one row.
private const val FEED_VERSION = 5

/** The cache key for [cfg]'s feed, versioned so a feed cached before items gained a field (logos) is fetched again. */
fun cacheKey(cfg: FeaturedConfig) = "$FEED_VERSION:$cfg"

/**
 * [key] is the [cacheKey] the [feed] was loaded for; [settledKey] the key whose load has finished (feed or failure), null
 * while one is under way. A failed fetch keeps the previous feed up, so a feed whose [key] is not the wanted one and whose
 * wanted key has settled is stale for good (ShelfFeed.usable).
 */
data class FeaturedState(val feed: FeaturedFeed? = null, val error: String? = null, val key: String? = null, val settledKey: String? = null)

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

    /** An add-on's feed is never cached on disk or in memory (it would outlive the add-on being switched off): it is asked for again after its own interval. */
    private var addonVersion: Any? = null
    private var addonUntil = 0L

    /** How many times each TV-row shelf has been fetched: the rotation of its window over everything the app published (P153). */
    private val rotations = HashMap<String, Int>()

    /** How old a feed may be before a refresh fetches again: a TV app's rows move often, the rest rarely. */
    private fun maxAgeMs(cfg: FeaturedConfig) = if (cfg.source == FeaturedSourceId.TvApp) TV_MAX_AGE_MS else 30 * 60_000L

    private suspend fun refreshAddon(cfg: FeaturedConfig, force: Boolean, addons: AddonSnapshot) {
        val key = cacheKey(cfg)
        // A setting the user changed, or the add-on switched on, is asked again at once; otherwise the add-on's interval (15 min or more) holds. Refresh Now (force) is the user's too.
        val version = AddonSurfaces.version(addons, cfg.addonPackage)
        val now = SystemClock.elapsedRealtime()
        if (!force && loadedKey == key && version == addonVersion && now < addonUntil) return
        _state.value = _state.value.copy(settledKey = null)
        val result = AddonSurfaces.feed(app, addons, cfg.addonPackage)
        loadedKey = key
        addonVersion = version
        if (result != null) {
            _state.value = FeaturedState(result.first, key = key, settledKey = key)
            addonUntil = SystemClock.elapsedRealtime() + AddonRefresh.minutes(result.second) * 60_000L
        } else {
            // Stale content goes: the shelf falls back to the app's hero, and the next try is a full interval away.
            _state.value = FeaturedState(null, "The add-on's content isn't available", key = key, settledKey = key)
            addonUntil = SystemClock.elapsedRealtime() + AddonRefresh.MIN * 60_000L
        }
    }

    suspend fun refresh(cfg: FeaturedConfig, force: Boolean = false, addons: AddonSnapshot = AddonSnapshot.NONE) = mutex.withLock {
        if (cfg.source == FeaturedSourceId.Addon) return@withLock refreshAddon(cfg, force, addons)
        val key = cacheKey(cfg)
        val now = SystemClock.elapsedRealtime()
        memory[key]?.let { (feed, at) ->
            _state.value = FeaturedState(feed, key = key, settledKey = key)
            loadedKey = key; loadedAt = at
            if (!force && now - at < maxAgeMs(cfg)) return@withLock
        }
        val fresh = loadedKey == key && now - loadedAt < maxAgeMs(cfg)
        if (fresh && !force) return@withLock
        // Switching source: show its cached feed if there is one; otherwise keep the current shelf up until
        // the new one arrives (blanking it would drop the backdrop to the wallpaper for a moment).
        if (loadedKey != key) readCache(key)?.let {
            _state.value = FeaturedState(it, key = key, settledKey = null)
            // Seen again soon: the memory copy is marked old enough that a fresh fetch still follows.
            memory[key] = it to (now - 25 * 60_000L)
        }
        // A load for this key is under way: whatever feed is up stays until it settles (it is the shelf's, not this app's).
        _state.value = _state.value.copy(settledKey = null)
        val result = when (cfg.source) {
            FeaturedSourceId.ContinueWatching -> runCatching { TvRows.continueWatching(app).withCinemeta(http) }
            FeaturedSourceId.TvApp -> runCatching {
                val turn = rotations.merge(key, 1, Int::plus) ?: 1
                TvRows.appRow(app, cfg.appPackage, appLabel(cfg.appPackage), rotation = turn - 1).withCinemeta(http)
            }
            else -> {
                val source = Sources.of(cfg.source) ?: run { _state.value = FeaturedState(); return@withLock }
                runCatching { source.load(http, cfg) }
            }
        }
        result.onSuccess { feed ->
            _state.value = FeaturedState(feed, key = key, settledKey = key)
            loadedKey = key
            loadedAt = SystemClock.elapsedRealtime()
            memory[key] = feed to loadedAt
            writeCache(key, feed)
        }.onFailure { e ->
            if (e is FeaturedAuthExpired) dev.glasslauncher.system.CredentialNotice.expired(app, cfg.source)
            _state.value = _state.value.copy(error = if (_state.value.feed == null) e.message ?: "Couldn't load featured content" else null, settledKey = key)
            loadedKey = key
            loadedAt = SystemClock.elapsedRealtime() - 25 * 60_000L
        }
    }

    companion object {
        /** A TV app's shelf is fetched again when it is this old (P153): at most every five minutes, whatever wakes it. */
        const val TV_MAX_AGE_MS = 5 * 60_000L
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
    private data class Cached(val key: String, val heading: String, val items: List<FeaturedItem>, val sections: List<FeaturedSection> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    private fun fileFor(key: String) = File(dir, key.hashCode().toUInt().toString(16) + ".json")

    fun read(key: String): FeaturedFeed? {
        val f = fileFor(key)
        fun parse(file: File) = runCatching { json.decodeFromString(Cached.serializer(), file.readText()) }.getOrNull()?.takeIf { it.key == key }
        // The single file the previous version wrote (and the device tests seed) is still read, for its own key.
        val cached = parse(f) ?: legacy?.let { parse(it) } ?: return null
        runCatching { if (f.exists()) f.setLastModified(System.currentTimeMillis()) }
        return FeaturedFeed(cached.heading, cached.items, cached.sections)
    }

    fun write(key: String, feed: FeaturedFeed) {
        runCatching {
            dir.mkdirs()
            fileFor(key).writeText(json.encodeToString(Cached.serializer(), Cached(key, feed.heading, feed.items, feed.sections)))
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
