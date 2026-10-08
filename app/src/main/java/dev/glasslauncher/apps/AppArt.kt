package dev.glasslauncher.apps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.glasslauncher.system.Root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Full-screen art for an app's Top Shelf hero, from the most official source there is:
 * 1. Amazon's Fire TV background (1920×1080, supplied by the developer for the Appstore listing), which the
 *    Appstore keeps on the device for every app it installed (needs root to read).
 * 2. Google Play's listing: its first full-HD 16:9 image (TV apps list TV screenshots), for sideloaded apps.
 * Nothing lower than 1920×1080 is used: a soft, upscaled hero is worse than the app's logo hero.
 * Answers (including "none") are remembered on disk; the image itself is cached by Coil like any shelf art.
 */
object AppArt {
    @Serializable
    private data class Entry(val url: String? = null, val at: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var entries: MutableMap<String, Entry>? = null
    private var amazon: Map<String, String>? = null

    private fun file(context: Context) = File(context.filesDir, "app-art-v2.json") // v2: full-HD originals only

    /** The art URL for [pkg], or null when no source has one. Network and root work happen once per app. */
    suspend fun url(context: Context, http: OkHttpClient, pkg: String): String? = withContext(Dispatchers.IO) {
        // Amazon's own background always wins (read from the device, no network).
        amazonBackground(context, pkg)?.let { return@withContext it }
        val known = mutex.withLock {
            if (entries == null) File(context.filesDir, "app-art.json").delete() // v1
            val map = entries ?: runCatching { json.decodeFromString<Map<String, Entry>>(file(context).readText()).toMutableMap() }
                .getOrDefault(mutableMapOf()).also { entries = it }
            map[pkg]
        }
        val now = System.currentTimeMillis()
        if (known != null && now - known.at < if (known.url != null) 14 * DAY else DAY) return@withContext known.url
        val found = runCatching {
            pick(playCandidates(fetch(http, "https://play.google.com/store/apps/details?id=$pkg&hl=en"))) { originalSize(http, it) }
        }.getOrNull()
        // A failed lookup keeps the last known art rather than dropping it.
        val url = found ?: known?.url
        mutex.withLock {
            val map = entries ?: mutableMapOf<String, Entry>().also { entries = it }
            map[pkg] = Entry(url, now)
            runCatching { file(context).writeText(json.encodeToString(map.toMap())) }
        }
        url
    }

    /** Package → Fire TV background, from the Appstore's own records (read once per process). */
    private suspend fun amazonBackground(context: Context, pkg: String): String? {
        val map = amazon ?: readAmazon(context).also { amazon = it }
        return map[pkg]
    }

    private suspend fun readAmazon(context: Context): Map<String, String> {
        if (!Root.available()) return emptyMap()
        val dir = File(context.cacheDir, "appstore").apply { mkdirs() }
        val src = "/data/data/com.amazon.venezia/databases/AppManager.db"
        // Copied with its WAL log when there is one, so the latest rows are there.
        File(dir, "AppManager.db").writeBytes(Root.readBytes(src) ?: return emptyMap())
        Root.readBytes("$src-wal")?.let { File(dir, "AppManager.db-wal").writeBytes(it) }
        return runCatching {
            SQLiteDatabase.openDatabase(File(dir, "AppManager.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("SELECT metadata FROM tile_metadata", null).use { c ->
                    buildMap {
                        while (c.moveToNext()) runCatching {
                            val o = json.parseToJsonElement(c.getString(0)).jsonObject
                            val p = o["packageName"]?.jsonPrimitive?.content
                            val bg = o["tvBackgroundImageUrl"]?.jsonPrimitive?.content
                            if (!p.isNullOrBlank() && !bg.isNullOrBlank() && p !in this) put(p, bg)
                        }
                    }
                }
            }
        }.getOrDefault(emptyMap()).also { dir.deleteRecursively() }
    }

    private fun fetch(http: OkHttpClient, url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        http.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            return r.body.string()
        }
    }

    private val PLAY_IMAGE = Regex("""https://play-lh\.googleusercontent\.com/([\w-]+)=w\d+-h\d+""")

    /** The listing's images (screenshots, promo art) in page order, without the size suffix. */
    fun playCandidates(html: String): List<String> =
        PLAY_IMAGE.findAll(html).map { "https://play-lh.googleusercontent.com/${it.groupValues[1]}" }.distinct().toList()

    /**
     * The first candidate whose original is a real 16:9 picture at least 1920 wide (the page's sizes are
     * thumbnail crops: a phone screenshot can look landscape there), asked for at exactly 1920×1080.
     */
    fun pick(candidates: List<String>, size: (String) -> Pair<Int, Int>?): String? = candidates.take(10).firstOrNull { url ->
        val (w, h) = size(url) ?: return@firstOrNull false
        w >= 1920 && h > 0 && w.toFloat() / h in 1.74f..1.8f
    }?.let { "$it=w1920-h1080" }

    /** An image's original size from its header only (the rest of the download is cancelled). */
    private fun originalSize(http: OkHttpClient, url: String): Pair<Int, Int>? = runCatching {
        http.newCall(Request.Builder().url("$url=s0").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeStream(r.body.byteStream(), null, o)
            (o.outWidth to o.outHeight).takeIf { it.first > 0 }
        }
    }.getOrNull()

    private const val DAY = 24 * 3600_000L
}
