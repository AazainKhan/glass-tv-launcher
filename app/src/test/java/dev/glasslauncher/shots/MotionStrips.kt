package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Frame strips of Home's main transitions, written to app/build/strips/. Not assertions: they're
 * for looking at. Run with `scripts/shots strips` (or -Pstrips=<name> for one).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class MotionStrips {

    @get:Rule val compose = createEmptyComposeRule()

    private val only = System.getProperty("strips").orEmpty()

    @Before fun enabled() = assumeTrue("strips not requested", only.isNotEmpty())

    private fun strip(name: String, vararg setup: Button, button: Button, frames: Int = 12, stepMs: Long = 32, columns: Int = 4) {
        assumeTrue(only == "all" || only == name)
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(*setup)
            compose.settle()
            println("strip: " + compose.strip(name, button, frames, stepMs, columns).absolutePath)
        }
    }

    @Test fun dockRight() = strip("dock-right", button = Button.Right)
    @Test fun dockToFeatured() = strip("dock-to-featured", button = Button.Up)
    @Test fun featuredToDock() = strip("featured-to-dock", Button.Up, button = Button.Down)
    @Test fun dockToGrid() = strip("dock-to-grid", button = Button.Down)
    @Test fun gridToDock() = strip("grid-to-dock", Button.Down, button = Button.Up)
    @Test fun openAppMenu() = strip("open-app-menu", button = Button.Menu)
    @Test fun controlCenterOpen() = strip("control-center-open", Button.Up, Button.Up, Button.Up, button = Button.Select, frames = 28, stepMs = 28, columns = 7)
    // Exits: Back goes to the activity's dispatcher directly (the harness's Back key doesn't reach it).
    @Test fun controlCenterClose() {
        assumeTrue(only == "all" || only == "control-center-close")
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.settle()
            val shots = compose.frames({ scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() } }, 24, 24)
            println("strip: " + sheet("control-center-close", shots, 24, 6).absolutePath)
        }
    }
    @Test fun settingsPagePush() = strip("settings-page-push", Button.Down, Button.Down, Button.Right, Button.Right, Button.Select, button = Button.Select)
}
