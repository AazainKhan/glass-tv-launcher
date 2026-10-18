package dev.glasslauncher.shots

import android.content.ComponentName
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.featured.FeaturedItem
import dev.glasslauncher.search.RecentSearches
import dev.glasslauncher.search.SearchModel
import dev.glasslauncher.search.SearchScreen
import dev.glasslauncher.search.SearchText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P161: the search screen types with the one-row keyboard, filters, and says what tvOS says when there is nothing. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class SearchScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    @After fun tearDown() { RecentSearches.write(ApplicationProvider.getApplicationContext(), emptyList()) }

    private fun app(label: String) = AppEntry("p.${label.lowercase()}", label, ComponentName("p.${label.lowercase()}", "M"))
    private fun item(title: String, id: String, type: String, year: Int) = FeaturedItem(id = id, title = title, type = type, year = year, image = null)

    private fun show(remote: suspend (String) -> List<FeaturedItem> = { emptyList() }): SearchModel {
        val model = SearchModel(scope, { listOf(app("Netflix"), app("Plex"), app("Netgear")) }, { listOf(item("Net Game", "t1", "Movie", 2020)) }, remote, debounceMs = 0)
        compose.setContent { Box(Modifier.fillMaxSize().background(Color(0xFF14161C))) { SearchScreen(model, active = true, onOpen = {}, askClear = {}) } }
        compose.waitForIdle()
        return model
    }

    private fun press(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("")

    @Test fun theKeyboardIsACompactGridAndTypingFiltersTheResultsLive() {
        show()
        assertTrue("every letter, the page keys, space, delete and clear are all on screen at once", ("abcdefghijklmnopqrstuvwxyz".map { "$it" } + listOf("page-abc", "page-num", "page-sym", "space", "delete", "clear")).all { exists("search-key:$it") })
        val rows = "abcdefghijklmnopqrstuvwxyz".map { c -> compose.onNodeWithTag("search-key:$c").fetchSemanticsNode().boundsInRoot.top }.distinct()
        assertTrue("letters sit in three rows of nine or fewer: ${rows.size}", rows.size == 3)
        assertTrue("and the keyboard is short enough to leave the results room", compose.onNodeWithTag("search-keyboard").fetchSemanticsNode().boundsInRoot.height < 230f * compose.density.density)
        press("search-key:n"); press("search-key:e")
        assertEquals("ne", text("search-query"))
        assertTrue(exists("search-result:app:p.netflix") && exists("search-result:app:p.netgear") && exists("search-result:title::t1"))
        assertTrue("Plex does not match ne", !exists("search-result:app:p.plex"))
        press("search-key:delete")
        assertEquals("n", text("search-query"))
        assertTrue("n matches Plex? no word starts with n", !exists("search-result:app:p.plex"))
        // The Movies tab keeps only movies: the apps go.
        press("search-filter:Movies")
        assertTrue(!exists("search-result:app:p.netflix") && exists("search-result:title::t1"))
        press("search-filter:Top")
        assertTrue(exists("search-result:app:p.netflix"))
    }

    @Test fun threePagesAbcDigitsAndMarksEachWithItsOwnKeys() {
        show()
        press("search-key:page-num")
        assertTrue(exists("search-key:1") && exists("search-key:0") && !exists("search-key:a") && !exists("search-key:."))
        press("search-key:1")
        assertEquals("1", text("search-query"))
        press("search-key:page-sym")
        assertTrue("marks are on their own page, not mixed with the digits", exists("search-key:&") && exists("search-key:/") && !exists("search-key:1"))
        press("search-key:&")
        assertEquals("1&", text("search-query"))
        press("search-key:page-abc")
        assertTrue(exists("search-key:a") && !exists("search-key:&"))
    }

    @Test fun clearEmptiesTheQueryAtOnce() {
        show()
        press("search-key:n"); press("search-key:e"); press("search-key:t")
        press("search-key:clear")
        assertEquals("Search", text("search-query"))
    }

    /** P194: the focused key grows 6%; the key at each edge of the keyboard must keep that room inside its parent (it was clipped on the left). */
    @Test fun aFocusedKeyAtAnEdgeIsNotClipped() {
        show()
        val root = compose.onNodeWithTag("search-keyboard").fetchSemanticsNode().boundsInRoot
        val grow = 0.06f
        val edgeKeys = listOf("a", "i", "j", "r", "s", "z", "page-abc", "clear")
        for (k in edgeKeys) {
            val b = compose.onNodeWithTag("search-key:$k").fetchSemanticsNode().boundsInRoot
            val room = b.width * grow / 2f
            assertTrue("$k: its growth ($room px) stays inside the keyboard: key ${b.left}..${b.right}, keyboard ${root.left}..${root.right}", b.left - room >= root.left && b.right + room <= root.right)
            assertTrue("$k keeps at least 6 dp between its growth and the keyboard's edge", b.left - root.left >= 6f * compose.density.density || b.right <= root.right - 6f * compose.density.density)
        }
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("the whole keyboard is on screen", root.left >= screen.left && root.right <= screen.right)
    }

    @Test fun noResultsSaysSoInTvOSsWordsWithTheKeyboardStillUp() {
        show()
        press("search-key:z"); press("search-key:q")
        assertTrue(exists("search-notice") && exists("search-keyboard"))
        val all = compose.onAllNodes(hasTestTag("search-notice")).fetchSemanticsNodes().single()
        val texts = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) { n.config.getOrNull(SemanticsProperties.Text)?.let { texts += it.joinToString("") }; n.children.forEach(::walk) }
        walk(all)
        assertEquals(listOf(SearchText.NO_RESULTS, SearchText.noResults("zq")), texts)
        assertEquals("There were no results for \"zq\" in Apple TV app. Try a new search.", SearchText.noResults("zq"))
    }

    @Test fun anUnreachableCinemetaSaysSearchNotAvailable() {
        show(remote = { throw java.io.IOException("offline") })
        press("search-key:z"); press("search-key:q")
        compose.waitUntil(3_000) { exists("search-notice") }
        val texts = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) { n.config.getOrNull(SemanticsProperties.Text)?.let { texts += it.joinToString("") }; n.children.forEach(::walk) }
        walk(compose.onAllNodes(hasTestTag("search-notice")).fetchSemanticsNodes().single())
        assertEquals(listOf(SearchText.UNAVAILABLE, SearchText.OFFLINE), texts)
    }

    @Test fun withNoTextItShowsRecentlySearchedAndASuggestionRunsThatSearch() {
        RecentSearches.write(ApplicationProvider.getApplicationContext(), listOf("plex", "netflix"))
        show()
        assertTrue(exists("search-recent") && exists("search-clear"))
        press("search-recent:plex")
        assertEquals("plex", text("search-query"))
        assertTrue(exists("search-result:app:p.plex"))
    }

    @Test fun goldenResults() {
        show(remote = { listOf(item("Network", "tt1", "TV Show", 2019), item("Net Worth", "tt2", "Movie", 2021)) })
        press("search-key:n"); press("search-key:e"); press("search-key:t")
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("search-results"))
    }

    @Test fun goldenEmptyWithRecents() {
        RecentSearches.write(ApplicationProvider.getApplicationContext(), listOf("the last of us", "plex", "netflix"))
        show()
        compose.onRoot().captureRoboImage(shot("search-empty"))
    }

    @Test fun noKeyClipsItsGlyphAndTheSymbolsPageHasTheMarks() {
        show()
        val clipped = mutableListOf<Char>()
        for (c in "abcdefghijklmnopqrstuvwxyz") {
            val node = compose.onNodeWithTag("search-key:$c", useUnmergedTree = true).fetchSemanticsNode()
            fun textWidth(n: androidx.compose.ui.semantics.SemanticsNode): Int? {
                val rs = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(rs)
                return rs.firstOrNull()?.size?.width ?: n.children.firstNotNullOfOrNull { textWidth(it) }
            }
            val w = textWidth(node) ?: continue
            // The glyph has room on both sides inside its key.
            if (node.boundsInRoot.width - w < 12f * compose.density.density) clipped += c
        }
        assertTrue("keys with no room around their letter: $clipped", clipped.isEmpty())
        press("search-key:page-num")
        for (m in listOf("0", "9")) assertTrue("digits page has $m", exists("search-key:$m"))
        press("search-key:page-sym")
        for (m in listOf(".", ",", "-", "_", "'", ":", "!", "?", "&", "@", "#", "(", ")", "/")) assertTrue("symbols page has $m", exists("search-key:$m"))
        press("search-key:page-abc")
        assertTrue("ABC toggles back", exists("search-key:a"))
    }

    @Test fun everyResultNamesItsSource() {
        show(remote = { listOf(item("Netcast", "tt5", "Movie", 2020)) })
        press("search-key:n"); press("search-key:e"); press("search-key:t")
        compose.waitForIdle()
        val sources = compose.onAllNodes(hasTestTag("search-source"), useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue("each result shows its source ($sources)", sources >= 3)
    }
}
