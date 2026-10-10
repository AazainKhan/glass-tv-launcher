package dev.glasslauncher.featured

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.shots.TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/** The Cover Flow view: keys, the open and exit callbacks, and one continuous glide between covers. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CoverFlowTest {
    @get:Rule val compose = createComposeRule()

    private val items = (0 until 9).map { FeaturedItem(id = "c$it", title = "Album $it", subtitle = "Artist $it", aspect = 1f) }
    private var index by mutableIntStateOf(4)
    private var opened by mutableStateOf<String?>(null)
    private var exited = 0

    private fun show() {
        val requester = FocusRequester()
        compose.setContent {
            CoverFlow(
                items = items, index = index, onIndex = { index = it },
                onOpen = { opened = it.id }, onExit = { exited++ },
                label = "Spotify", focusRequester = requester,
            )
            androidx.compose.runtime.LaunchedEffect(Unit) { requester.requestFocus() }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
    }

    private fun press(key: Key) { compose.onRoot().performKeyInput { pressKey(key) } }

    private fun centreX(i: Int): Float = compose.onNodeWithTag("cover:$i").fetchSemanticsNode().boundsInRoot.center.x

    @Test fun theCentreCoverIsCentredAndTheLabelAndTitleAreShown() {
        show()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, centreX(4), 2f)
        compose.onNodeWithTag("cover-flow-label").assertExists()
        compose.onNodeWithTag("cover-title").assertExists()
    }

    @Test fun rightAndLeftStepTheIndexAndSelectOpensAndDownExits() {
        show()
        press(Key.DirectionRight)
        assertEquals(5, index)
        press(Key.DirectionLeft); press(Key.DirectionLeft)
        assertEquals(3, index)
        press(Key.DirectionCenter)
        assertEquals("c3", opened)
        press(Key.DirectionDown)
        assertEquals(1, exited)
    }

    @Test fun twoQuickPressesAreTwoSteps() {
        show()
        // Both before the next recomposition: the second must step from the first's result.
        compose.mainClock.autoAdvance = false
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertEquals(6, index)
    }

    @Test fun theEndsDoNotStepPastTheFirstAndLastCover() {
        index = 0
        show()
        press(Key.DirectionLeft)
        assertEquals(0, index)
        index = 8
        compose.waitForIdle()
        press(Key.DirectionRight)
        assertEquals(8, index)
    }

    @Test fun aStepGlidesEveryCoverContinuouslyToItsNewPlace() {
        show()
        val before = (1..7).associateWith { centreX(it) }
        compose.mainClock.autoAdvance = false
        press(Key.DirectionRight)
        var previous = before
        var moved = false
        repeat(40) {
            compose.mainClock.advanceTimeBy(16)
            val now = (1..7).associateWith { centreX(it) }
            for (i in 1..7) {
                val step = abs(now.getValue(i) - previous.getValue(i))
                // A pitch is ~74% of a cover (~420 px at xhdpi); no frame moves a cover more than a third of it.
                assertTrue("cover $i jumped ${step}px in one frame", step < 150f)
                if (step > 0.5f) moved = true
            }
            previous = now
        }
        assertTrue("the covers moved", moved)
        // After the glide the next cover is in the centre.
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, centreX(5), 3f)
    }
}
