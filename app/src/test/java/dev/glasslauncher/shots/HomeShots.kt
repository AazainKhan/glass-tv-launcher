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
    @get:Rule val pinnedClock = PinnedClockRule()

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
        // At rest first: the overlay snapshots Home on a real thread, so mid-spring tiles would land at a different phase each run.
        compose.settle()
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

    // Larger text grows the layout (§9.2): the tray still fits on screen and keeps its apps reachable.
    @Test fun dockLargeText() = home(config = { it.copy(textScale = 1.3f) }) { capture("home-dock-1.3x") }
    @Test fun dockLargestText() = home(config = { it.copy(textScale = 1.4f) }) { capture("home-dock-1.4x") }

    // Light appearance is a theme of its own (§9.1): every main surface has a light baseline.
    private val light: (LauncherConfig) -> LauncherConfig = { it.copy(theme = ThemeMode.Light) }

    @Test fun lightGrid() = home(config = light) {
        compose.press(Button.Down, Button.Right)
        capture("home-grid-light")
    }

    @Test fun lightFeaturedRow() = home(config = light) {
        compose.press(Button.Up)
        capture("home-featured-row-light")
    }

    @Test fun lightAppMenu() = home(config = light) {
        compose.press(Button.Menu)
        capture("app-menu-light", tolerance = 0.02f, colourNoise = 0.03f)
    }

    @Test fun lightFolderOpen() = home(config = { c ->
        val apps = listOf("com.plexapp.android", "org.jellyfin.androidtv", "org.videolan.vlc")
        c.copy(theme = ThemeMode.Light, folders = listOf(Folder("media", "Media", apps)), order = listOf(folderKey("media")))
    }) {
        compose.focusTag(folderKey("media"))
        // At rest first: the overlay snapshots Home on a real thread, so mid-spring tiles would land at a different phase each run.
        compose.settle()
        compose.press(Button.Select)
        compose.waitForTag("folder-title")
        capture("folder-open-light", tolerance = 0.01f)
    }

    @Test fun lightControlCenter() = home(config = light) {
        compose.press(Button.Up, Button.Up, Button.Up)
        compose.press(Button.Select)
        compose.waitForTag("control-center")
        capture("control-center-light")
    }

    @Test fun lightSettings() = home(config = light) {
        compose.focusTag("settings-tile")
        compose.press(Button.Select)
        capture("settings-light", colourNoise = 0.03f)
    }
}

internal fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForTag(tag: String) {
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMillis = 5_000)
}
