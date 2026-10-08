package dev.glasslauncher.shots

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.MainActivity
import dev.glasslauncher.data.Folder
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.ThemeMode
import dev.glasslauncher.data.folderKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Screen catalogue. Record baselines with `scripts/shots record`, check with `scripts/shots verify`;
 * baselines live in app/src/test/screenshots/.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class HomeShots {

    @get:Rule val compose = createEmptyComposeRule()

    private fun home(config: (LauncherConfig) -> LauncherConfig = { it }, block: (ActivityScenario<MainActivity>) -> Unit) {
        TvHarness.setUp(config = config)
        ActivityScenario.launch(MainActivity::class.java).use { compose.waitForHome(); block(it) }
    }

    /**
     * [tolerance]: the share of pixels that may differ; [colourNoise]: how far each pixel's colour may
     * drift (glass over the JVM harness's blurred Home varies faintly everywhere between runs).
     */
    private fun capture(name: String, tolerance: Float = 0f, colourNoise: Float = 0f) {
        compose.settle()
        val options = if (tolerance > 0f || colourNoise > 0f) com.github.takahirom.roborazzi.RoborazziOptions(
            compareOptions = com.github.takahirom.roborazzi.RoborazziOptions.CompareOptions(
                changeThreshold = tolerance,
                imageComparator = com.dropbox.differ.SimpleImageComparator(maxDistance = colourNoise.coerceAtLeast(0.007f)),
            ),
        ) else com.github.takahirom.roborazzi.RoborazziOptions()
        compose.stableImage().captureRoboImage(shot(name), roborazziOptions = options)
    }

    @Test fun dock() = home { capture("home-dock") }

    @Test fun featuredRow() = home {
        compose.press(Button.Up)
        capture("home-featured-row")
    }

    @Test fun grid() = home {
        compose.press(Button.Down, Button.Right)
        capture("home-grid")
    }

    @Test fun controlCenter() = home {
        // Up expands the shelf, Up again reaches Play, and Up once more the status pill.
        compose.press(Button.Up, Button.Up, Button.Up)
        check(compose.focused() == "status-pill") { "focus is on ${compose.focused()}" }
        compose.press(Button.Select)
        compose.waitForTag("control-center")
        capture("control-center")
    }

    @Test fun appMenu() = home {
        compose.press(Button.Menu)
        // Opens over a snapshot taken while Home is still settling: its glass differs faintly between runs.
        capture("app-menu", tolerance = 0.02f, colourNoise = 0.03f)
    }

    @Test fun folderOpen() = home(config = { c ->
        val apps = listOf("com.plexapp.android", "org.jellyfin.androidtv", "org.videolan.vlc")
        c.copy(folders = listOf(Folder("media", "Media", apps)), order = listOf(folderKey("media")))
    }) {
        compose.focusTag(folderKey("media"))
        compose.press(Button.Select)
        compose.waitForTag("folder-title")
        // The blurred Home behind the folder differs by a few scattered pixels between runs.
        capture("folder-open", tolerance = 0.01f)
    }

    @Test fun settings() = home {
        compose.focusTag("settings-tile")
        compose.press(Button.Select)
        capture("settings", colourNoise = 0.03f)
    }

    @Test fun lightDock() = home(config = { it.copy(theme = ThemeMode.Light) }) { capture("home-dock-light") }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForTag(tag: String) {
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMillis = 5_000)
}
