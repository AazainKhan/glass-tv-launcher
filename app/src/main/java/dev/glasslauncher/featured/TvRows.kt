package dev.glasslauncher.featured

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Other apps' TV rows from the system TV provider: the Watch Next list and the preview channels apps
 * publish (Stremio's "Continue Watching", Spotify, VLC…). Reading other apps' rows needs
 * ACCESS_ALL_EPG_DATA, which only a privileged system app gets (Settings › Root › System App).
 */
object TvRows {
    private const val PERMISSION = "com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA"
    private val WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program")
    private val CHANNELS = Uri.parse("content://android.media.tv/channel")
    private val PREVIEW = Uri.parse("content://android.media.tv/preview_program")
    private val CONTINUE = Regex("continue|resume|watch next|recently watched|keep watching|up next", RegexOption.IGNORE_CASE)

    fun available(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    // No "browsable" filter: on Android TV the launcher decides which preview channels are browsable,
    // and Fire OS's launcher never marks any, so every app's channels read as hidden.

    /** Apps that publish at least one preview channel (cheap; for the "Focused app" shelf). */
    suspend fun packagesWithRows(context: Context): Set<String> = if (!available(context)) emptySet() else withContext(Dispatchers.IO) {
        query(context, CHANNELS, arrayOf("package_name"), "type=?", arrayOf("TYPE_PREVIEW")) { it.str("package_name") }.filterNotNull().toSet()
    }

    /** What you were watching, newest first: Watch Next, then apps' own "Continue watching" rows. */
    suspend fun continueWatching(context: Context): FeaturedFeed = withContext(Dispatchers.IO) {
        check(available(context)) { "Turn on Settings › Root › System App to read other apps' rows" }
        val watchNext = query(context, WATCH_NEXT, null, null, null, "last_engagement_time_utc_millis DESC") { it.item() }
        val channels = query(context, CHANNELS, arrayOf("_id", "display_name"), "type=?", arrayOf("TYPE_PREVIEW")) { c ->
            c.long("_id") to (c.str("display_name") ?: "")
        }.filter { CONTINUE.containsMatchIn(it.second) }.map { it.first }
        val rows = channels.flatMap { id -> previewItems(context, id) }
        FeaturedFeed("Continue Watching", (watchNext + rows).filterNotNull().distinctBy { it.packages.firstOrNull() + it.title }.take(12))
    }

    /** One app's rows, in its own order: what tvOS shows in the top shelf when that app is focused. */
    suspend fun appRow(context: Context, pkg: String, label: String): FeaturedFeed = withContext(Dispatchers.IO) {
        check(available(context)) { "Turn on Settings › Root › System App to read other apps' rows" }
        val channels = query(context, CHANNELS, arrayOf("_id", "display_name"), "package_name=? AND type=?", arrayOf(pkg, "TYPE_PREVIEW")) { c ->
            c.long("_id") to (c.str("display_name") ?: "")
        }.sortedByDescending { CONTINUE.containsMatchIn(it.second) }
        val items = CardShape.uniform(channels.flatMap { (id, _) -> previewItems(context, id) }.filterNotNull().distinctBy { it.title }).take(12)
        FeaturedFeed(channels.firstOrNull()?.second?.ifBlank { null } ?: label, items)
    }

    private fun previewItems(context: Context, channelId: Long) =
        query(context, PREVIEW, null, "channel_id=?", arrayOf(channelId.toString()), "weight DESC") { it.item() }

    private fun Cursor.item(): FeaturedItem? {
        val pkg = str("package_name") ?: return null
        val title = str("title") ?: return null
        val season = str("season_display_number"); val episode = str("episode_display_number")
        val episodeTitle = str("episode_title")
        val subtitle = listOfNotNull(
            if (season != null && episode != null) "S$season E$episode" else null,
            episodeTitle,
        ).joinToString(" · ").ifBlank { null }
        // Landscape art suits the full-screen backdrop; portrait posters fall back to the thumbnail.
        val aspect = int("poster_art_aspect_ratio")
        val poster = str("poster_art_uri"); val thumb = str("thumbnail_uri")
        val image = if (aspect in 0..2) poster ?: thumb else thumb ?: poster
        // The columns TvContract defines for details and progress (Watch Next and preview programs alike).
        val durationMs = long("duration_millis")
        val positionMs = long("last_playback_position_millis")
        return FeaturedItem(
            id = "tv:$pkg:${long("_id")}",
            title = title,
            subtitle = subtitle,
            // Spotify repeats the title as its description; show nothing rather than the title twice.
            description = str("short_description")?.takeUnless { it.equals(title, ignoreCase = true) },
            image = image,
            link = str("intent_uri"),
            packages = listOf(pkg),
            year = str("release_date")?.take(4)?.toIntOrNull(),
            rating = str("content_rating")?.substringAfterLast('/')?.substringAfterLast('_'),
            genre = str("genre")?.split(',')?.firstOrNull()?.trim()?.lowercase()?.replaceFirstChar { it.uppercase() },
            durationMin = (durationMs / 60_000).toInt().takeIf { it > 0 },
            episode = if (season != null && episode != null) "S$season, E$episode" else null,
            progress = if (durationMs > 0 && positionMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null,
            // The art shown is the poster (its own aspect) unless it fell back to the thumbnail (16:9).
            aspect = if (image == poster) CardShape.fromTvContract(aspect) else null,
        )
    }

    private fun <T> query(context: Context, uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String? = null, map: (Cursor) -> T): List<T> =
        runCatching {
            context.contentResolver.query(uri, projection, selection, args, order)?.use { c -> buildList { while (c.moveToNext()) add(map(c)) } }
        }.getOrNull().orEmpty()

    private fun Cursor.str(col: String): String? = getColumnIndex(col).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }?.takeIf { it.isNotBlank() }
    private fun Cursor.long(col: String): Long = getColumnIndex(col).takeIf { it >= 0 }?.let { getLong(it) } ?: 0L
    private fun Cursor.int(col: String): Int = getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) } ?: -1
}
