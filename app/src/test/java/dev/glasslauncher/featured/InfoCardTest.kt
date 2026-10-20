package dev.glasslauncher.featured

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P231: the info card is a contained card with a margin from every screen edge, never full height, whatever it holds. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InfoCardTest {
    @get:Rule val compose = createComposeRule()

    private fun item() = FeaturedItem(id = "t1", title = "A very long running series", subtitle = null, description = "A synopsis. ".repeat(40), image = null, packages = emptyList())

    private fun tall() = ShelfDetails(
        cast = (1..6).map { Person("Actor Number $it", "Role $it") },
        directors = listOf("Director One", "Director Two"), writers = listOf("Writer One"), country = "Country", awards = "Many awards ".repeat(10),
        seasonList = (1..8).map { s -> Season(s, (1..12).map { e -> Episode(e, "Episode $e", "An overview that runs on. ".repeat(6)) }) },
        type = "series", genres = listOf("Drama"), runtimeMin = 50,
    )

    private fun check(details: ShelfDetails?) {
        compose.setContent { Box(Modifier.size(960.dp, 540.dp)) { InfoCard(item(), details, onClose = {}) } }
        compose.waitForIdle()
        val d = compose.density.density
        val card = compose.onNodeWithTag("shelf-info-card").fetchSemanticsNode().boundsInRoot
        val margin = INFO_CARD_MARGIN.value * d
        val w = 960 * d; val h = 540 * d
        assertTrue("clear of the top: ${card.top} vs $margin", card.top >= margin - 1)
        assertTrue("clear of the bottom: ${card.bottom} vs ${h - margin}", card.bottom <= h - margin + 1)
        assertTrue("clear of the left", card.left >= margin - 1)
        assertTrue("clear of the right", card.right <= w - margin + 1)
    }

    @Test fun aLongCardIsContainedWithMarginsAndScrollsInside() = check(tall())

    @Test fun aShortCardHasTheMarginsToo() = check(ShelfDetails())

    @Test fun whileLoadingToo() = check(null)

    /** P231 on the stick: Down at the end of the card's scroll bubbled to the shelf, closed the card and left full screen. */
    @Test fun directionKeysNeverLeaveTheCardNotEvenAtTheEndOfItsScroll() {
        var closed = 0
        var outside = 0
        compose.setContent {
            Box(Modifier.size(960.dp, 540.dp).onKeyEvent { e -> if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) outside++; false }) {
                InfoCard(item(), tall(), onClose = { closed++ })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("shelf-info-card").assertIsFocused()   // else the keys below would go nowhere and the test prove nothing
        repeat(12) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }; compose.waitForIdle(); compose.mainClock.advanceTimeBy(400) }
        repeat(3) { compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }; compose.waitForIdle() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertTrue("no direction key reached whatever sits behind the card ($outside did)", outside == 0)
        assertTrue("and the card did not close", closed == 0)
        compose.onNodeWithTag("shelf-info-card").assertExists()
    }
}
