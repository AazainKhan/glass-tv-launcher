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

    private fun strip(name: String, vararg setup: Button, button: Button) {
        assumeTrue(only == "all" || only == name)
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(*setup)
            compose.settle()
            println("strip: " + compose.strip(name, button).absolutePath)
        }
    }

    @Test fun dockRight() = strip("dock-right", button = Button.Right)
    @Test fun dockToFeatured() = strip("dock-to-featured", button = Button.Up)
    @Test fun featuredToDock() = strip("featured-to-dock", Button.Up, button = Button.Down)
    @Test fun dockToGrid() = strip("dock-to-grid", button = Button.Down)
    @Test fun gridToDock() = strip("grid-to-dock", Button.Down, button = Button.Up)
    @Test fun openAppMenu() = strip("open-app-menu", button = Button.Menu)
}
