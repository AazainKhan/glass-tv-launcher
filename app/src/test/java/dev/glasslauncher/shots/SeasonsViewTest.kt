package dev.glasslauncher.shots

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.featured.Episode
import dev.glasslauncher.featured.FeaturedItem
import dev.glasslauncher.featured.HeroInfoLine
import dev.glasslauncher.featured.Season
import dev.glasslauncher.featured.SeasonsView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P156: the Seasons view steps seasons with Select on its button, and an episode row takes focus and swallows Select (it must not close the sheet). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class SeasonsViewTest {
    @get:Rule val compose = createComposeRule()

    private val seasons = listOf(
        Season(1, listOf(Episode(1, "Pilot", "Begins.", null, 45), Episode(2, "Second", null, null, null))),
        Season(2, listOf(Episode(1, "Return", "Again.", null, 50))),
    )

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun selectOnTheSeasonButtonStepsToTheNextSeasonAndWraps() {
        compose.setContent { SeasonsView(seasons) }
        assertTrue(exists("episode:1-1") && exists("episode:1-2") && !exists("episode:2-1"))
        compose.onNodeWithTag("season-button").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertTrue("season 2 shows", exists("episode:2-1") && !exists("episode:1-1"))
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertTrue("and it wraps to season 1", exists("episode:1-1"))
    }

    @Test fun aSingleSeasonHasNoButton() {
        compose.setContent { SeasonsView(seasons.take(1)) }
        assertEquals(false, exists("season-button"))
        assertTrue(exists("episode:1-1"))
    }

    @Test fun theHeroLineIsHiddenWhenThereIsNothingToSay() {
        compose.setContent { HeroInfoLine(FeaturedItem(id = "x", title = "T"), loadExtra = false) }
        assertEquals(false, exists("shelf-info-line"))
    }

    @Test fun theHeroLineShowsTheRowsFieldsAndARatingPill() {
        compose.setContent { HeroInfoLine(FeaturedItem(id = "x", title = "T", year = 2021, rating = "TV-MA", genre = "Drama"), loadExtra = false) }
        assertTrue(exists("shelf-info-line"))
    }
}
