package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.GlassApp
import dev.glasslauncher.MainActivity
import dev.glasslauncher.app
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A held D-pad in move mode is many steps but one save: the layout is edited in memory and written when the move ends. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class MoveSavesOnceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    @Test fun fourStepsAreOneWrite() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The store the activity's own HomeModel uses (not whichever application the test context hands out).
            lateinit var model: dev.glasslauncher.home.HomeModel
            scenario.onActivity { model = androidx.lifecycle.ViewModelProvider(it)[dev.glasslauncher.home.HomeModel::class.java] }
            val store = object {
                val writes get() = model.saveCount
                val config get() = model.savedConfig // what is on disk, not the move's working layout
            }
            compose.waitForHome()
            compose.settle()
            val first = store.config.value.dock.first()
            compose.press(Button.Menu) // the app menu, with Edit Home Screen focused
            compose.settle()
            compose.press(Button.Select)
            compose.settle()
            check(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("move-banner")).fetchSemanticsNodes().isNotEmpty()) { "move mode didn't start (focused ${compose.focused()})" }
            val before = store.writes // whatever opening the menu wrote is not the move
            compose.press(Button.Right, Button.Right, Button.Right, Button.Right)
            compose.settle()
            // Followed on screen already, not yet saved: the working copy is what the screen shows.
            assertEquals("not written step by step", before, store.writes)
            compose.press(Button.Select) // Done
            compose.settle()
            // The save runs on a background thread: wait for it (bounded) instead of trusting one settle.
            val deadline = System.currentTimeMillis() + 10_000
            while (store.writes == before && System.currentTimeMillis() < deadline) { Thread.sleep(50); compose.waitForIdle() }
            assertEquals("one write for the whole move", before + 1, store.writes)
            val dock = store.config.value.dock
            assertEquals("and it is the saved order: the moved app four places along", first, dock[minOf(4, dock.lastIndex)])
            assertTrue("the others shifted left", dock.first() != first)
        }
    }
}
