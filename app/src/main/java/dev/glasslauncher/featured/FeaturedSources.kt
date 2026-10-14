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
    /** The app that published this row (the TV provider's package_name); null for titles Glass fetched itself. */
    val owner: String? = null,
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
    /** "Movie" or "TV Show" when the source says (search results; the info line reads it from Cinemeta otherwise). */
    val type: String? = null,
) {
    /**
     * The hero's info line in tvOS's order (P156): kind · genre · genre · year · episode · runtime. [extra] fills what the row lacks
     * (Cinemeta, matched by IMDb id or title and year); anything missing is left out, never a blank. The rating is not in it: it is
     * its own pill after the line ([ratingPill]).
     */
    fun heroLine(extra: ShelfDetails? = null): String? {
        val minutes = (durationMin ?: extra?.runtimeMin)?.takeIf { it > 0 }
        val duration = minutes?.let { m -> if (m >= 60) "${m / 60} hr" + (if (m % 60 > 0) " ${m % 60} min" else "") else "$m min" }
        val genres = (if (genre != null) listOf(genre) else extra?.genres.orEmpty()).take(2)
        val parts = listOfNotNull(extra?.type) + genres + listOfNotNull(year?.toString() ?: extra?.releaseYear(), episode, duration)
        return parts.joinToString(" · ").ifEmpty { null }
    }

    /** The age rating as the pill after the info line, or null. */
    fun ratingPill(): String? = rating?.takeIf { it.isNotBlank() }

    /** The details line under the title, in tvOS's order: rating · year · episode · duration · genre. */
    fun metaLine(): String? {
        val duration = durationMin?.takeIf { it > 0 }?.let { m -> if (m >= 60) "${m / 60} hr" + (if (m % 60 > 0) " ${m % 60} min" else "") else "$m min" }
        val parts = listOfNotNull(rating, year?.toString(), episode, duration, genre)
        return if (parts.isEmpty()) subtitle else parts.joinToString(" · ")
    }
}

/** One headed row of the sectioned shelf (tvOS 27's Top Shelf sections). */
@Serializable
data class FeaturedSection(val heading: String, val items: List<FeaturedItem>)

/**
 * What the shelf shows. [items] is every title once, in shelf order: the backdrop, slideshow, Play and More Info work
 * from it. A source with several headed rows also sets [sections] (each title may repeat across them); every other
 * feed leaves it empty and is one row under [heading].
 */
data class FeaturedFeed(val heading: String, val items: List<FeaturedItem>, val sections: List<FeaturedSection> = emptyList())

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
        FeaturedSourceId.Addon -> null // the add-on's provider, through the repository
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

/** The Cinemeta meta of [item] (cached by IMDb id), or null when its link has none. Blocking: call off the main thread. */
/** A title as Cinemeta spells it, for matching: lower case, no punctuation or accents' marks, single spaces. */
internal fun normTitle(t: String): String =
    java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

/**
 * The Cinemeta search hit that is [title] (P131): the name must match exactly after [normTitle], and when the item has a
 * [year] the hit's first year must be within one of it, so a remake or a same-named show doesn't lend its cast. Returns
 * the type and IMDb id, or null.
 */
internal fun pickCinemetaMatch(metas: List<JsonObject>, title: String, year: Int?): Pair<String, String>? {
    val want = normTitle(title).takeIf { it.isNotEmpty() } ?: return null
    return metas.firstNotNullOfOrNull { o ->
        val id = o["imdb_id"].str() ?: o["id"].str() ?: return@firstNotNullOfOrNull null
        if (!Regex("tt\\d+").matches(id)) return@firstNotNullOfOrNull null
        if (normTitle(o["name"].str() ?: return@firstNotNullOfOrNull null) != want) return@firstNotNullOfOrNull null
        val first = Regex("\\d{4}").find(o["releaseInfo"].str().orEmpty())?.value?.toIntOrNull()
        if (year != null && first != null && kotlin.math.abs(first - year) > 1) return@firstNotNullOfOrNull null
        (o["type"].str() ?: "movie") to id
    }
}

private val titleLookup = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<String>>()

/** The Info sheet's details: by IMDb link, else (Prime Video's rows carry only an Amazon deep link) by title (P131). */
internal fun cinemetaForInfo(item: FeaturedItem, http: OkHttpClient): JsonObject? = cinemetaFor(item, http) ?: cinemetaByTitle(item, http)

private fun cinemetaByTitle(item: FeaturedItem, http: OkHttpClient): JsonObject? {
    val key = normTitle(item.title) + "|" + (item.year ?: "")
    val cached = titleLookup[key]
    val found = if (cached != null) cached.orElse(null) else {
        val q = java.net.URLEncoder.encode(item.title, "UTF-8").replace("+", "%20")
        val metas = listOf("movie", "series").flatMap { t ->
            runCatching { (http.getJson("https://v3-cinemeta.strem.io/catalog/$t/top/search=$q.json").jsonObject["metas"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonObject } }.getOrNull().orEmpty()
        }
        val hit = pickCinemetaMatch(metas, item.title, item.year)
        // Only an answer is remembered: a failed search (no network) must be tried again.
        if (metas.isNotEmpty()) titleLookup[key] = java.util.Optional.ofNullable(hit?.let { "${it.first}/${it.second}" })
        hit?.let { "${it.first}/${it.second}" }
    } ?: return null
    return cinemetaFor(item.copy(link = "stremio:///detail/$found"), http)
}

internal fun cinemetaFor(item: FeaturedItem, http: OkHttpClient): JsonObject? {
    val m = item.link?.let { Regex("(movie|series)/(tt\\d+)").find(it) } ?: return null
    // Stremio's rows sometimes label series as movies, so try the other type too.
    val types = listOf(m.groupValues[1], if (m.groupValues[1] == "movie") "series" else "movie")
    return cinemetaCache[m.groupValues[2]] ?: types.firstNotNullOfOrNull { t ->
        runCatching { http.getJson("https://v3-cinemeta.strem.io/meta/$t/${m.groupValues[2]}.json").jsonObject["meta"] as? JsonObject }.getOrNull()
    }?.also { cinemetaCache[m.groupValues[2]] = it }
}

private fun enrich(item: FeaturedItem, imdb: Regex, http: OkHttpClient): FeaturedItem {
    val o = cinemetaFor(item, http) ?: return item
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
    private val PACKAGES = listOf(
        "com.teamsmart.videomanager.tv", "com.google.android.youtube.tv", "com.amazon.firetv.youtube",
        "io.gh.reisxd.tizentube.cobalt",
    )
    private val channelId = Regex("UC[A-Za-z0-9_-]{22}")
    private const val MAX_CHANNELS = 10
    private const val MAX_FEED_BYTES = 512L * 1024

    /** The channel ids in [text] (ids or channel URLs, separated by anything), without repeats. */
    fun channelIds(text: String): List<String> = channelId.findAll(text).map { it.value }.distinct().toList()

    override suspend fun load(http: OkHttpClient, cfg: FeaturedConfig) = withContext(Dispatchers.IO) {
        val channels = channelIds(cfg.youtubeChannels)
        require(channels.isNotEmpty() || cfg.youtubeKey.isNotBlank()) { "Add YouTube channels or an API key in Settings" }
        val client = http.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        val perChannel = kotlinx.coroutines.coroutineScope {
            channels.take(MAX_CHANNELS).map { id ->
                async {
                    try {
                        parseFeed(client.getText("https://www.youtube.com/feeds/videos.xml?channel_id=$id"))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("YouTube", "channel $id failed: ${e.javaClass.simpleName}")
                        emptyList()
                    }
                }
            }.map { it.await() }
        }
        val trending = if (cfg.youtubeKey.isBlank()) emptyList() else runCatching { trending(http, cfg) }.getOrDefault(emptyList())
        val feed = ShelfSections.youtube(perChannel, trending)
        check(feed.items.isNotEmpty()) { "Couldn't load YouTube videos" }
        feed
    }

    private fun OkHttpClient.getText(url: String): String =
        newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            val src = r.body.source()
            src.request(MAX_FEED_BYTES + 1)
            check(src.buffer.size <= MAX_FEED_BYTES) { "Feed too large" }
            src.buffer.readUtf8()
        }

    private fun trending(http: OkHttpClient, cfg: FeaturedConfig): List<FeaturedItem> {
        val url = "https://www.googleapis.com/youtube/v3/videos".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("chart", "mostPopular")
            .addQueryParameter("regionCode", cfg.youtubeRegion)
            .addQueryParameter("maxResults", "20")
            .addQueryParameter("key", cfg.youtubeKey)
            .build().toString()
        return http.getJson(url).jsonObject["items"]!!.jsonArray.mapNotNull { v ->
            val o = v.jsonObject
            val id = o["id"].str() ?: return@mapNotNull null
            val s = o["snippet"]!!.jsonObject
            val thumbs = s["thumbnails"]?.jsonObject
            val image = listOf("maxres", "standard", "high").firstNotNullOfOrNull { thumbs?.get(it)?.jsonObject?.get("url").str() }
            video(id, s["title"].str() ?: "", s["channelTitle"].str(), image, null)
        }
    }

    private fun video(id: String, title: String, channel: String?, image: String?, description: String?) = FeaturedItem(
        id = id, title = title, subtitle = channel, description = description, image = image,
        link = "https://www.youtube.com/watch?v=$id", packages = PACKAGES,
    )

    /** The videos of a channel's public RSS feed, each with its publish time (ISO, sorts as text); a malformed feed gives none. */
    fun parseFeed(xml: String): List<Pair<FeaturedItem, String>> = runCatching {
        // Channel feeds never carry a DOCTYPE; refuse one outright rather than trust parser flags.
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) return@runCatching emptyList()
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isExpandEntityReferences = false
        for ((f, v) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        )) runCatching { factory.setFeature(f, v) }
        val doc = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
        fun org.w3c.dom.Element.text(tag: String) = getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val entries = doc.getElementsByTagName("entry")
        (0 until entries.length).mapNotNull { i ->
            val e = entries.item(i) as org.w3c.dom.Element
            val id = e.text("yt:videoId") ?: return@mapNotNull null
            val title = e.text("title") ?: return@mapNotNull null
            val channel = (e.getElementsByTagName("author").item(0) as? org.w3c.dom.Element)?.text("name")
            val description = e.text("media:description")?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(240)
            // The feed's own thumbnail suits Shorts too; hq720 is only a guess when it is absent.
            val thumb = (e.getElementsByTagName("media:thumbnail").item(0) as? org.w3c.dom.Element)?.getAttribute("url")?.takeIf { it.startsWith("https://") }
            video(id, title, channel, thumb ?: "https://i.ytimg.com/vi/$id/hq720.jpg", description) to (e.text("published") ?: "")
        }
    }.getOrDefault(emptyList())
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

/**
 * Opens an item: the intent its app published (TV rows: this is what resumes playback), else its title link, else the app
 * (P140, see [ResumeIntents]). Each step falls through when it cannot run; rows from other apps only ever reach their own app.
 */
fun FeaturedItem.open(context: Context): Boolean {
    val pm = context.packageManager
    val installed = packages.filter { pm.getLaunchIntentForPackage(it) != null || pm.getLeanbackLaunchIntentForPackage(it) != null }
    for (step in ResumeIntents.steps(this, installed, context.packageName)) if (ResumeIntents.start(context, step)) return true
    // A link Glass fetched itself (Stremio, JustWatch…) may also go to any app that handles it; a row another app published may not.
    val uri = link
    if (uri != null && !ResumeIntents.isAppPublished(this) && ResumeIntents.schemeOk(ResumeIntents.schemeOf(uri))) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (view.resolveActivity(pm)?.packageName != context.packageName && view.resolveActivity(pm) != null &&
            runCatching { context.startActivity(view) }.isSuccess) return true
    }
    return false
}

/**
 * Resume (P160): opens the item like [open], then asks Glass's accessibility service for the one key that starts playback (see
 * [ResumePlayback]). Without that service (APK mode, it is off) the page opens and a one-line hint says why nothing plays.
 */
fun FeaturedItem.resume(context: Context): Boolean {
    val pm = context.packageManager
    val target = owner ?: packages.firstOrNull { pm.getLaunchIntentForPackage(it) != null || pm.getLeanbackLaunchIntentForPackage(it) != null }
    val opened = open(context)
    if (!opened || target == null) return opened
    val service = dev.glasslauncher.system.RemoteKeysService.instance
    if (service != null) service.autoPlay(ResumePlayback.Request(target, android.os.SystemClock.uptimeMillis()))
    else dev.glasslauncher.system.RemoteNotice.show(context, "Turn on Glass remote keys in Settings to resume playing automatically.")
    return true
}

/**
 * JustWatch's popular titles for one streaming service, with the service's own Android TV deep link per
 * title (Netflix, Prime Video, Disney+…): the shelf for apps that publish nothing to the TV themselves.
 * JustWatch's public GraphQL API is unofficial, so failures fall back to the default source.
 */
object JustWatch : FeaturedSource {
    /** [code] is JustWatch's package short name in the US; [names] are the service's names as JustWatch lists it per country (it differs: Prime Video is "amp" in the US and "prv" in Canada). */
    class Service(val code: String, val name: String, val packages: List<String>, val names: List<String> = emptyList())

    val services = listOf(
        Service("nfx", "Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient"), listOf("Netflix")),
        Service("amp", "Prime Video", listOf("com.amazon.firebat", "com.amazon.avod", "com.amazon.amazonvideo.livingroom"), listOf("Amazon Prime Video")),
        Service("dnp", "Disney+", listOf("com.disney.disneyplus"), listOf("Disney Plus", "Disney+")),
        Service("atp", "Apple TV+", listOf("com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv"), listOf("Apple TV Plus", "Apple TV+")),
        Service("mxx", "Max", listOf("com.wbd.stream", "com.hbo.hbonow", "com.hbo.max.android.tv"), listOf("Max", "HBO Max")),
        Service("hlu", "Hulu", listOf("com.hulu.livingroomplus", "com.hulu.plus"), listOf("Hulu")),
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

    /** The package short names per country, by name (lower case): fetched once per country and kept. */
    private val packageNames = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    /** The service's code in this country's catalogue (pure, tested); the US code when JustWatch lists no match. */
    fun resolveCode(listed: Map<String, String>, service: Service): String =
        service.names.firstNotNullOfOrNull { listed[it.lowercase()] } ?: service.code

    private fun packagesFor(http: OkHttpClient, country: String): Map<String, String> = packageNames[country] ?: run {
        runCatching {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive("query(${'$'}c: Country!){ packages(country: ${'$'}c, platform: WEB){ shortName clearName } }"))
                put("variables", kotlinx.serialization.json.buildJsonObject { put("c", kotlinx.serialization.json.JsonPrimitive(country)) })
            }.toString()
            val request = Request.Builder().url("https://apis.justwatch.com/graphql").post(body.toRequestBody("application/json".toMediaType())).build()
            val root = http.newCall(request).execute().use { r -> check(r.isSuccessful) { "HTTP ${r.code}" }; json.parseToJsonElement(r.body.string()) }
            root.jsonObject["data"]?.jsonObject?.get("packages")?.jsonArray.orEmpty().mapNotNull { p ->
                val o = p.jsonObject
                val clear = o["clearName"].str()?.trim()?.lowercase(); val short = o["shortName"].str()
                if (clear != null && short != null) clear to short else null
            }.toMap()
        }.getOrDefault(emptyMap()).also { if (it.isNotEmpty()) packageNames[country] = it }   // a failed lookup is retried next time
    }

    override suspend fun load(http: OkHttpClient, cfg: FeaturedConfig) = withContext(Dispatchers.IO) {
        val service = services.firstOrNull { it.code == cfg.justWatchPackage } ?: error("No JustWatch service")
        val country = java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
        // The service's code differs per country (Prime Video is "amp" in the US but "prv" in Canada, where "amp" has
        // only four titles): ask JustWatch for this country's package list and match by name (P89).
        val code = resolveCode(packagesFor(http, country), service)
        val body = kotlinx.serialization.json.buildJsonObject {
            put("query", kotlinx.serialization.json.JsonPrimitive(QUERY))
            put("variables", kotlinx.serialization.json.buildJsonObject {
                put("country", kotlinx.serialization.json.JsonPrimitive(country))
                // Many titles have no backdrop at this size and are dropped (Prime showed ~4 of 15): ask for more, keep 20.
                put("first", kotlinx.serialization.json.JsonPrimitive(FETCH))
                put("filter", kotlinx.serialization.json.buildJsonObject {
                    put("packages", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(code))))
                })
                put("pkgs", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(code))))
            })
        }.toString()
        val request = Request.Builder().url("https://apis.justwatch.com/graphql")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val root = http.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            json.parseToJsonElement(r.body.string())
        }
        val items = parse(root, service)
        check(items.isNotEmpty()) { "JustWatch: no titles for ${service.name}" }
        FeaturedFeed("Popular on ${service.name}", items)
    }

    /** Titles asked for per request, and kept (those without a backdrop are dropped, so ask for more than we keep). */
    private const val FETCH = 40
    const val KEEP = 20

    /** The popular-titles response into items (pure, tested): only titles with a backdrop, in JustWatch's order, up to [KEEP]. */
    fun parse(root: kotlinx.serialization.json.JsonElement, service: Service): List<FeaturedItem> {
        val edges = root.jsonObject["data"]?.jsonObject?.get("popularTitles")?.jsonObject?.get("edges")?.jsonArray ?: error("JustWatch: no titles")
        return edges.mapNotNull { e ->
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
        }.take(KEEP)
    }
}
