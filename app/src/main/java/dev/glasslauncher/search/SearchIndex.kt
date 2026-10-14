package dev.glasslauncher.search

import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.featured.FeaturedItem
import dev.glasslauncher.featured.normTitle

/** What a search result is, in the order the results list groups them. */
enum class SearchKind { App, Title, Movie, Show }

/** One result row: a poster (an app's tile art, a title's poster), a name, a line under it, and what Select does. */
data class SearchHit(
    val kind: SearchKind,
    val key: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val score: Int,
    /** The app to launch (kind [SearchKind.App]) or the title to open, null for the other. */
    val app: AppEntry? = null,
    val item: FeaturedItem? = null,
)

/**
 * System search (P161), the pure core: which of the apps, the titles the TV rows and Top Shelf already know, and the titles Cinemeta
 * finds match a typed query, best first. Matching is on [normTitle] (case, accents and punctuation do not matter): a name that starts
 * with the query beats one with a word that starts with it, which beats one that merely contains it; words typed in any order all have to
 * match. Nothing here touches the network or Android, so it is unit-tested.
 */
object SearchIndex {
    const val MAX_RESULTS = 30

    /** 0 for no match; higher is better. Exact 100, starts-with 80, a word starts with it 60, contains 40, every word matches 30. */
    fun score(query: String, title: String): Int {
        val q = normTitle(query)
        val t = normTitle(title)
        if (q.isEmpty() || t.isEmpty()) return 0
        return when {
            t == q -> 100
            t.startsWith(q) -> 80
            t.split(' ').any { it.startsWith(q) } -> 60
            t.contains(q) -> 40
            q.split(' ').all { w -> w.isNotEmpty() && t.contains(w) } -> 30
            else -> 0
        }
    }

    fun apps(query: String, apps: List<AppEntry>): List<SearchHit> = apps.mapNotNull { a ->
        val s = score(query, a.label)
        if (s == 0) null else SearchHit(SearchKind.App, "app:${a.packageName}", a.label, null, null, s, app = a)
    }

    /** Titles the TV rows, Continue Watching and the Top Shelf already carry; [kind] says whether they are movies or shows when known. */
    fun titles(query: String, items: List<FeaturedItem>): List<SearchHit> = items.mapNotNull { i ->
        val s = score(query, i.title)
        if (s == 0) null else SearchHit(
            SearchKind.Title, "title:${i.owner ?: ""}:${i.id}", i.title,
            listOfNotNull(i.type, i.year?.toString()).joinToString(" · ").ifEmpty { null } ?: i.subtitle ?: i.episode, i.image, s, item = i,
        )
    }

    fun remote(query: String, items: List<FeaturedItem>): List<SearchHit> = items.mapNotNull { i ->
        val s = score(query, i.title)
        if (s == 0) null else SearchHit(
            if (i.type == "Movie") SearchKind.Movie else SearchKind.Show, "cinemeta:${i.id}", i.title,
            listOfNotNull(i.type, i.year?.toString()).joinToString(" · ").ifEmpty { null }, i.image, s, item = i,
        )
    }

    /**
     * Everything, best first: by score, then apps before titles before Cinemeta's, then by name. A title Cinemeta also found (same name
     * and year) is shown once, as the one the user's own rows know (it can resume). At most [MAX_RESULTS].
     */
    fun merge(apps: List<SearchHit>, titles: List<SearchHit>, remote: List<SearchHit>): List<SearchHit> {
        val known = titles.map { normTitle(it.title) }.toSet()
        val fresh = remote.filter { normTitle(it.title) !in known }
        return (apps + titles + fresh)
            .sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.kind.ordinal }.thenBy { normTitle(it.title) })
            .distinctBy { it.key }
            .take(MAX_RESULTS)
    }

    /** The segmented filter under the keyboard (tvOS: Top Results | TV Shows | Movies). */
    enum class Filter(val label: String) { Top("Top Results"), Shows("TV Shows"), Movies("Movies") }

    /** Top Results is everything (apps too); a type tab keeps only the titles of that type (a row whose type is unknown is only in Top Results). */
    fun filter(hits: List<SearchHit>, filter: Filter): List<SearchHit> = when (filter) {
        Filter.Top -> hits
        Filter.Shows -> hits.filter { it.kind == SearchKind.Show || (it.kind == SearchKind.Title && it.item?.type == "TV Show") }
        Filter.Movies -> hits.filter { it.kind == SearchKind.Movie || (it.kind == SearchKind.Title && it.item?.type == "Movie") }
    }

    /** Completion pills for the typed text: the names of the best results that begin with it (or have a word that does), at most [n]. */
    fun suggestions(query: String, hits: List<SearchHit>, n: Int = 5): List<String> {
        val q = normTitle(query)
        if (q.isEmpty()) return emptyList()
        return hits.map { it.title }.distinct().filter { t -> val nt = normTitle(t); nt.startsWith(q) || nt.split(' ').any { it.startsWith(q) } }.take(n)
    }
}
