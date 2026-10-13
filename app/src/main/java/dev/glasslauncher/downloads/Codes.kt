package dev.glasslauncher.downloads

import dev.glasslauncher.data.MyCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.glasslauncher.app
import java.io.File

/** A short code's target. [sha256] and [name] are optional. */
@Serializable
data class CodeEntry(val code: String, val url: String, val name: String? = null, val sha256: String? = null)

object Codes {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The index file: a JSON list of entries. Entries without a code or URL, and malformed hashes, are dropped. */
    fun parseIndex(text: String): List<CodeEntry> =
        runCatching { json.decodeFromString<List<CodeEntry>>(text) }.getOrDefault(emptyList())
            .filter { it.code.isNotBlank() && it.url.isNotBlank() }
            .map { e -> e.copy(sha256 = e.sha256?.lowercase()?.takeIf { SHA256.matches(it) }, name = e.name?.takeIf { it.isNotBlank() }) }

    /** The user's own list wins over the public index; codes ignore case. Null when neither has it. */
    fun resolve(code: String, mine: List<MyCode>, index: List<CodeEntry>): CodeEntry? {
        val c = code.trim()
        mine.firstOrNull { it.code.equals(c, ignoreCase = true) }?.let { return CodeEntry(it.code, it.url, it.name.ifBlank { null }) }
        return index.firstOrNull { it.code.equals(c, ignoreCase = true) }
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}

/** The public index, fetched at most once a day; a stale copy stands in when the fetch fails. */
class CodeIndex(
    private val cache: File,
    private val fetch: suspend () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun load(): List<CodeEntry> {
        val fresh = cache.isFile && now() - cache.lastModified() < DAY_MS
        if (!fresh) {
            runCatching { fetch() }.getOrNull()?.takeIf { Codes.parseIndex(it).isNotEmpty() || it.trim().startsWith("[") }?.let { body ->
                cache.parentFile?.mkdirs()
                cache.writeText(body)
                cache.setLastModified(now())
            }
        }
        return if (cache.isFile) Codes.parseIndex(cache.readText()) else emptyList()
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        /** The repo's default branch on GitHub raw: Updater's repo name, the file under downloads/. */
        fun url(repo: String) = "https://raw.githubusercontent.com/$repo/master/downloads/codes.json"
    }
}

/** The index as the app fetches it: GitHub raw over the app's own HTTP client, cached under cacheDir. */
fun CodeIndex.Companion.forApp(context: android.content.Context): CodeIndex {
    val app = context.applicationContext
    val http = app.app.http
    val url = url(dev.glasslauncher.BuildConfig.UPDATE_REPO)
    return CodeIndex(File(app.cacheDir, "downloads/codes.json"), {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body.string()
            }
        }
    })
}
