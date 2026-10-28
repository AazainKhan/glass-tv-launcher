package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.MainActivity
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P308: Home with a right-to-left layout. The stick has no `cmd locale`, so the harness runs the real activity under an Arabic locale
 * (that is what makes the view's layout direction RTL; the `ldrtl` qualifier alone does not).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "ar-ldrtl-$TV")
class RtlHomeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private fun home(config: (dev.glasslauncher.data.LauncherConfig) -> dev.glasslauncher.data.LauncherConfig = { it }, block: () -> Unit) {
        TvHarness.setUp(config = config)
        ActivityScenario.launch(MainActivity::class.java).use { compose.waitForHome(); compose.settle(); block() }
    }

    private fun left(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left
    private fun exists(tag: String) = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theDockIsMirroredAndFocusFollowsTheKeys() = home {
        val netflix = "app:com.netflix.ninja"; val youtube = "app:com.amazon.firetv.youtube"
        assertTrue("the first app is at the right edge: ${left(netflix)} vs ${left(youtube)}", left(netflix) > left(youtube))
        compose.press(Button.Left)
        assertEquals("Left moves focus to the tile on the left, the next app", youtube, compose.focused())
        compose.press(Button.Right)
        assertEquals(netflix, compose.focused())
    }

    /** Rearranging the dock: Left moves the tile to the left, which is one place later in a mirrored dock. */
    @Test fun movingATileFollowsTheKeyOnScreen() = home {
        val netflix = "app:com.netflix.ninja"
        compose.press(Button.Menu) // the app menu, with Edit Home Screen focused
        compose.settle()
        compose.press(Button.Select)
        compose.settle()
        assertTrue("move mode started", exists("move-banner"))
        val before = left(netflix)
        compose.press(Button.Left)
        compose.settle()
        assertTrue("Left moved the tile left (was $before, now ${left(netflix)})", left(netflix) < before)
    }

    /** Control Center's edit mode: a lifted tile moves the way the key points on the mirrored grid. */
    @Test fun aLiftedControlMovesTheWayTheKeyPoints() = home {
        dev.glasslauncher.home.CcEditEntry.startInEdit = true
        compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
        compose.waitForTag("control-center")
        compose.settle()
        // Settings sits at the right in a mirrored grid, with Wi-Fi to its left: Left reaches Wi-Fi.
        compose.press(Button.Left)
        if (compose.focused()?.startsWith("cc-tile:wifi") != true) compose.press(Button.Up)
        assertTrue("on Wi-Fi: ${compose.focused()}", compose.focused()?.startsWith("cc-tile:wifi") == true)
        val before = left("cc-tile:wifi")
        compose.press(Button.Select, Button.Right)
        compose.settle()
        assertTrue("Right moved Wi-Fi right (was $before, now ${left("cc-tile:wifi")})", left("cc-tile:wifi") > before)
    }

    /** The dock mirrored: the first app at the right edge, the status pill and the hero text on the right/left of a right-to-left screen. */
    @Test fun goldenHomeDockRtl() = home {
        compose.settle()
        compose.stableImage().captureRoboImage(shot("home-dock-rtl"))
    }
}
