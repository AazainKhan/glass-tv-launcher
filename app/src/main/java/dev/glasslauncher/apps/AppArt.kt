package dev.glasslauncher.apps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.glasslauncher.system.Root
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * An app's own logo art for its Top Shelf hero: Amazon's 16:9 Fire TV icon (1280×720, supplied by the
 * developer for the Appstore listing), which the Appstore keeps on the device for every app it installed
 * (needs root to read). Apps it doesn't list fall back to their own banner (HomeScreen's logo plate).
 * Never a screenshot or a promotional collage: the hero is the app's logo, full screen.
 */
object AppArt {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var icons: Map<String, String>? = null

    /** The Fire TV icon URL for [pkg], or null. The Appstore's records are read once per process. */
    suspend fun url(context: Context, pkg: String): String? = mutex.withLock {
        (icons ?: readAmazon(context).also { icons = it })[pkg]
    }

    /** One Appstore tile record (JSON) → its package and Fire TV icon, if it has one. */
    fun fireTvIcon(metadata: String): Pair<String, String>? = runCatching {
        val o = json.parseToJsonElement(metadata).jsonObject
        val pkg = o["packageName"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val icon = o["tvIconUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        pkg to icon
    }.getOrNull()

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
                    buildMap { while (c.moveToNext()) fireTvIcon(c.getString(0))?.let { (p, u) -> if (p !in this) put(p, u) } }
                }
            }
        }.getOrDefault(emptyMap()).also { dir.deleteRecursively() }
    }
}
