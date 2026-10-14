package dev.glasslauncher.search

import dev.glasslauncher.featured.FeaturedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Movies and shows from Cinemeta's public catalog search (Stremio's, keyless): poster, year, kind. Results are [FeaturedItem]s that open
 * like the Top Shelf's Stremio rows (a `stremio:` detail link, only ever sent to the Stremio app) and carry an IMDb id, so the info sheet
 * knows them. Parsing is pure and tested; the fetch goes through the shared public-only client, off Main.
 */
object CinemetaSearch {
    private const val BASE = "https://v3-cinemeta.strem.io/catalog"
    private const val STREMIO = "com.stremio.one"
    private const val MAX_PER_KIND = 12

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** The items in one catalog search response of [type] ("movie" or "series"). */
    fun parse(type: String, body: String): List<FeaturedItem> {
        val metas = (Json.parseToJsonElement(body).jsonObject["metas"] as? JsonArray) ?: return emptyList()
        return metas.mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val id = o.str("imdb_id") ?: o.str("id") ?: return@mapNotNull null
            if (!Regex("tt\\d+").matches(id)) return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            FeaturedItem(
                id = id, title = name,
                description = o.str("description"),
                image = o.str("poster") ?: o.str("background"),
                link = "stremio:///detail/$type/$id",
                packages = listOf(STREMIO),
                year = Regex("\\d{4}").find(o.str("releaseInfo") ?: o.str("year").orEmpty())?.value?.toIntOrNull(),
                type = if (type == "movie") "Movie" else "TV Show",
                aspect = 2f / 3f,
            )
        }.take(MAX_PER_KIND)
    }

    /** Movies then shows for [query], both searches in parallel. Throws when neither could be reached (the model then says "offline"). */
    suspend fun search(http: OkHttpClient, query: String): List<FeaturedItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isEmpty()) return@withContext emptyList()
        val answers = coroutineScope {
            listOf("movie", "series").map { type ->
                async {
                    runCatching {
                        http.newCall(Request.Builder().url("$BASE/$type/top/search=$q.json").build()).execute().use { r ->
                            if (r.isSuccessful) parse(type, r.body?.string().orEmpty()) else error("HTTP ${r.code}")
                        }
                    }
                }
            }.map { it.await() }
        }
        if (answers.all { it.isFailure }) throw java.io.IOException("Cinemeta unreachable")
        answers.flatMap { it.getOrDefault(emptyList()) }
    }
}
