package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P83 M2: Control Center's edit mode through the remote: enter from the Edit pill, lift, move, drop, Back saves. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcEditModeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private fun top(id: String) = compose.onNodeWithTag("cc-tile:$id").fetchSemanticsNode().boundsInRoot.top
    private fun left(id: String) = compose.onNodeWithTag("cc-tile:$id").fetchSemanticsNode().boundsInRoot.left
    private fun hints() = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("cc-edit-hints")).fetchSemanticsNodes().size

    @Test fun liftMoveDropAndBackSavesTheOrder() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            assertTrue("starts packed as today: Wi-Fi above Bluetooth", top("wifi") < top("bluetooth"))
            // Down to the Edit pill, Select: edit mode, with its hints.
            repeat(12) { if (compose.focused()?.startsWith("cc-edit") != true) compose.press(Button.Down) }
            compose.press(Button.Select)
            compose.settle()
            assertTrue("edit mode shows its hints", hints() == 1)
            // Golden: every removable tile wears its legend, the hints sit under the grid (wobble is a transform only).
            compose.onRoot().captureRoboImage(shot("cc-edit-mode"))
            // Focus is on Settings; Right goes to Wi-Fi. Select lifts it; Down moves it into the row below; Select drops.
            focusWifi()
            compose.press(Button.Select, Button.Down, Button.Select)
            compose.settle()
            // Back leaves edit mode (and saves): Control Center is still open, hints gone.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.settle()
            assertTrue("edit mode is over", hints() == 0)
            assertTrue("Wi-Fi now sits below Bluetooth (top ${top("wifi")} vs ${top("bluetooth")})", top("wifi") > top("bluetooth"))
        }
    }

    /** From Settings, Right lands on Wi-Fi or Bluetooth (the big tile sits level with both): Up gets from Bluetooth to Wi-Fi. */
    private fun focusWifi() {
        compose.press(Button.Right)
        if (compose.focused()?.startsWith("cc-tile:wifi") != true) compose.press(Button.Up)
        assertTrue("on Wi-Fi: ${compose.focused()}", compose.focused()?.startsWith("cc-tile:wifi") == true)
    }

    private fun exists(tag: String) = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun removeThenAddAControlBackFromTheGallery() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            repeat(12) { if (compose.focused()?.startsWith("cc-edit") != true) compose.press(Button.Down) }
            compose.press(Button.Select)
            compose.settle()
            // Right to Wi-Fi, Play/Pause removes it (no confirm: the gallery brings it back).
            focusWifi()
            compose.press(Button.PlayPause)
            compose.settle()
            assertTrue("Wi-Fi is gone", !exists("cc-tile:wifi"))
            // Down to the Add a Control slot, Select: the groups; Select Glass: the list, with Wi-Fi first and not added.
            repeat(12) { if (compose.focused()?.startsWith("cc-add") != true) compose.press(Button.Down) }
            compose.press(Button.Select)
            compose.settle()
            assertTrue("gallery groups", exists("cc-gallery"))
            compose.press(Button.Select)
            compose.settle()
            assertTrue("Wi-Fi is offered", exists("cc-gallery:wifi"))
            compose.onRoot().captureRoboImage(shot("cc-gallery"))
            // Back goes up a level (groups), then Select Glass again and add Wi-Fi: back on the grid, focused on it.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.settle()
            assertTrue("still in the gallery, at the groups", exists("cc-gallery") && !exists("cc-gallery:wifi"))
            compose.press(Button.Select, Button.Select)
            compose.settle()
            assertTrue("Wi-Fi is back on the grid", exists("cc-tile:wifi") && !exists("cc-gallery"))
            assertTrue("focus landed on it: ${compose.focused()}", compose.focused()?.startsWith("cc-tile:wifi") == true)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.settle()
            assertTrue("saved and left edit mode", !exists("cc-edit-hints") && exists("cc-tile:wifi"))
        }
    }

    @Test fun aNewPageStartsEmptyWithItsAddSlotAndResizeKeepsFocus() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            repeat(12) { if (compose.focused()?.startsWith("cc-edit") != true) compose.press(Button.Down) }
            compose.press(Button.Select)
            compose.settle()
            // Menu resizes the focused tile (Settings has one size: nothing); move to Wi-Fi and resize: focus stays on it.
            focusWifi()
            compose.press(Button.Menu)
            compose.settle()
            assertTrue("focus stays on the resized tile: ${compose.focused()}", compose.focused()?.startsWith("cc-tile:wifi") == true)
            // Up to the page icons, Right twice to the + icon, Select: a new empty page with its Add slot.
            // (In edit mode the Alexa page isn't in the strip: Controls, then +.)
            compose.press(Button.Up)
            repeat(3) { if (compose.focused()?.contains("New page") != true) compose.press(Button.Right) }
            assertTrue("on +: ${compose.focused()}", compose.focused()?.contains("New page") == true)
            compose.press(Button.Select)
            compose.settle()
            assertTrue("the new page is empty", !exists("cc-tile:wifi") && !exists("cc-tile:bluetooth"))
            assertTrue("and the gallery opens for it", exists("cc-gallery"))
            // Back from the gallery, then Back again: edit mode ends (the empty page is dropped) and Control Center stays.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.settle()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.settle()
            assertTrue("edit mode over, Control Center still open", !exists("cc-edit-hints") && exists("control-center") && exists("cc-tile:wifi"))
        }
    }

    @Test fun restartAsksInsideControlCenterAndBackCancels() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            // Down to the round rows, then Right along them to Restart.
            // The default's third row is Sleep, Clean Up, Restart (AirPlay hides without PhairPlay): one Down, then Right.
            compose.press(Button.Down)
            repeat(4) { if (compose.focused()?.startsWith("cc-tile:restart") != true) compose.press(Button.Right) }
            assertTrue("on Restart: ${compose.focused()}", compose.focused()?.startsWith("cc-tile:restart") == true)
            compose.press(Button.Select)
            compose.settle()
            assertTrue("the confirm is in Control Center", exists("cc-confirm") && exists("control-center"))
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.settle()
            assertTrue("Back cancels and stays", !exists("cc-confirm") && exists("cc-tile:restart"))
            assertTrue("focus returns to the Restart tile: ${compose.focused()}", compose.focused()?.startsWith("cc-tile:restart") == true)
        }
    }
}
