package dev.glasslauncher.system

import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * A Compose window's semantics tree in uiautomator's XML format, for the device tests (DebugDumpReceiver).
 * Shaped like uiautomator's dump of the same screen: a row that merges its children (a menu row, a tile)
 * carries their description, and its text children still appear under it as their own nodes. Bounds are
 * the drawn bounds (uiautomator pads small targets up to 48 dp).
 */
object SemanticsDump {
    fun xml(root: RootForTest): String {
        val owner = root.semanticsOwner
        val merged = HashMap<Int, SemanticsNode>()
        fun index(n: SemanticsNode) { merged[n.id] = n; n.children.forEach(::index) }
        index(owner.rootSemanticsNode)
        val out = StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">")
        fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun walk(n: SemanticsNode, index: Int) {
            // Scrolled out of view (a long settings page): empty bounds. uiautomator leaves these out, and
            // steering toward their empty corner sent focus the wrong way.
            if (n.boundsInWindow.width <= 0f || n.boundsInWindow.height <= 0f) return
            val own = n.config
            val merging = own.isMergingSemanticsOfDescendants
            val c = if (merging) merged[n.id]?.config ?: own else own
            val tag = own.getOrNull(SemanticsProperties.TestTag).orEmpty()
            val desc = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
            // A merging row with a description shows it as its label; its texts stay on the child nodes.
            val text = if (merging && desc.isNotEmpty()) "" else own.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
            val focused = own.getOrNull(SemanticsProperties.Focused) == true
            val focusable = own.contains(SemanticsActions.RequestFocus)
            val b = n.boundsInWindow
            out.append("<node index=\"$index\" text=\"${esc(text)}\" resource-id=\"${esc(tag)}\" class=\"android.view.View\" package=\"dev.glasslauncher\" content-desc=\"${esc(desc)}\" focusable=\"$focusable\" focused=\"$focused\" bounds=\"[${b.left.toInt()},${b.top.toInt()}][${b.right.toInt()},${b.bottom.toInt()}]\">")
            n.children.forEachIndexed { i, child -> walk(child, i) }
            out.append("</node>")
        }
        walk(owner.unmergedRootSemanticsNode, 0)
        return out.append("</hierarchy>").toString()
    }
}
