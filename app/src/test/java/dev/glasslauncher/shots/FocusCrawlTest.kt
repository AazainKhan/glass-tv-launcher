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

    @Test fun home() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            val graph = FocusCrawler(compose, reset = { scenario.pressHome() }).crawl()
            graph.save("home")
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

            // Left/Right at the end of a row stays put instead of jumping to the status pill or another row.
            graph.nodes.forEach { n ->
                listOf(Button.Left, Button.Right).forEach { b ->
                    if (graph.from(n, b) == "status-pill" && n != "status-pill") problems += "$n $b -> status-pill"
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

            assertTrue("Focus problems (see build/focus-graph/home.md):\n" + problems.joinToString("\n"), problems.isEmpty())
        }
    }
}
