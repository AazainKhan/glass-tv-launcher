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
 * Crawls Home's focus graph and checks the rules a remote user relies on. The full graph is
 * written to build/focus-graph/home.md for review.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class FocusCrawlTest {

    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    @Test fun home() = crawlHome("home")

    // Largest text (§9.2): four columns, so the tray's last apps move into the grid; all stay reachable.
    @Test fun homeLargestText() = crawlHome("home-1.4x") { it.copy(textScale = 1.4f) }

    private fun crawlHome(name: String, config: (dev.glasslauncher.data.LauncherConfig) -> dev.glasslauncher.data.LauncherConfig = { it }) {
        TvHarness.setUp(config = config)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            val graph = FocusCrawler(compose, reset = { scenario.pressHome() }).crawl()
            graph.save(name)
            val problems = mutableListOf<String>()

            // Focus is never lost.
            graph.edges.filter { it.to == null }.forEach { problems += "${it.from} ${it.button} loses focus" }

            graph.skipped.forEach { problems += "crawler couldn't return to $it" }

            // Every installed app is reachable.
            val missing = TvHarness.apps.keys.map { "app:$it" } - graph.nodes
            if (missing.isNotEmpty()) problems += "unreachable: $missing"

            // Down from any featured card leaves the featured row (bug #6).
            graph.matching("featured:").forEach { card ->
                val to = graph.from(card, Button.Down)
                if (to == null || to.startsWith("featured:")) problems += "$card Down -> $to (stays in the featured row)"
            }

            // Left/Right at the end of a row stays put instead of jumping to the status pill or another row. The circles
            // beside the pill are its neighbours (search: P161, notices: P98), so they may reach it.
            graph.nodes.forEach { n ->
                listOf(Button.Left, Button.Right).forEach { b ->
                    if (graph.from(n, b) == "status-pill" && n != "status-pill" && n != "status-search" && n != "status-notices") problems += "$n $b -> status-pill"
                }
            }
            graph.from("settings-tile", Button.Right)?.let { if (it != "settings-tile") problems += "settings-tile Right -> $it" }

            // Up and Down are reversible between apps: Down then Up gets back to the same row.
            graph.matching("app:").forEach { a ->
                val down = graph.from(a, Button.Down) ?: return@forEach
                if (down == a || !down.startsWith("app:")) return@forEach
                val back = graph.from(down, Button.Up)
                if (back != null && !back.startsWith("app:") && !back.startsWith("folder:")) problems += "$a Down -> $down, but Up -> $back"
            }

            // A screen reader (TalkBack) can speak every focus stop: a label and a role (P166).
            writeA11y(name, graph)
            graph.a11y.forEach { (node, a) ->
                if (a.label.isBlank()) problems += "$node has no spoken label"
                else if (a.role == null) problems += "$node ('${a.label}') has no role"
            }

            assertTrue("Focus problems (see build/focus-graph/home.md):\n" + problems.joinToString("\n"), problems.isEmpty())
        }
    }

    private fun writeA11y(name: String, graph: FocusCrawler.Graph) {
        val dir = java.io.File("build/focus-graph").apply { mkdirs() }
        java.io.File(dir, "$name-a11y.md").writeText(
            "| node | label | role |\n|---|---|---|\n" + graph.a11y.entries.joinToString("\n") { (n, a) -> "| $n | ${a.label.replace("|", "/")} | ${a.role ?: "NONE"} |" },
        )
    }
}
