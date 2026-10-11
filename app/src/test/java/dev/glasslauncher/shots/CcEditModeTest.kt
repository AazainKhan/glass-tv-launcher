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
            compose.press(Button.Right, Button.Select, Button.Down, Button.Select)
            compose.settle()
            // Back leaves edit mode (and saves): Control Center is still open, hints gone.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.settle()
            assertTrue("edit mode is over", hints() == 0)
            assertTrue("Wi-Fi now sits below Bluetooth (top ${top("wifi")} vs ${top("bluetooth")})", top("wifi") > top("bluetooth"))
        }
    }
}
