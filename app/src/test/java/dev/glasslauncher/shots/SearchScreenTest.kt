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
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
        // The field takes focus a frame after the screen opens.
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        shownModel = model
        return model
    }

    private fun press(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private var shownModel: SearchModel? = null
    /** The query (the model's: the field and the row both show it, which the field test checks). */
    private fun text(tag: String): String? =
        if (tag == "search-query") shownModel!!.query.value
        else compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("")
    /** Types into the system field (the keyboard itself is the device's, not under test). */
    private fun type(s: String) {
        compose.onNodeWithTag("search-field").performTextInput(s)
        compose.waitForIdle()
    }

    @Test fun searchHasNoKeyboardOfItsOwnAndTypingFiltersTheResultsLive() {
        show()
        assertTrue("a normal text field opens the device's keyboard: no key grid", exists("search-field") && compose.onAllNodes(hasTestTag("search-keyboard")).fetchSemanticsNodes().isEmpty())
        type("ne")
        assertEquals("the field shows what was typed", "ne", compose.onNodeWithTag("search-field").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertEquals("ne", text("search-query"))
        assertTrue(exists("search-result:app:p.netflix") && exists("search-result:app:p.netgear") && exists("search-result:title::t1"))
        assertTrue("Plex does not match ne", !exists("search-result:app:p.plex"))
        compose.onNodeWithTag("search-field").performTextReplacement("n"); compose.waitForIdle()
        assertEquals("n", text("search-query"))
        assertTrue("n matches Plex? no word starts with n", !exists("search-result:app:p.plex"))
        // The Movies tab keeps only movies: the apps go.
        press("search-filter:Movies")
        assertTrue(!exists("search-result:app:p.netflix") && exists("search-result:title::t1"))
        press("search-filter:Top")
        assertTrue(exists("search-result:app:p.netflix"))
    }

    @Test fun theFieldOpensWithTheScreenAndTheRowReturnsOnceTypingEnds() {
        show()
        assertTrue("typing starts at once", exists("search-field") && !exists("search-row"))
        compose.onNodeWithTag("search-field").performImeAction()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        assertTrue("the keyboard's Search key ends typing: the query sits in a row again", !exists("search-field") && exists("search-row"))
        press("search-row")
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        assertTrue("Select on the row types again", exists("search-field"))
    }

    @Test fun noResultsSaysSoInTvOSsWordsWithTheKeyboardStillUp() {
        show()
        type("zq")
        assertTrue(exists("search-notice") && (exists("search-field") || exists("search-row")))
        val all = compose.onAllNodes(hasTestTag("search-notice")).fetchSemanticsNodes().single()
        val texts = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) { n.config.getOrNull(SemanticsProperties.Text)?.let { texts += it.joinToString("") }; n.children.forEach(::walk) }
        walk(all)
        assertEquals(listOf(SearchText.NO_RESULTS, SearchText.noResults("zq")), texts)
        assertEquals("There were no results for \"zq\" in Apple TV app. Try a new search.", SearchText.noResults("zq"))
    }

    @Test fun anUnreachableCinemetaSaysSearchNotAvailable() {
        show(remote = { throw java.io.IOException("offline") })
        type("zq")
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
        type("net")
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("search-results"))
    }

    @Test fun goldenEmptyWithRecents() {
        RecentSearches.write(ApplicationProvider.getApplicationContext(), listOf("the last of us", "plex", "netflix"))
        show()
        compose.onRoot().captureRoboImage(shot("search-empty"))
    }

    @Test fun everyResultNamesItsSource() {
        show(remote = { listOf(item("Netcast", "tt5", "Movie", 2020)) })
        type("net")
        compose.waitForIdle()
        val sources = compose.onAllNodes(hasTestTag("search-source"), useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue("each result shows its source ($sources)", sources >= 3)
    }
}
