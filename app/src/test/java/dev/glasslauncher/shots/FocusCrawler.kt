package dev.glasslauncher.shots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
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

    /** What a screen reader gets for a focused element (P166): the spoken label (description, else text, else its children's text) and its role. */
    data class A11y(val label: String, val role: String?)

    /** Shortest key path from the start to each node found so far. */
    private val paths = HashMap<String, List<Button>>()
    private lateinit var start: String

    /**
     * [offered] is every focusable that was on screen (enabled, not an unlabelled container) while the crawl visited a node: a focus
     * target that was there to see and never got focus is unreachable. [hidden] lists the focus stops whose bounds are empty or outside
     * the screen (P213: the focus would be on something the user cannot see).
     */
    class Graph(
        val start: String, val edges: List<Edge>, val skipped: List<String>, val a11y: Map<String, A11y> = emptyMap(),
        val offered: Set<String> = emptySet(), val hidden: List<String> = emptyList(),
    ) {
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
        startLayer = compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.let { layerOf(it) }
        paths[start] = emptyList()
        val a11y = LinkedHashMap<String, A11y>()
        a11yOfFocused()?.let { a11y[start] = it }
        val edges = ArrayList<Edge>()
        val queue = ArrayDeque(listOf(start))
        val seen = mutableSetOf(start)
        val skipped = ArrayList<String>()
        val offered = LinkedHashSet<String>()
        val hidden = ArrayList<String>()
        while (queue.isNotEmpty() && seen.size <= maxNodes) {
            val node = queue.removeFirst()
            if (focus(node)) {
                offered += visibleFocusables()
                focusedOutOfSight()?.let { hidden += "$node: $it" }
            }
            for (b in directions) {
                if (!focus(node)) { skipped += node; break }
                compose.press(b)
                val to = compose.focused()
                if (to != null && to !in a11y) a11yOfFocused()?.let { a11y[to] = it }
                edges += Edge(node, b, to)
                if (to != null && seen.add(to)) { queue += to; paths[to] = paths.getValue(node) + b }
            }
        }
        focus(start)
        return Graph(start, edges, skipped, a11y, offered, hidden)
    }

    private fun rootBounds(): androidx.compose.ui.geometry.Rect =
        compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** Why the focused element could not be seen (empty, or entirely off the screen), null if it can. */
    private fun focusedOutOfSight(): String? {
        val node = compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull() ?: return null
        val b = node.boundsInRoot
        val root = rootBounds()
        return when {
            b.width <= 0f || b.height <= 0f -> "empty bounds $b"
            b.right <= root.left || b.left >= root.right || b.bottom <= root.top || b.top >= root.bottom -> "off screen $b (screen $root)"
            else -> null
        }
    }

    /** The overlay a node is drawn in (the nearest ancestor tagged "overlay…"), null for Home itself. Home stays composed under an overlay but cannot take focus there. */
    private fun layerOf(node: SemanticsNode): String? {
        var n: SemanticsNode? = node
        while (n != null) {
            n.config.getOrNull(SemanticsProperties.TestTag)?.let { if (it.startsWith("overlay")) return it }
            n = n.parent
        }
        return null
    }

    private var startLayer: String? = null

    /** The enabled focusables of the start's layer that are on screen now. */
    private fun visibleFocusables(): List<String> {
        val root = rootBounds()
        return focusables().filter { n ->
            val b = n.boundsInRoot
            layerOf(n) == startLayer && n.config.getOrNull(SemanticsProperties.Disabled) == null && b.width > 0f && b.height > 0f &&
                b.right > root.left && b.left < root.right && b.bottom > root.top && b.top < root.bottom
        }.map { describe(it) }
    }

    /** The merged (TalkBack's) view of the focused element: its label and role. */
    private fun a11yOfFocused(): A11y? {
        val node = compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = false).fetchSemanticsNodes().firstOrNull() ?: return null
        fun texts(n: SemanticsNode): List<String> = buildList {
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
            n.config.getOrNull(SemanticsProperties.Text)?.let { addAll(it.map { t -> t.text }) }
            n.children.forEach { addAll(texts(it)) }
        }
        val own = (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
            .filter { it.isNotBlank() }
        val label = (own.ifEmpty { texts(node) }).joinToString(", ")
        return A11y(label, node.config.getOrNull(SemanticsProperties.Role)?.toString())
    }

    /** Moves focus to [name] (as [describe] names it): directly if it's on screen, else by replaying its key path. */
    fun focus(name: String): Boolean {
        if (compose.focused() == name) return true
        if (requestFocus(name)) return true
        val path = paths[name] ?: return false
        reset(); compose.waitForIdle()
        // Not focus(start): when the screen does not come back with the start focusable, replaying again would reset and loop for ever.
        if (compose.focused() != start && !requestFocus(start)) return false
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
