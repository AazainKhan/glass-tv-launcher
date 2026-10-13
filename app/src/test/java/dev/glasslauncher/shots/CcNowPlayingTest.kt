package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.MainActivity
import dev.glasslauncher.widgets.NowPlaying
import dev.glasslauncher.widgets.NowPlayingFixture
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P136: Control Center with a Now Playing session: the card takes its own place under the grid and covers nothing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcNowPlayingTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    @After fun reset() { NowPlayingFixture.state = null }

    private val track = NowPlaying(
        "Spider-Man", "Sony Pictures", null, "airplay", null, durationMs = 8_158_000, positionMs = 542_000,
        positionAt = android.os.SystemClock.elapsedRealtime(), speed = 0f, playing = false,
        controller = object : NowPlaying.Controls { override fun play() {}; override fun pause() {}; override fun next() {}; override fun previous() {} },
    )

    @Test fun cardUnderTheGrid() {
        NowPlayingFixture.state = MutableStateFlow(track)
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            // The card sits under the grid's last row, inside the panel, and covers no tile.
            val card = compose.onNodeWithTag("now-playing-card").fetchSemanticsNode().boundsInRoot
            val last = compose.onNodeWithTag("cc-tile:restart").fetchSemanticsNode().boundsInRoot
            val panel = compose.onNodeWithTag("control-center").fetchSemanticsNode().boundsInRoot
            check(card.top >= last.bottom) { "card ${card.top} overlaps the grid (${last.bottom})" }
            check(card.bottom <= panel.bottom) { "card ${card.bottom} outside the panel (${panel.bottom})" }
            compose.onRoot().captureRoboImage(shot("cc-now-playing"))
        }
    }
}
