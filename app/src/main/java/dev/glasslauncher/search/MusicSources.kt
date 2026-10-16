package dev.glasslauncher.search

import android.content.Context
import android.content.Intent
import android.net.Uri

/** A music search to run in one app: its package and the public search links to try, in order. */
data class MusicLink(val pkg: String, val uris: List<String>)

/**
 * The music apps search knows (P171): Spotify, YouTube Music and YouTube. Their results come from what they publish on the TV rows (a title that
 * matches, opened like any row) and from a "Search ... on <app>" row that runs the app's own search through its public link, sent only to that
 * app (setPackage), so nothing opens anywhere else. A link the app does not handle falls through to the next, then to a capsule.
 */
object MusicSources {
    class Source(val id: String, val label: String, val packages: List<String>, val links: (String) -> List<String>)

    private fun enc(q: String) = java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20")

    val all = listOf(
        Source("spotify", "Spotify", listOf("com.spotify.tv.android", "com.spotify.music"),
            { q -> listOf("spotify:search:${enc(q)}", "https://open.spotify.com/search/${enc(q)}") }),
        Source("ytmusic", "YouTube Music", listOf("com.google.android.youtube.tvmusic", "com.google.android.apps.youtube.music"),
            { q -> listOf("https://music.youtube.com/search?q=${enc(q)}") }),
        Source("youtube", "YouTube", listOf("com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.liskovsoft.smarttubetv", "com.google.android.youtube"),
            { q -> listOf("https://www.youtube.com/results?search_query=${enc(q)}") }),
    )

    fun ofPackage(pkg: String): Source? = all.firstOrNull { pkg in it.packages }

    /** Runs the search inside its app; false when none of the links is handled (the caller says so). */
    fun open(context: Context, link: MusicLink): Boolean {
        for (uri in link.uris) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(link.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
