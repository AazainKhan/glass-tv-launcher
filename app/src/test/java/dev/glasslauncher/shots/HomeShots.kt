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

    private fun capture(name: String) {
        compose.settle()
        compose.stableImage().captureRoboImage(shot(name))
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
        compose.press(Button.Up, Button.Up)
        check(compose.focused() == "status-pill") { "focus is on ${compose.focused()}" }
        compose.press(Button.Select)
        compose.waitForTag("control-center")
        capture("control-center")
    }

    @Test fun appMenu() = home {
        compose.press(Button.Menu)
        capture("app-menu")
    }

    @Test fun folderOpen() = home(config = { c ->
        val apps = listOf("com.plexapp.android", "org.jellyfin.androidtv", "org.videolan.vlc")
        c.copy(folders = listOf(Folder("media", "Media", apps)), order = listOf(folderKey("media")))
    }) {
        compose.focusTag(folderKey("media"))
        compose.press(Button.Select)
        compose.waitForTag("folder-title")
        capture("folder-open")
    }

    @Test fun settings() = home {
        compose.focusTag("settings-tile")
        compose.press(Button.Select)
        capture("settings")
    }

    @Test fun lightDock() = home(config = { it.copy(theme = ThemeMode.Light) }) { capture("home-dock-light") }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForTag(tag: String) {
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMillis = 5_000)
}
