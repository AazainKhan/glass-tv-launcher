package dev.glasslauncher.dream

import android.content.Context
import dev.glasslauncher.data.AerialQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream

@Serializable
data class AerialVideo(
    val id: String,
    val label: String,
    val hd: String,
    val uhd: String?,
    val shotId: String? = null,
    val categories: List<String> = emptyList(),
) {
    fun url(quality: AerialQuality) = if (quality == AerialQuality.Uhd4k) uhd ?: hd else hd

    /** Apple's still of the clip (~400 KB JPEG), for Choose Aerials. */
    val thumbnail: String? get() = shotId?.let { "${AerialCatalog.SNAPSHOTS}$it.jpg" }
}

/** Choose Aerials' categories, in tvOS's order: names as tvOS shows them, ids from Apple's feed. */
enum class AerialCategory(val label: String, val id: String) {
    Cityscape("Cityscape", "5EF41171-4862-4F93-800C-AD86CE5E6891"),
    Earth("Earth", "55B7C95D-CEAF-4FD8-ADEF-F5BC657D8F6D"),
    Landscape("Landscape", "A33A55D9-EDEA-4596-A850-6C10B54FBBB5"),
    Underwater("Underwater", "8BE8B524-6EAE-43F5-A3E8-01DCFA1BCD4B"),
}

/** The clips to play: all but the hidden ones (all of them if every clip is hidden). */
fun List<AerialVideo>.playable(hidden: Set<String>) = filterNot { it.id in hidden }.ifEmpty { this }

/** Apple's public tvOS Aerial catalog: a tar whose entries.json lists each video and its URLs. */
class AerialCatalog(private val context: Context, private val http: OkHttpClient) {

    private val cache = File(context.filesDir, "aerials-v2.json") // v2: with shot ids and categories
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun videos(): List<AerialVideo> = withContext(Dispatchers.IO) {
        val cached = runCatching { json.decodeFromString<List<AerialVideo>>(cache.readText()) }.getOrNull()
        val stale = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > 7L * 24 * 3600_000
        if (cached != null && !stale) return@withContext cached
        val fresh = runCatching { download() }.getOrNull()
        if (!fresh.isNullOrEmpty()) {
            cache.writeText(json.encodeToString(fresh))
            File(context.filesDir, "aerials.json").delete() // v1, without categories
            fresh
        } else cached.orEmpty()
    }

    private fun download(): List<AerialVideo> {
        val request = Request.Builder().url(FEED).build()
        http.newCall(request).execute().use { r ->
            check(r.isSuccessful)
            val entries = extract(r.body.byteStream(), "entries.json") ?: error("entries.json missing")
            val assets = json.parseToJsonElement(entries).jsonObject["assets"]!!.jsonArray
            return assets.mapNotNull { a ->
                val o = a.jsonObject
                val hd = o["url-1080-SDR"]?.jsonPrimitive?.content ?: o["url-1080-H264"]?.jsonPrimitive?.content ?: return@mapNotNull null
                AerialVideo(
                    id = o["id"]?.jsonPrimitive?.content ?: hd,
                    label = o["accessibilityLabel"]?.jsonPrimitive?.content ?: "",
                    hd = hd,
                    uhd = o["url-4K-SDR"]?.jsonPrimitive?.content,
                    shotId = o["shotID"]?.jsonPrimitive?.content,
                    categories = o["categories"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                )
            }
        }
    }

    /** Minimal ustar reader: returns the named member as text. */
    private fun extract(input: InputStream, name: String): String? {
        val header = ByteArray(512)
        while (true) {
            if (input.readNBytesCompat(header) < 512) return null
            val entryName = String(header, 0, 100).trimEnd('\u0000')
            if (entryName.isEmpty()) return null
            val size = String(header, 124, 12).trim('\u0000', ' ').ifEmpty { "0" }.toLong(8)
            val padded = (size + 511) / 512 * 512
            if (entryName == name || entryName.endsWith("/$name")) {
                val data = ByteArray(size.toInt())
                input.readNBytesCompat(data)
                return String(data)
            }
            var skip = padded
            while (skip > 0) {
                val n = input.skip(skip)
                if (n <= 0) { if (input.read() < 0) return null else skip-- } else skip -= n
            }
        }
    }

    private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    companion object {
        const val FEED = "https://sylvan.apple.com/Aerials/resources-16.tar"
        const val SNAPSHOTS = "https://sylvan.apple.com/Aerials/jjGqDwCNrOsVJ4of6uSVr/snapshots/"
    }
}
