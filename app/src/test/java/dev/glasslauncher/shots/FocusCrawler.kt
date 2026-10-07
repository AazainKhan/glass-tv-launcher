package dev.glasslauncher.shots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performSemanticsAction
import java.io.File

/**
 * Walks every focusable reachable from the current focus by pressing each D-pad direction from
 * each node, the way a user would, and records where focus lands. Catches dead ends, focus loss,
 * unreachable items and wrong neighbours (e.g. Down from the featured row going nowhere).
 */
class FocusCrawler(
    private val compose: ComposeTestRule,
    /** Returns the screen to its starting focus (e.g. a Home press). Used when a node can't be focused directly. */
    private val reset: () -> Unit,
    private val maxNodes: Int = 200,
) {

    data class Edge(val from: String, val button: Button, val to: String?)

    /** Shortest key path from the start to each node found so far. */
    private val paths = HashMap<String, List<Button>>()
    private lateinit var start: String

    class Graph(val start: String, val edges: List<Edge>, val skipped: List<String>) {
        val nodes: Set<String> get() = (edges.map { it.from } + edges.mapNotNull { it.to }).toSet()
        fun from(node: String, button: Button) = edges.firstOrNull { it.from == node && it.button == button }?.to
        fun matching(prefix: String) = nodes.filter { it.startsWith(prefix) }

        /** Readable table, one row per node: where each direction goes ("·" = stays put, "∅" = focus lost). */
        fun table(): String = buildString {
            appendLine("| node | Up | Down | Left | Right |")
            appendLine("|---|---|---|---|---|")
            for (n in edges.map { it.from }.distinct()) {
                val cells = listOf(Button.Up, Button.Down, Button.Left, Button.Right).map { b ->
                    when (val to = from(n, b)) { null -> "∅"; n -> "·"; else -> to }
                }
                appendLine("| $n | ${cells.joinToString(" | ")} |")
            }
        }

        fun json(): String = edges.joinToString(",\n  ", "[\n  ", "\n]") {
            """{"from":"${it.from}","button":"${it.button}","to":${it.to?.let { t -> "\"$t\"" } ?: "null"}}"""
        }

        fun save(name: String) {
            val dir = File("build/focus-graph").apply { mkdirs() }
            File(dir, "$name.json").writeText(json())
            val skippedNote = if (skipped.isEmpty()) "" else "\nCouldn't get back to (not crawled): ${skipped.joinToString()}\n"
            File(dir, "$name.md").writeText("# Focus graph: $name\n\nStart: `$start`\n\n" + table() + skippedNote)
        }
    }

    fun crawl(directions: List<Button> = listOf(Button.Up, Button.Down, Button.Left, Button.Right)): Graph {
        start = requireNotNull(compose.focused()) { "nothing is focused" }
        paths[start] = emptyList()
        val edges = ArrayList<Edge>()
        val queue = ArrayDeque(listOf(start))
        val seen = mutableSetOf(start)
        val skipped = ArrayList<String>()
        while (queue.isNotEmpty() && seen.size <= maxNodes) {
            val node = queue.removeFirst()
            for (b in directions) {
                if (!focus(node)) { skipped += node; break }
                compose.press(b)
                val to = compose.focused()
                edges += Edge(node, b, to)
                if (to != null && seen.add(to)) { queue += to; paths[to] = paths.getValue(node) + b }
            }
        }
        focus(start)
        return Graph(start, edges, skipped)
    }

    /** Moves focus to [name] (as [describe] names it): directly if it's on screen, else by replaying its key path. */
    fun focus(name: String): Boolean {
        if (compose.focused() == name) return true
        if (requestFocus(name)) return true
        val path = paths[name] ?: return false
        reset(); compose.waitForIdle()
        if (compose.focused() != start && !focus(start)) return false
        compose.press(*path.toTypedArray())
        // Rows can remember their last focus, so the replay may land nearby; the node is composed now.
        return compose.focused() == name || requestFocus(name)
    }

    private fun requestFocus(name: String): Boolean {
        val node = focusables().firstOrNull { describe(it) == name } ?: return false
        compose.onNode(SemanticsMatcher("id ${node.id}") { it.id == node.id }, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        return compose.focused() == name
    }

    private fun focusables(): List<SemanticsNode> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused), useUnmergedTree = true)
            .fetchSemanticsNodes()
}
