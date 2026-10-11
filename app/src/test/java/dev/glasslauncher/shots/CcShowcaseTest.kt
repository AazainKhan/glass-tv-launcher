package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.MainActivity
import dev.glasslauncher.home.CcLayout
import dev.glasslauncher.home.CcPage
import dev.glasslauncher.home.CcSize
import dev.glasslauncher.home.CcTile
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** P83 M6: the add-on tile states with fake data (debug flag), and the permission card with Deny first. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcShowcaseTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private fun exists(tag: String) = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private val layout = CcLayout(listOf(CcPage("controls", "grid", listOf(
        CcTile("fire_settings", CcSize.S2x2), CcTile("addon:claude/agents", CcSize.S2x1), CcTile("addon:ha/scene#movie_night", CcSize.S2x1),
        CcTile("addon:plex/continue", CcSize.S2x1), CcTile("addon:weather/now", CcSize.S2x1), CcTile("now_playing", CcSize.S2x1),
        CcTile("restart", CcSize.S1x1), CcTile("sleep_timer", CcSize.S1x1),
    ))))

    @Test fun showcaseTilesAndTheApproveCardWithDenyFirst() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.filesDir, "cc-showcase").apply { parentFile?.mkdirs() }.writeText("1")
        try {
            TvHarness.setUp(config = { it.copy(ccLayout = layout.toJson()) })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForHome()
                compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
                compose.waitForTag("control-center")
                compose.settle()
                assertTrue("agents tile", exists("cc-tile:addon:claude/agents"))
                compose.onRoot().captureRoboImage(shot("cc-showcase"))
                // Agents: Select opens the card, Deny has focus first.
                compose.press(Button.Right)
                assertTrue("on Agents: ${compose.focused()}", compose.focused()?.startsWith("cc-tile:addon:claude/agents") == true)
                compose.press(Button.Select)
                compose.settle()
                assertTrue("approve card", exists("cc-agent-card"))
                assertTrue("Deny focused first: ${compose.focused()}", compose.focused()?.startsWith("cc-agent-deny") == true)
                compose.onRoot().captureRoboImage(shot("cc-agent-card"))
                // Back is Deny: the card closes and the tile reads Denied.
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.settle()
                assertTrue("card closed", !exists("cc-agent-card") && exists("cc-tile:addon:claude/agents"))
            }
        } finally {
            File(ctx.filesDir, "cc-showcase").delete()
        }
    }
}
