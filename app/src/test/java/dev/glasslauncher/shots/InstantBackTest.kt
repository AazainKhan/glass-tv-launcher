package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** P87: Back during Glass's own open zoom, before the app is started, cancels it: the app never starts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class InstantBackTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private fun app() = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())

    private fun drain() { while (app().nextStartedActivity != null) Unit }

    @Test fun backBeforeTheHandoffNeverStartsTheApp() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            compose.settle()
            drain()
            compose.mainClock.autoAdvance = false
            try {
                compose.onRoot().performKeyInput { pressKey(Button.Select.key) }
                // The zoom is under way; the app is started ~170 ms in. Back at ~60 ms.
                compose.mainClock.advanceTimeBy(60)
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.mainClock.advanceTimeBy(1_500)
            } finally { compose.mainClock.autoAdvance = true }
            compose.settle()
            assertNull("the app was never started", app().nextStartedActivity)
        }
    }

    @Test fun withoutBackTheAppStartsAfterTheHandoff() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.settle()
            drain()
            compose.onRoot().performKeyInput { pressKey(Button.Select.key) }
            compose.mainClock.advanceTimeBy(1_500)
            compose.settle()
            assertNotNull("the control: it does start", app().nextStartedActivity)
        }
    }
}
