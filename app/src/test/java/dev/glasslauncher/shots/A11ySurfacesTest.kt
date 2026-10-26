package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P166: the screen-reader pass over the surfaces a user reaches with the remote. Each test opens one and asserts every focusable element
 * has a spoken label and a role ([A11yAudit]); the Home crawl in FocusCrawlTest does the same for every focus stop it reaches.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class A11ySurfacesTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private fun surface(name: String, pane: String? = null, open: (ActivityScenario<MainActivity>) -> Unit) {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            open(scenario)
            compose.settle()
            val problems = A11yAudit.problems(compose)
            assertTrue("$name has focusable elements a screen reader cannot speak:\n" + problems.joinToString("\n"), problems.isEmpty())
            if (pane != null) assertTrue("$name is not announced as '$pane' (pane titles on screen: ${A11yAudit.paneTitles(compose)})", pane in A11yAudit.paneTitles(compose))
        }
    }

    @Test fun controlCenter() = surface("Control Center", pane = "Control Center") {
        compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
        compose.waitForTag("control-center")
    }

    @Test fun controlCenterEditMode() = surface("Control Center edit mode") {
        dev.glasslauncher.home.CcEditEntry.startInEdit = true
        compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
        compose.waitForTag("control-center")
    }

    @Test fun search() = surface("Search", pane = "Search") {
        compose.press(Button.Up, Button.Up, Button.Up)
        // Two circles sit beside the pill now (notices: P98, then search: P161).
        compose.press(Button.Left, Button.Left, Button.Select)
        compose.waitForTag("search")
    }

    @Test fun appMenu() = surface("App menu") {
        compose.press(Button.Menu)
        compose.waitForTag("menu-header")
    }

    @Test fun settings() = surface("Settings", pane = "Settings") {
        compose.focusTag("settings-tile")
        compose.press(Button.Select)
        compose.waitForTag("settings-icon:Settings")
    }

    @Test fun settingsPages() {
        // Every page a row of the main Settings list opens: Select the i-th row, audit, Back.
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            compose.focusTag("settings-tile")
            compose.press(Button.Select)
            compose.waitForTag("settings-icon:Settings")
            compose.settle()
            val problems = mutableListOf<String>()
            repeat(14) { i ->
                compose.press(*Array(i + 1) { Button.Down }.let { if (i == 0) emptyArray() else it })
                compose.press(Button.Select)
                compose.settle()
                A11yAudit.problems(compose).forEach { problems += "row $i: $it" }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.settle()
            }
            assertTrue("Settings pages have focusable elements a screen reader cannot speak:\n" + problems.distinct().joinToString("\n"), problems.isEmpty())
        }
    }

    private val folderApps = listOf("com.netflix.ninja", "org.videolan.vlc", "com.plexapp.android")

    @Test fun folderMenuRenameAndConfirm() {
        TvHarness.setUp(config = { c -> c.copy(folders = listOf(dev.glasslauncher.data.Folder("media", "Media", folderApps)), order = listOf(dev.glasslauncher.data.folderKey("media"))) })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            compose.focusTag(dev.glasslauncher.data.folderKey("media"))
            compose.settle()
            compose.press(Button.Menu)
            compose.waitForTag("menu-header")
            val problems = mutableListOf<String>()
            A11yAudit.problems(compose).forEach { problems += "folder menu: $it" }
            // Rename: the compact text panel.
            compose.press(Button.Down, Button.Select)
            compose.waitForTag("text-value")
            compose.settle()
            A11yAudit.problems(compose).forEach { problems += "rename: $it" }
            if ("Rename Folder" !in A11yAudit.paneTitles(compose)) problems += "rename is not announced as 'Rename Folder': ${A11yAudit.paneTitles(compose)}"
            assertTrue("These surfaces have focusable elements a screen reader cannot speak:\n" + problems.joinToString("\n"), problems.isEmpty())
        }
    }

    @Test fun controlCenterGalleryAndPlatters() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            dev.glasslauncher.home.CcEditEntry.startInEdit = true
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            val problems = mutableListOf<String>()
            // The Add a Control gallery (its groups, then the Apps group).
            repeat(12) { if (compose.focused()?.startsWith("cc-add") != true) compose.press(Button.Down) }
            compose.press(Button.Select)
            compose.settle()
            A11yAudit.problems(compose).forEach { problems += "gallery groups: $it" }
            compose.press(Button.Select)
            compose.settle()
            A11yAudit.problems(compose).forEach { problems += "gallery list: $it" }
            assertTrue("The gallery has focusable elements a screen reader cannot speak:\n" + problems.joinToString("\n"), problems.isEmpty())
        }
    }

    @Test fun infoSheet() = surface("More Info sheet", pane = "More Info") {
        // Up expands the shelf, Up again reaches Play, Right is More Info.
        compose.press(Button.Up, Button.Up, Button.Right, Button.Select)
        compose.waitForTag("shelf-info-sheet")
    }

    @Test fun confirmCard() {
        TvHarness.setUp(config = { c -> c.copy(folders = listOf(dev.glasslauncher.data.Folder("media", "Media", folderApps)), order = listOf(dev.glasslauncher.data.folderKey("media"))) })
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.focusTag(dev.glasslauncher.data.folderKey("media"))
            compose.settle()
            compose.press(Button.Menu)
            compose.waitForTag("menu-header")
            // Open, Rename, Move, Remove Folder: the confirm card.
            compose.press(Button.Down, Button.Down, Button.Down, Button.Select)
            compose.settle()
            val problems = A11yAudit.problems(compose)
            assertTrue("The confirm card has focusable elements a screen reader cannot speak:\n" + problems.joinToString("\n"), problems.isEmpty())
        }
    }

    @Test fun coverFlow() {
        TvHarness.setUp(squareFeed = true)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up)
            compose.waitForTag("cover-flow")
            compose.settle()
            val problems = A11yAudit.problems(compose)
            assertTrue("The Cover Flow has focusable elements a screen reader cannot speak:\n" + problems.joinToString("\n"), problems.isEmpty())
        }
    }
}
