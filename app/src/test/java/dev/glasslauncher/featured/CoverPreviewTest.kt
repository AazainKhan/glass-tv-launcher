package dev.glasslauncher.featured

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.shots.TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P79b: Spotify's resting Top Shelf is the carousel itself, above the tray, handing over to the full-screen flow. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CoverPreviewTest {
    @get:Rule val compose = createComposeRule()

    private val feed = FeaturedFeed("Made for You", List(9) { FeaturedItem(id = "c$it", title = "Album $it", subtitle = "Artist $it", aspect = 1f) })
    private var expand by mutableFloatStateOf(0f)

    private fun show() {
        compose.setContent { CoverPreview(feed, index = 4, expand = { expand }, restCentreY = 130.dp) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
    }

    @Test fun atRestItIsTheCarouselAboveTheTrayAndNotFocusable() {
        expand = 0f
        show()
        val centre = compose.onNodeWithTag("preview-cover:4").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        // The tray's top is at 260 dp: the covers (with reflections) sit above it and are smaller than the 280 dp flow.
        assertTrue("centre cover bottom ${centre.bottom / density} dp should be above the tray (260 dp)", centre.bottom / density <= 260f)
        assertTrue("it is the resting size, smaller than the full-screen cover (${centre.width / density} dp)", centre.width / density < 280f * 0.7f)
        // The flow's own focus handling and label are not there.
        assertEquals(0, compose.onAllNodesWithTagCount("cover-flow-label"))
        assertEquals(0, compose.onAllNodesWithTagCount("cover-title"))
    }

    @Test fun asTheFlowOpensItSettlesIntoTheFullScreenPose() {
        expand = 1f
        show()
        val density = compose.density.density
        val centre = compose.onNodeWithTag("preview-cover:4").fetchSemanticsNode().boundsInRoot
        assertEquals("full size at expand = 1", 280f, centre.width / density, 3f)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
