package dev.glasslauncher.shots

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P93 #7: when Home fades out on idle, the time stays over the wallpaper and the rest of the chrome goes.
 *
 * tvOS leaves the clock when the top shelf goes full-bleed, and it is the one piece worth keeping: it says the TV is
 * awake and what time it is. The pill's glass and gear go with the chrome, so this checks both halves - the clock is
 * still there, and the gear is not.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class IdleClockTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** Past the idle timeout and through the fade. Both clocks: the timer reads uptimeMillis, the fade reads the frame clock. */
    private fun goIdle() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(70, java.util.concurrent.TimeUnit.SECONDS)
        compose.mainClock.advanceTimeBy(70_000)
        compose.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
    }

    @Test fun theClockSurvivesIdleAndTheGearDoesNot() {
        TvHarness.setUp(config = { it.copy(idleFadeMinutes = 1) })
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            compose.waitForHome()
            compose.settle()
            assertTrue("the pill is there to begin with", exists("status-pill"))
            assertTrue("and its gear", exists("status-gear"))

            goIdle()

            assertTrue("the clock must stay over the wallpaper when idle", exists("idle-clock"))
            assertFalse("the gear goes with the rest of the chrome", exists("status-gear"))

            // Any key brings the full pill back.
            compose.press(Button.Back)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertTrue("a key restores the pill", exists("status-gear"))
            assertFalse("and the bare clock gives way to it", exists("idle-clock"))
        }
    }
}
