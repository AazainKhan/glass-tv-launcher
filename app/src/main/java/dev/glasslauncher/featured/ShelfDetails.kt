package dev.glasslauncher.featured

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

/** One name in the cast or crew, with the character and a photo when the source has them. */
data class Person(val name: String, val role: String? = null, val photo: String? = null)

/**
 * What the Info sheet shows that the shelf itself doesn't (P110): cast with photos, director, writers, country,
 * seasons and awards. The title, synopsis, rating, year, runtime and genre are on the shelf already, so they are not
 * repeated here. From Cinemeta's meta (no API key, so it works in APK mode); parsing is pure.
 */
data class ShelfDetails(
    val cast: List<Person> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val country: String? = null,
    val seasons: Int? = null,
    val awards: String? = null,
) {
    val isEmpty: Boolean get() = cast.isEmpty() && directors.isEmpty() && writers.isEmpty() && country == null && seasons == null && awards == null

    companion object {
        /** At most this many faces fit the sheet's row. */
        const val MAX_CAST = 6

        private fun JsonObject.strings(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() } }.orEmpty()

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

        private fun people(arr: JsonArray?): List<Person> = arr.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull (e as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.let { Person(it.content) }
            val name = o.str("name") ?: return@mapNotNull null
            Person(name, role = o.str("character"), photo = o.str("photo"))
        }

        fun parse(meta: JsonObject): ShelfDetails {
            val extras = meta["app_extras"] as? JsonObject
            // Cinemeta's app_extras carry faces and roles; the plain arrays are names only.
            val cast = people(extras?.get("cast") as? JsonArray).ifEmpty { meta.strings("cast").map { Person(it) } }.take(MAX_CAST)
            val directors = people(extras?.get("directors") as? JsonArray).map { it.name }.ifEmpty { meta.strings("director") }
            val writers = people(extras?.get("writers") as? JsonArray).map { it.name }.ifEmpty { meta.strings("writer") }
            val seasons = (meta["videos"] as? JsonArray)?.mapNotNull { v ->
                ((v as? JsonObject)?.get("season") as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }
            }?.distinct()?.size?.takeIf { it > 0 }
            return ShelfDetails(
                cast = cast, directors = directors.take(3), writers = writers.take(3),
                country = meta.str("country"), seasons = seasons, awards = meta.str("awards"),
            )
        }

        /** The details of [item] from Cinemeta (cached by IMDb id), or null when it has no IMDb link or none arrive. */
        suspend fun load(http: OkHttpClient, item: FeaturedItem): ShelfDetails? = withContext(Dispatchers.IO) {
            val meta = cinemetaFor(item, http) ?: return@withContext null
            runCatching { parse(meta) }.getOrNull()?.takeIf { !it.isEmpty }
        }
    }
}
