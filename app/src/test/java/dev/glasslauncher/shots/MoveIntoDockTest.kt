package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import dev.glasslauncher.home.HomeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P190: moving an app up into the dock must leave Home alive: focus on the moved app, and the next key still moves it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class MoveIntoDockTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    @Test fun anAppMovedIntoTheDockStaysFocusedAndKeysStillWork() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: HomeModel
            scenario.onActivity { model = androidx.lifecycle.ViewModelProvider(it)[HomeModel::class.java] }
            compose.waitForHome()
            compose.settle()
            compose.press(Button.Down)           // first grid app
            compose.settle()
            val key = compose.focused()
            assertNotNull("focus on a grid tile", key)
            check(key!!.startsWith("app:")) { "focused $key" }
            compose.press(Button.Menu); compose.settle()
            compose.press(Button.Select); compose.settle()  // Edit Home Screen
            check(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("move-banner")).fetchSemanticsNodes().isNotEmpty()) { "move mode didn't start (focused ${compose.focused()})" }
            compose.press(Button.Up); compose.settle()       // into the dock
            val dockNow = model.currentLayout().dock.map { "app:" + it.packageName }
            assertTrue("$key is in the dock: $dockNow", key in dockNow)
            assertEquals("focus followed the moved app", key, compose.focused())
            val before = model.currentLayout().dock.map { it.packageName }
            compose.press(Button.Right); compose.settle()
            assertTrue("a key still moves it", model.currentLayout().dock.map { it.packageName } != before)
            compose.press(Button.Select); compose.settle()   // Done
            assertTrue("move mode ended", compose.onAllNodes(androidx.compose.ui.test.hasTestTag("move-banner")).fetchSemanticsNodes().isEmpty())
            compose.press(Button.Right); compose.settle()
            assertNotNull("keys work on Home again", compose.focused())
        }
    }

    /** Many arrow keys in a row in move mode (into the dock, out again, along it): Home must still answer after Done. */
    @Test fun aLongRunOfMoveKeysLeavesHomeAlive() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            compose.waitForHome()
            compose.settle()
            compose.press(Button.Down); compose.settle()
            compose.press(Button.Right, Button.Right); compose.settle()
            compose.press(Button.Menu); compose.settle()
            compose.press(Button.Select); compose.settle()
            val rnd = java.util.Random(190)
            val keys = listOf(Button.Up, Button.Down, Button.Left, Button.Right)
            repeat(60) { compose.press(keys[rnd.nextInt(keys.size)]) }
            compose.settle()
            compose.press(Button.Select); compose.settle()  // Done
            check(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("move-banner")).fetchSemanticsNodes().isEmpty()) { "move mode never ended" }
            compose.press(Button.Down); compose.settle()
            assertNotNull("Home answers keys after the move", compose.focused())
        }
    }

    /** The user's route (P190): Menu, Move to…, App Dock. The moved tile is a new node; focus must not be left nowhere. */
    @Test fun moveToAppDockLeavesFocusOnATile() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: HomeModel
            scenario.onActivity { model = androidx.lifecycle.ViewModelProvider(it)[HomeModel::class.java] }
            compose.waitForHome()
            compose.settle()
            compose.press(Button.Down); compose.settle()
            val key = compose.focused()!!
            compose.press(Button.Menu); compose.settle()
            compose.press(Button.Down); compose.settle()      // Move to…
            compose.press(Button.Select); compose.settle()
            compose.press(Button.Select); compose.settle()    // App Dock
            // A slow stick lands the layout late: give it a while, then every key must still land on something.
            compose.mainClock.advanceTimeBy(2_000); compose.settle()
            assertTrue("$key moved into the dock", key in model.currentLayout().dock.map { "app:" + it.packageName })
            assertNotNull("focus is on a tile, not nowhere", compose.focused())
            compose.press(Button.Right); compose.settle()
            assertNotNull(compose.focused())
        }
    }

    /** Whatever drops focus (a tile that changed places), the watchdog puts it back within a second or two. */
    @Test fun focusLostWhileHomeIsIdleComesBack() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            compose.settle()
            compose.press(Button.Down); compose.settle()
            assertNotNull(compose.focused())
            scenario.onActivity { it.currentFocus?.clearFocus() }
            compose.settle()
            compose.mainClock.advanceTimeBy(2_000); compose.settle()
            assertNotNull("focus came back", compose.focused())
        }
    }
}
