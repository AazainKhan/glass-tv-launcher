package dev.glasslauncher.shots

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import dev.glasslauncher.data.Folder
import dev.glasslauncher.data.folderKey
import dev.glasslauncher.home.TrayHost
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P248: the dock tray's own view, and its hand-over to Home's view while an overlay is open. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class TrayHostTest {

    @get:Rule val compose = createEmptyComposeRule()

    @After fun reset() { TrayHost.split = false }

    private fun withFolder(split: Boolean, block: (ActivityScenario<MainActivity>) -> Unit) {
        TvHarness.setUp(config = { c -> c.copy(folders = listOf(Folder("media", "Media", listOf("org.jellyfin.androidtv", "org.videolan.vlc", "com.hbo.hbonow"))), order = listOf(folderKey("media"))) })
        TrayHost.split = split
        ActivityScenario.launch(MainActivity::class.java).use { compose.waitForHome(); block(it) }
    }

    @Test(timeout = 120_000)
    fun underAnOpenFolderTheTraySlotGoesIdle() = withFolder(split = false) { _ ->
        compose.focusTag(folderKey("media"))
        compose.settle()
        compose.press(Button.Select)
        compose.waitForTag("folder-title")
        compose.settle()
        val before = TrayHost.slotCompositions
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        // A slot that recomposes itself never goes idle (the loop P248 shipped with once).
        assertTrue("tray slot recomposed ${TrayHost.slotCompositions - before} times at rest", TrayHost.slotCompositions - before <= 2)
    }

    @Test(timeout = 120_000)
    fun theSplitTrayHandsOverToHomeWhileAFolderIsOpen() = withFolder(split = true) { scenario ->
        assertNotNull("the tray's own view is attached", TrayHost.view)
        assertFalse(TrayHost.inMain)
        compose.focusTag(folderKey("media"))
        compose.settle()
        // Several Compose roots now (Home's, the tray's, the grid's, P258): keys go to the one where the folder has focus.
        compose.onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForTag("folder-title")
        assertTrue("an open folder draws the tray in Home's view", TrayHost.inMain)
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.settle()
        assertFalse("closing it hands the tray back", TrayHost.inMain)
    }

    private fun focusedTag(): String? = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
        .firstNotNullOfOrNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) }

    @Test(timeout = 120_000)
    fun aDockTileHasFocusAgainAfterItsMenuCloses() = withFolder(split = true) { scenario ->
        compose.focusTag("app:com.netflix.ninja")
        compose.settle()
        // Keys go to the root that holds the focus (the tray's view).
        compose.onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(Key.Menu) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("App Info").fetchSemanticsNodes().isNotEmpty() }
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        // Checked before Home's focus watchdog (two 500 ms looks) could put it back.
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertEquals("focus comes back to the tile whose menu closed", "app:com.netflix.ninja", focusedTag())
        compose.onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(Key.DirectionRight) }
        compose.settle()
        assertEquals("and Right moves it", "app:com.amazon.firetv.youtube", focusedTag())
    }
}
