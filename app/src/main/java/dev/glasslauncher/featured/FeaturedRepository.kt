package dev.glasslauncher.featured

import android.content.Context
import android.os.SystemClock
import dev.glasslauncher.data.FeaturedConfig
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

data class FeaturedState(val feed: FeaturedFeed? = null, val error: String? = null)

/** Loads the selected source, keeps the last good result on disk so the shelf is filled instantly at boot. */
class FeaturedRepository(context: Context, private val http: OkHttpClient) {

    @Serializable
    private data class Cached(val key: String, val heading: String, val items: List<FeaturedItem>)

    private val file = File(context.cacheDir, "featured.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(FeaturedState())
    val state: StateFlow<FeaturedState> = _state
    private var loadedKey: String? = null
    private var loadedAt = 0L

    suspend fun refresh(cfg: FeaturedConfig, force: Boolean = false) = mutex.withLock {
        val key = cfg.toString()
        val fresh = loadedKey == key && SystemClock.elapsedRealtime() - loadedAt < 30 * 60_000L
        if (fresh && !force) return@withLock
        if (loadedKey != key) {
            _state.value = FeaturedState(readCache(key))
        }
        val source = Sources.of(cfg.source) ?: run { _state.value = FeaturedState(); return@withLock }
        val result = runCatching { source.load(http, cfg) }
        result.onSuccess { feed ->
            _state.value = FeaturedState(feed)
            loadedKey = key
            loadedAt = SystemClock.elapsedRealtime()
            writeCache(key, feed)
        }.onFailure { e ->
            _state.value = _state.value.copy(error = if (_state.value.feed == null) e.message ?: "Couldn't load featured content" else null)
            loadedKey = key
            loadedAt = SystemClock.elapsedRealtime() - 25 * 60_000L
        }
    }

    private suspend fun readCache(key: String): FeaturedFeed? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(Cached.serializer(), file.readText()) }.getOrNull()
            ?.takeIf { it.key == key }
            ?.let { FeaturedFeed(it.heading, it.items) }
    }

    private suspend fun writeCache(key: String, feed: FeaturedFeed) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(json.encodeToString(Cached.serializer(), Cached(key, feed.heading, feed.items))) }
    }
}
