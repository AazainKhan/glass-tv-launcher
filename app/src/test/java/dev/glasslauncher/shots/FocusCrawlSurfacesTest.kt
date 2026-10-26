package dev.glasslauncher.shots

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P213: the focus crawl of FocusCrawlTest, on the surfaces a user reaches from Home: Control Center (and its edit mode), Settings and
 * each of its pages, the app menu and the folder menu. On each: focus is never lost, every focus stop is on screen, and every enabled
 * focusable that is on screen can be reached with the D-pad (a dead or invisible focus target is a stuck user). Graphs go to
 * build/focus-graph/.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class FocusCrawlSurfacesTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private val folderApps = listOf("com.netflix.ninja", "org.videolan.vlc", "com.plexapp.android")
    private val withFolder = { c: dev.glasslauncher.data.LauncherConfig ->
        c.copy(folders = listOf(dev.glasslauncher.data.Folder("media", "Media", folderApps)), order = listOf(dev.glasslauncher.data.folderKey("media")))
    }

    private fun problemsOf(name: String, graph: FocusCrawler.Graph): List<String> = buildList {
        graph.edges.filter { it.to == null }.forEach { add("$name: ${it.from} ${it.button} loses focus") }
        graph.skipped.forEach { add("$name: the crawler couldn't return to $it") }
        graph.hidden.forEach { add("$name: focus on an unseen element: $it") }
        (graph.offered - graph.nodes).forEach { add("$name: $it is on screen and focusable but the D-pad never reaches it") }
    }

    /** Crawls the surface [open] brings up in a running app and returns what is wrong; [open] is also how it comes back after a Home press. */
    private fun crawlIn(
        scenario: ActivityScenario<MainActivity>, name: String, maxNodes: Int,
        open: (ActivityScenario<MainActivity>) -> Unit,
    ): List<String> {
        scenario.pressHome(); compose.waitForHome()
        open(scenario)
        compose.settle()
        return crawlNow(scenario, name, maxNodes) { open(scenario); compose.settle() }
    }

    /** Crawls what is on screen now; [reopen] brings it back after a Home press. */
    private fun crawlNow(
        scenario: ActivityScenario<MainActivity>, name: String, maxNodes: Int,
        reopen: () -> Unit,
    ): List<String> {
        if (compose.focused() == null) return listOf("$name: nothing has focus once it is open (the first D-pad press would go to whatever is behind it)")
        val graph = FocusCrawler(compose, reset = { scenario.pressHome(); compose.waitForHome(); reopen() }, maxNodes = maxNodes).crawl()
        graph.save("surface-$name")
        return problemsOf(name, graph)
    }

    /** Launches the app and crawls one surface. */
    private fun crawl(
        name: String, maxNodes: Int = 80, config: (dev.glasslauncher.data.LauncherConfig) -> dev.glasslauncher.data.LauncherConfig = { it },
        prepare: () -> Unit = {},
        open: (ActivityScenario<MainActivity>) -> Unit,
    ): List<String> {
        TvHarness.setUp(config = config)
        prepare()
        var found = emptyList<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            found = crawlIn(scenario, name, maxNodes, open)
        }
        return found
    }

    private fun check(problems: List<String>) =
        assertTrue("Focus problems (graphs in build/focus-graph/surface-*.md):\n" + problems.joinToString("\n"), problems.isEmpty())

    /** Focuses the on-screen focusable that [FocusCrawler] would name starting with [prefix] (a tile's tag sits on a parent, so focusTag can't). */
    private fun focusDescribed(prefix: String) {
        val node = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Focused), useUnmergedTree = true)
            .fetchSemanticsNodes().first { describe(it).startsWith(prefix) }
        compose.onNode(androidx.compose.ui.test.SemanticsMatcher("id ${node.id}") { it.id == node.id }, useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    private fun openControlCenter() {
        compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
        compose.waitForTag("control-center")
    }

    @Test fun controlCenter() = check(crawl("control-center") { openControlCenter() })

    @Test fun controlCenterEditMode() = check(crawl("control-center-edit") {
        dev.glasslauncher.home.CcEditEntry.startInEdit = true
        openControlCenter()
    })

    /** The header's page icons exist with a second page (Alexa); focusing one shows its page, so the crawl walks them all. */
    @Test fun controlCenterPages() = check(crawl("control-center-pages", maxNodes = 120, prepare = { TvHarness.installAlexa() }) { openControlCenter() })

    /**
     * The Control Center tiles that open a platter over the grid (the others leave Control Center for a system page): Remotes and
     * Game Controllers, and the Pair platter inside each.
     */
    @Test fun controlCenterPlatters() {
        val problems = mutableListOf<String>()
        listOf("remotes", "controllers").forEach { id ->
            problems += crawl("control-center-platter-$id", maxNodes = 40) {
                openControlCenter()
                focusDescribed("cc-tile:$id")
                compose.press(Button.Select)
                compose.settle()
            }
            problems += crawl("control-center-pair-$id", maxNodes = 40) {
                openControlCenter()
                focusDescribed("cc-tile:$id")
                compose.press(Button.Select)
                compose.settle()
                focusDescribed("cc-action-pair")
                compose.press(Button.Select)
                compose.settle()
            }
        }
        check(problems.distinct())
    }

    @Test fun appMenu() = check(crawl("app-menu") {
        compose.press(Button.Menu)
        compose.waitForTag("menu-header")
    })

    /** The app menu's second lists: Move to… and Change Icon. */
    @Test fun appMenuMoveTo() = check(crawl("app-menu-move-to") {
        compose.press(Button.Menu)
        compose.waitForTag("menu-header")
        compose.press(Button.Down, Button.Select)
    })

    @Test fun appMenuChangeIcon() = check(crawl("app-menu-change-icon") {
        compose.press(Button.Menu)
        compose.waitForTag("menu-header")
        compose.press(Button.Down, Button.Down, Button.Select)
    })

    /** Delivers one of MainActivity's actions the way the remote's buttons do (a new intent to the running activity). */
    private fun deliver(scenario: ActivityScenario<MainActivity>, action: String) = scenario.onActivity { activity ->
        android.app.Activity::class.java.getDeclaredMethod("onNewIntent", android.content.Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, android.content.Intent(action))
    }

    @Test fun search() = check(crawl("search") {
        compose.press(Button.Up, Button.Up, Button.Up)
        // Two circles sit beside the pill now (notices: P98, then search: P161).
        compose.press(Button.Left, Button.Left, Button.Select)
        compose.waitForTag("search")
    })

    /**
     * The switcher keeps its own selected card and asks for focus to follow it, so a crawler's direct focus requests would pull the two
     * apart, and Up closes the focused app. This walks the strip with the keys a user has: Left to the end, Right to the end.
     */
    @Test fun appSwitcher() {
        TvHarness.setUp(config = { it.copy(recentApps = listOf("com.netflix.ninja", "org.videolan.vlc", "com.plexapp.android")) })
        val problems = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            deliver(scenario, MainActivity.ACTION_APP_SWITCHER)
            compose.settle()
            check(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("app-switcher")).fetchSemanticsNodes().isNotEmpty()) { "the switcher didn't open" }
            val onScreen = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("card") { n ->
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("switcher-card:") == true
            }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }.toSet()
            val reached = linkedSetOf<String>()
            fun note(step: String) {
                val f = compose.focused()
                if (f == null) problems += "app-switcher: focus is lost after $step" else reached += f
            }
            note("opening")
            repeat(onScreen.size + 1) { compose.press(Button.Left); compose.settle(); note("Left") }
            repeat(onScreen.size + 1) { compose.press(Button.Right); compose.settle(); note("Right") }
            compose.press(Button.Down); compose.settle(); note("Down")
            (onScreen - reached).forEach { problems += "app-switcher: $it is on screen and focusable but Left and Right never reach it" }
        }
        check(problems.distinct())
    }

    @Test fun downloads() = check(crawl("downloads") { scenario ->
        deliver(scenario, MainActivity.ACTION_DOWNLOADS)
        compose.waitForIdle()
    })

    @Test fun folderMenu() = check(crawl("folder-menu", config = withFolder) {
        compose.focusTag(dev.glasslauncher.data.folderKey("media"))
        compose.settle()
        compose.press(Button.Menu)
        compose.waitForTag("menu-header")
    })

    /** Opens Settings, and the page of its [row]-th row (0 = the list itself). The caller settles once, after. */
    private fun openSettings(row: Int) {
        compose.focusTag("settings-tile")
        compose.press(Button.Select)
        compose.waitForTag("settings-icon:Settings")
        compose.waitForIdle()
        if (row > 0) {
            compose.press(*Array(row) { Button.Down })
            compose.press(Button.Select)
        }
    }

    @Test fun settingsList() = check(crawl("settings") { openSettings(0) })

    /** Every page a row of the main Settings list opens, in one launch of the app (each is reopened from Home: Back does not land on the next row). */
    @Test fun settingsPages() {
        TvHarness.setUp()
        val problems = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            repeat(14) { row -> problems += crawlIn(scenario, "settings-page-$row", 40) { openSettings(row) } }
        }
        check(problems.distinct())
    }
}
