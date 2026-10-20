package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P231 on the stick: opened from the ⓘ button, the info card takes focus, and Down keeps its keys until Back instead of leaving full screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class InfoCardKeysTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    @Test fun theCardTakesFocusWhenOpenedFromTheInfoButtonAndDownNeverLeavesIt() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            // Up expands the shelf, Up again reaches Play, Right is More Info, Select opens the card.
            compose.press(Button.Up, Button.Up, Button.Right, Button.Select)
            compose.waitForTag("shelf-info-sheet")
            compose.settle()
            assertEquals("the card has focus as it opens", "shelf-info-card", compose.focused())
            repeat(6) { compose.press(Button.Down); compose.settle() }
            assertEquals("Down x6 stayed inside the card", "shelf-info-card", compose.focused())
            compose.press(Button.Back)
            compose.settle()
            assertEquals("Back closed it and focus returned to the info button", "shelf-info", compose.focused())
        }
    }
}
