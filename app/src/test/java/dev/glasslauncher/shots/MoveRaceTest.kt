package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import dev.glasslauncher.home.HomeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A held D-pad in move mode repeats about every 30 ms, faster than the layout (built off Main) catches up with the working
 * copy. Every step must still land: positions come from the working copy, never from the lagging layout.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class MoveRaceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    @Test fun everyStepOfAFastRepeatLands() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: HomeModel
            scenario.onActivity { model = androidx.lifecycle.ViewModelProvider(it)[HomeModel::class.java] }
            compose.waitForHome()
            compose.settle()
            val before = model.currentLayout().dock.map { it.packageName }
            val moved = before.first()
            scenario.onActivity {
                model.beginMove()
                // Back to back, with nothing yielding to the layout builder in between: faster than any D-pad repeat.
                repeat(4) { assertTrue("step $it", model.move("app:$moved", 1)) }
            }
            val want = before.drop(1).take(4) + moved + before.drop(5)
            assertEquals("the app moved four places along", want, model.currentLayout().dock.map { it.packageName })
            // The screen follows the working copy once the layout builder catches up.
            val deadline = System.currentTimeMillis() + 5_000
            while (model.layout.value.dock.map { it.packageName } != want && System.currentTimeMillis() < deadline) { Thread.sleep(20); compose.waitForIdle() }
            assertEquals(want, model.layout.value.dock.map { it.packageName })
        }
    }
}
