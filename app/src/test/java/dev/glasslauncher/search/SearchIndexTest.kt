package dev.glasslauncher.search

import android.content.ComponentName
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.featured.FeaturedItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {
    private fun app(label: String, pkg: String = "p.${label.lowercase()}") = AppEntry(pkg, label, ComponentName(pkg, "$pkg.M"))
    private fun item(title: String, id: String = title, owner: String? = null, year: Int? = null, type: String? = null) =
        FeaturedItem(id = id, title = title, owner = owner, year = year, type = type, image = "https://img/$id.jpg")

    @Test fun scoresRankExactThenPrefixThenWordThenContains() {
        assertEquals(100, SearchIndex.score("netflix", "Netflix"))
        assertEquals(80, SearchIndex.score("net", "Netflix"))
        assertEquals(60, SearchIndex.score("video", "Prime Video"))
        assertEquals(40, SearchIndex.score("lix", "Netflix"))
        assertEquals("words in any order", 30, SearchIndex.score("video prime", "Prime Video"))
        assertEquals(0, SearchIndex.score("zzz", "Netflix"))
        assertEquals("accents and punctuation do not matter", 100, SearchIndex.score("amelie", "Amélie!"))
        assertEquals(0, SearchIndex.score("", "Netflix"))
    }

    @Test fun mergeIsBestScoreFirstThenAppsBeforeTitlesAndNoDuplicates() {
        val apps = SearchIndex.apps("dis", listOf(app("Disney+"), app("Discovery"), app("Plex")))
        val titles = SearchIndex.titles("dis", listOf(item("Disenchantment", "t1", owner = "p.x"), item("Mind Disorder", "t2", owner = "p.x")))
        val remote = SearchIndex.remote("dis", listOf(item("Disenchantment", "tt1", year = 2018, type = "TV Show"), item("District 9", "tt2", year = 2009, type = "Movie")))
        val out = SearchIndex.merge(apps, titles, remote)
        assertEquals("Plex does not match", false, out.any { it.title == "Plex" })
        assertEquals("prefix matches first, apps before titles before Cinemeta, then by name",
            listOf("Discovery", "Disney+", "Disenchantment", "District 9", "Mind Disorder"), out.map { it.title })
        assertEquals("the title the user's own rows know wins over Cinemeta's same name", 1, out.count { it.title == "Disenchantment" })
        assertEquals(SearchKind.Title, out.first { it.title == "Disenchantment" }.kind)
        assertEquals(SearchKind.Movie, out.first { it.title == "District 9" }.kind)
    }

    @Test fun resultsAreCapped() {
        val many = (1..60).map { app("Alpha $it") }
        assertEquals(SearchIndex.MAX_RESULTS, SearchIndex.merge(SearchIndex.apps("alpha", many), emptyList(), emptyList()).size)
    }

    @Test fun theModelAnswersLocallyAtOnceThenAddsCinemetaOnceTheTypingRests() {
        runBlocking {
            var asked = 0
            val model = SearchModel(this, { listOf(app("Stremio")) }, { emptyList() }, { q -> asked++; listOf(item("Stremio Story", "tt9", year = 2020, type = "Movie")) }, debounceMs = 40)
            model.setQuery("s"); model.setQuery("st"); model.setQuery("stre")
            delay(15)
            assertEquals("local result at once", listOf("Stremio"), model.results.value.map { it.title })
            assertEquals("no request yet", 0, asked)
            delay(120)
            assertEquals("one request for three keys", 1, asked)
            assertEquals(listOf("Stremio", "Stremio Story"), model.results.value.map { it.title })
            assertEquals(false, model.busy.value)
            model.setQuery("")
            assertTrue(model.results.value.isEmpty() && model.empty)
        }
    }

    @Test fun aSlowAnswerForOldTextNeverReplacesANewerList() {
        runBlocking {
            val model = SearchModel(this, { listOf(app("Plex"), app("Pluto")) }, { emptyList() }, { q -> delay(80); listOf(item("Old $q", "tt$q")) }, debounceMs = 5)
            model.setQuery("pl")
            delay(30)
            model.setQuery("plex")
            delay(200)
            assertEquals("only the newer text's answer, never the old one's ('Old pl')", listOf("Plex", "Old plex"), model.results.value.map { it.title })
        }
    }
}
