package dev.glasslauncher.featured

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.glasslauncher.data.FeaturedConfig
import dev.glasslauncher.data.FeaturedSourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class FeaturedItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val image: String? = null,
    val logo: String? = null,
    /** VIEW uri to open the item, tried first. */
    val link: String? = null,
    /** Apps that can open [link] or, failing that, are launched directly. Tried in order. */
    val packages: List<String> = emptyList(),
    val year: Int? = null,
    /** Age rating as the service shows it (14+, TV-MA…). */
    val rating: String? = null,
    val genre: String? = null,
    val durationMin: Int? = null,
    /** "S2, E4" for an episode. */
    val episode: String? = null,
    /** How far it has been watched (0..1), for Resume; null if never started. */
    val progress: Float? = null,
    /** The art's width / height when the source says (TV rows do: Spotify's covers are 1:1); null is 16:9. */
    val aspect: Float? = null,
) {
    /** The details line under the title, in tvOS's order: rating · year · episode · duration · genre. */
    fun metaLine(): String? {
        val duration = durationMin?.takeIf { it > 0 }?.let { m -> if (m >= 60) "${m / 60} hr" + (if (m % 60 > 0) " ${m % 60} min" else "") else "$m min" }
        val parts = listOfNotNull(rating, year?.toString(), episode, duration, genre)
        return if (parts.isEmpty()) subtitle else parts.joinToString(" · ")
    }
}

data class FeaturedFeed(val heading: String, val items: List<FeaturedItem>)

interface FeaturedSource {
    suspend fun load(http: OkHttpClient, cfg: FeaturedConfig): FeaturedFeed
}

private val json = Json { ignoreUnknownKeys = true }

private fun OkHttpClient.getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement {
    val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    newCall(request).execute().use { r ->
        check(r.isSuccessful) { "HTTP ${r.code}" }
        return json.parseToJsonElement(r.body.string())
    }
}

private fun JsonElement?.str(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content?.takeIf { it.isNotBlank() }

object Sources {
    fun of(id: FeaturedSourceId): FeaturedSource? = when (id) {
        FeaturedSourceId.Off -> null
        FeaturedSourceId.Stremio -> Stremio
        FeaturedSourceId.Tmdb -> Tmdb
        FeaturedSourceId.YouTube -> YouTube
        FeaturedSourceId.Plex -> Plex
        FeaturedSourceId.JustWatch -> JustWatch
        FeaturedSourceId.ContinueWatching, FeaturedSourceId.TvApp -> null // TvRows, which needs a Context
    }

    val stremioCatalogs = listOf(
        "movie/top" to "Popular Movies",
        "series/top" to "Popular Series",
        "movie/imdbRating" to "Featured Movies",
        "series/imdbRating" to "Featured Series",
        "movie/year" to "New Movies",
    )

    val tmdbProviders = listOf(
        "trending" to "Trending",
        "netflix" to "Netflix",
        "prime" to "Prime Video",
        "appletv" to "Apple TV+",
        "disney" to "Disney+",
        "max" to "Max",
        "hulu" to "Hulu",
    )
}

/**
 * TV rows carry portrait posters; for titles with an IMDb id in their link (Stremio's rows), Cinemeta
 * has the landscape background, the logo and the details the shelf shows. Others are left as they are.
 */
private val cinemetaCache = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

suspend fun FeaturedFeed.withCinemeta(http: OkHttpClient): FeaturedFeed = withContext(Dispatchers.IO) {
    val imdb = Regex("(movie|series)/(tt\\d+)")
    // In parallel, and remembered: a shelf of a dozen titles otherwise took ~10 s to appear.
    copy(items = kotlinx.coroutines.coroutineScope { items.map { item -> async { enrich(item, imdb, http) } }.map { it.await() } })
}

private fun enrich(item: FeaturedItem, imdb: Regex, http: OkHttpClient): FeaturedItem {
    val m = item.link?.let { imdb.find(it) } ?: return item
    // Stremio's rows sometimes label series as movies, so try the other type too.
    val types = listOf(m.groupValues[1], if (m.groupValues[1] == "movie") "series" else "movie")
    val o = cinemetaCache[m.groupValues[2]] ?: types.firstNotNullOfOrNull { t ->
        runCatching { http.getJson("https://v3-cinemeta.strem.io/meta/$t/${m.groupValues[2]}.json").jsonObject["meta"] as? JsonObject }.getOrNull()
    }?.also { cinemetaCache[m.groupValues[2]] = it } ?: return item
    return runCatching {
        item.copy(
            image = o["background"].str() ?: item.image,
            logo = o["logo"].str() ?: item.logo,
            subtitle = item.subtitle ?: listOfNotNull(o["releaseInfo"].str(), o["imdbRating"].str()?.let { "IMDb $it" }).joinToString("  ·  ").ifEmpty { null },
            description = item.description ?: o["description"].str(),
        )
    }.getOrDefault(item)
}

object Stremio : FeaturedSource {
    override suspend fun load(http: OkHttpClient, cfg: FeaturedConfig) = withContext(Dispatchers.IO) {
        val (type, catalog) = cfg.stremioCatalog.split('/').let { it[0] to it.getOrElse(1) { "top" } }
        val metas = http.getJson("https://v3-cinemeta.strem.io/catalog/$type/$catalog.json").jsonObject["metas"]!!.jsonArray
        val items = metas.take(20).mapNotNull { m ->
            val o = m.jsonObject
            val id = o["id"].str() ?: return@mapNotNull null
            FeaturedItem(
                id = id,
                title = o["name"].str() ?: return@mapNotNull null,
                subtitle = listOfNotNull(o["releaseInfo"].str(), o["imdbRating"].str()?.let { "IMDb $it" }, o["runtime"].str()).joinToString("  ·  ").ifEmpty { null },
                description = o["description"].str(),
                image = o["background"].str() ?: o["poster"].str(),
                logo = o["logo"].str(),
                link = "stremio:///detail/$type/$id",
                packages = listOf("com.stremio.one"),
            )
        }
        FeaturedFeed(Sources.stremioCatalogs.firstOrNull { it.first == cfg.stremioCatalog }?.second ?: "Stremio", items)
    }
}

object Tmdb : FeaturedSource {
    private data class Provider(val tmdbId: Int, val name: String, val packages: List<String>)

    private val providers = mapOf(
        "netflix" to Provider(8, "Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient")),
        "prime" to Provider(9, "Prime Video", listOf("com.amazon.avod", "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")),
        "appletv" to Provider(350, "Apple TV+", listOf("com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv")),
        "disney" to Provider(337, "Disney+", listOf("com.disney.disneyplus")),
        "max" to Provider(1899, "Max", listOf("com.wbd.stream", "com.hbo.hbonow")),
        "hulu" to Provider(15, "Hulu", listOf("com.hulu.livingroomplus", "com.hulu.plus")),
    )

    override suspend fun load(http: OkHttpClient, cfg: FeaturedConfig) = withContext(Dispatchers.IO) {
        require(cfg.tmdbKey.isNotBlank()) { "Add a TMDB API key in Settings" }
        val provider = providers[cfg.tmdbProvider]
        val base = "https://api.themoviedb.org/3"
        val bearer = cfg.tmdbKey.startsWith("eyJ")
        fun url(path: String, params: Map<String, String> = emptyMap()) = "$base$path".toHttpUrl().newBuilder().apply {
            if (!bearer) addQueryParameter("api_key", cfg.tmdbKey)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build().toString()
        val headers = if (bearer) mapOf("Authorization" to "Bearer ${cfg.tmdbKey}") else emptyMap()

        val results: List<Pair<JsonObject, String>> = if (provider == null) {
            http.getJson(url("/trending/all/day"), headers).jsonObject["results"]!!.jsonArray
                .map { it.jsonObject to (it.jsonObject["media_type"].str() ?: "movie") }
        } else {
            val params = mapOf(
                "with_watch_providers" to provider.tmdbId.toString(),
                "watch_region" to cfg.tmdbRegion,
                "sort_by" to "popularity.desc",
            )
            val movies = http.getJson(url("/discover/movie", params), headers).jsonObject["results"]!!.jsonArray.map { it.jsonObject to "movie" }
            val shows = http.getJson(url("/discover/tv", params), headers).jsonObject["results"]!!.jsonArray.map { it.jsonObject to "tv" }
            movies.zip(shows).flatMap { listOf(it.first, it.second) }
        }
        val items = results.filter { it.first["backdrop_path"].str() != null }.take(20).map { (o, type) ->
            val title = o["title"].str() ?: o["name"].str() ?: ""
            val year = (o["release_date"].str() ?: o["first_air_date"].str())?.take(4)
            FeaturedItem(
                id = "$type-${o["id"].str()}",
                title = title,
                subtitle = listOfNotNull(if (type == "tv") "Series" else "Movie", year, provider?.name).joinToString("  ·  "),
                description = o["overview"].str(),
                image = "https://image.tmdb.org/t/p/original" + o["backdrop_path"].str(),
                packages = provider?.packages ?: emptyList(),
            )
        }
        FeaturedFeed(if (provider == null) "Trending Today" else "Popular on ${provider.name}", items)
    }
}

object YouTube : FeaturedSource {
    override suspend fun load(http: OkHttpClient, cfg: FeaturedConfig) = withContext(Dispatchers.IO) {
        require(cfg.youtubeKey.isNotBlank()) { "Add a YouTube Data API key in Settings" }
        val url = "https://www.googleapis.com/youtube/v3/videos".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("chart", "mostPopular")
            .addQueryParameter("regionCode", cfg.youtubeRegion)
            .addQueryParameter("maxResults", "20")
            .addQueryParameter("key", cfg.youtubeKey)
            .build().toString()
        val items = http.getJson(url).jsonObject["items"]!!.jsonArray.mapNotNull { v ->
            val o = v.jsonObject
            val id = o["id"].str() ?: return@mapNotNull null
            val s = o["snippet"]!!.jsonObject
            val thumbs = s["thumbnails"]?.jsonObject
            val image = listOf("maxres", "standard", "high").firstNotNullOfOrNull { thumbs?.get(it)?.jsonObject?.get("url").str() }
            FeaturedItem(
                id = id,
                title = s["title"].str() ?: "",
                subtitle = s["channelTitle"].str(),
                image = image,
                link = "https://www.youtube.com/watch?v=$id",
                packages = listOf(
                    "com.teamsmart.videomanager.tv", "com.google.android.youtube.tv", "com.amazon.firetv.youtube",
                    "io.gh.reisxd.tizentube.cobalt",
                ),
            )
        }
        FeaturedFeed("Trending on YouTube", items)
    }
}

object Plex : FeaturedSource {
    const val PRODUCT = "Glass TV Launcher"

    fun headers(clientId: String, token: String? = null) = buildMap {
        put("Accept", "application/json")
        put("X-Plex-Product", PRODUCT)
        put("X-Plex-Client-Identifier", clientId)
        if (token != null) put("X-Plex-Token", token)
    }

    /** Starts the plex.tv/link flow; returns (pinId, code). */
    suspend fun createPin(http: OkHttpClient, clientId: String): Pair<Long, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://plex.tv/api/v2/pins")
            .post(okhttp3.FormBody.Builder().add("strong", "false").build())
            .apply { headers(clientId).forEach { (k, v) -> header(k, v) } }
            .build()
        http.newCall(request).execute().use { r ->
            val o = json.parseToJsonElement(r.body.string()).jsonObject
            o["id"]!!.jsonPrimitive.content.toLong() to o["code"]!!.jsonPrimitive.content
        }
    }

    suspend fun pollPin(http: OkHttpClient, clientId: String, pinId: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.getJson("https://plex.tv/api/v2/pins/$pinId", headers(clientId)).jsonObject["authToken"].str()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    override suspend fun load(http: OkHttpClient, cfg: FeaturedConfig) = withContext(Dispatchers.IO) {
        require(cfg.plexToken.isNotBlank()) { "Sign in to Plex in Settings" }
        val h = headers(cfg.plexClientId, cfg.plexToken)
        val servers = http.getJson("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", h).jsonArray
            .map { it.jsonObject }
            .filter { it["provides"].str()?.contains("server") == true }
        var lastError: Throwable? = null
        for (server in servers) {
            val token = server["accessToken"].str() ?: cfg.plexToken
            val connections = (server["connections"] as? JsonArray).orEmpty().map { it.jsonObject }
                .sortedBy { if (it["local"]?.jsonPrimitive?.content == "true") 0 else 1 }
            for (c in connections) {
                val uri = c["uri"].str() ?: continue
                val result = runCatching {
                    val container = http.getJson("$uri/library/onDeck", headers(cfg.plexClientId, token)).jsonObject["MediaContainer"]!!.jsonObject
                    (container["Metadata"] as? JsonArray).orEmpty().take(20).map { m ->
                        val o = m.jsonObject
                        val show = o["grandparentTitle"].str()
                        val art = o["art"].str() ?: o["grandparentArt"].str() ?: o["thumb"].str()
                        FeaturedItem(
                            id = o["ratingKey"].str() ?: o["key"].str() ?: "",
                            title = show ?: o["title"].str() ?: "",
                            subtitle = if (show != null) o["title"].str() else o["year"].str(),
                            description = o["summary"].str(),
                            image = art?.let { "$uri$it?X-Plex-Token=$token" },
                            packages = listOf("com.plexapp.android"),
                        )
                    }
                }
                result.onSuccess { return@withContext FeaturedFeed("Continue Watching on Plex", it) }
                lastError = result.exceptionOrNull()
            }
        }
        throw lastError ?: IllegalStateException("No reachable Plex server")
    }
}

/** Opens an item: its deep link in the first capable app, else the first installed app. */
fun FeaturedItem.open(context: Context): Boolean {
    val pm = context.packageManager
    val installed = packages.filter { pm.getLaunchIntentForPackage(it) != null || pm.getLeanbackLaunchIntentForPackage(it) != null }
    // TV rows hand over full intents ("intent:…#Intent;…;end").
    link?.takeIf { it.startsWith("intent:") }?.let { uri ->
        runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.getOrNull()?.let { intent ->
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
    }
    link?.takeIf { !it.startsWith("intent:") }?.let { uri ->
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (pkg in installed) {
            val targeted = Intent(view).setPackage(pkg)
            if (targeted.resolveActivity(pm) != null && runCatching { context.startActivity(targeted) }.isSuccess) return true
        }
        if (view.resolveActivity(pm) != null && runCatching { context.startActivity(view) }.isSuccess) return true
    }
    for (pkg in installed) {
        val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: continue
        if (runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
    }
    return false
}

/**
 * JustWatch's popular titles for one streaming service, with the service's own Android TV deep link per
 * title (Netflix, Prime Video, Disney+…): the shelf for apps that publish nothing to the TV themselves.
 * JustWatch's public GraphQL API is unofficial, so failures fall back to the default source.
 */
object JustWatch : FeaturedSource {
    class Service(val code: String, val name: String, val packages: List<String>)

    val services = listOf(
        Service("nfx", "Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient")),
        Service("amp", "Prime Video", listOf("com.amazon.firebat", "com.amazon.avod", "com.amazon.amazonvideo.livingroom")),
        Service("dnp", "Disney+", listOf("com.disney.disneyplus")),
        Service("atp", "Apple TV+", listOf("com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv")),
        Service("mxx", "Max", listOf("com.wbd.stream", "com.hbo.hbonow", "com.hbo.max.android.tv")),
        Service("hlu", "Hulu", listOf("com.hulu.livingroomplus", "com.hulu.plus")),
    )

    private const val IMAGES = "https://images.justwatch.com"

    private val genres = mapOf(
        "act" to "Action", "ani" to "Animation", "cmy" to "Comedy", "crm" to "Crime", "doc" to "Documentary",
        "drm" to "Drama", "fml" to "Family", "fnt" to "Fantasy", "hst" to "History", "hrr" to "Horror",
        "msc" to "Music", "rma" to "Romance", "scf" to "Sci-Fi", "spt" to "Sport", "trl" to "Thriller",
        "war" to "War", "wsn" to "Western", "rly" to "Reality", "eur" to "European",
    )

    /** JustWatch's Prime links target Amazon's Android TV app; on Fire TV, Prime Video is com.amazon.firebat. */
    fun forFireTv(link: String): String = link.replace("package=com.amazon.amazonvideo.livingroom", "package=com.amazon.firebat")

    private const val QUERY = """query P(${'$'}country: Country!, ${'$'}first: Int!, ${'$'}filter: TitleFilter, ${'$'}pkgs: [String!]) {
  popularTitles(country: ${'$'}country, first: ${'$'}first, filter: ${'$'}filter) { edges { node {
    id objectType
    content(country: ${'$'}country, language: "en") {
      title originalReleaseYear runtime shortDescription ageCertification externalIds { imdbId }
      genres { shortName }
      backdrops(profile: S1920, format: JPG) { backdropUrl }
    }
    offers(country: ${'$'}country, platform: ANDROID_TV, filter: {packages: ${'$'}pkgs}) { deeplinkURL(platform: ANDROID_TV) standardWebURL }
  } } }
}"""

    override suspend fun load(http: OkHttpClient, cfg: FeaturedConfig) = withContext(Dispatchers.IO) {
        val service = services.firstOrNull { it.code == cfg.justWatchPackage } ?: error("No JustWatch service")
        val country = java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
        val body = kotlinx.serialization.json.buildJsonObject {
            put("query", kotlinx.serialization.json.JsonPrimitive(QUERY))
            put("variables", kotlinx.serialization.json.buildJsonObject {
                put("country", kotlinx.serialization.json.JsonPrimitive(country))
                put("first", kotlinx.serialization.json.JsonPrimitive(15))
                put("filter", kotlinx.serialization.json.buildJsonObject {
                    put("packages", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(service.code))))
                })
                put("pkgs", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(service.code))))
            })
        }.toString()
        val request = Request.Builder().url("https://apis.justwatch.com/graphql")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val root = http.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            json.parseToJsonElement(r.body.string())
        }
        val edges = root.jsonObject["data"]?.jsonObject?.get("popularTitles")?.jsonObject?.get("edges")?.jsonArray ?: error("JustWatch: no titles")
        val items = edges.mapNotNull { e ->
            val node = e.jsonObject["node"]?.jsonObject ?: return@mapNotNull null
            val c = node["content"]?.jsonObject ?: return@mapNotNull null
            val offer = node["offers"]?.jsonArray?.firstOrNull()?.jsonObject
            val backdrop = c["backdrops"]?.jsonArray?.firstOrNull()?.jsonObject?.get("backdropUrl").str() ?: return@mapNotNull null
            FeaturedItem(
                id = "jw:${node["id"].str()}",
                title = c["title"].str() ?: return@mapNotNull null,
                description = c["shortDescription"].str(),
                image = IMAGES + backdrop,
                // The official title treatment, from Stremio's metahub by IMDb id (keyless; a few titles have none).
                logo = c["externalIds"]?.jsonObject?.get("imdbId").str()?.let { "https://images.metahub.space/logo/medium/$it/img" },
                link = offer?.get("deeplinkURL").str()?.let(::forFireTv) ?: offer?.get("standardWebURL").str(),
                packages = service.packages,
                year = c["originalReleaseYear"].str()?.toIntOrNull(),
                rating = c["ageCertification"].str(),
                genre = c["genres"]?.jsonArray?.firstNotNullOfOrNull { genres[it.jsonObject["shortName"].str()] },
                durationMin = c["runtime"].str()?.toIntOrNull(),
            )
        }
        check(items.isNotEmpty()) { "JustWatch: no titles for ${service.name}" }
        FeaturedFeed("Popular on ${service.name}", items)
    }
}
