package dev.glasslauncher.shots

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * The screen-reader check (P166): every focusable element on screen, as TalkBack sees it (the merged tree), has a spoken label (its
 * description, else its text, else its children's text) and a role. Returns the offenders, named by test tag when there is one, so a
 * failing surface says which element to fix.
 */
object A11yAudit {
    private fun texts(n: SemanticsNode): List<String> = buildList {
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
        n.config.getOrNull(SemanticsProperties.Text)?.let { addAll(it.map { t -> t.text }) }
        n.children.forEach { addAll(texts(it)) }
    }

    fun label(n: SemanticsNode): String {
        val own = (n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
            .filter { it.isNotBlank() }
        return own.ifEmpty { texts(n).filter { it.isNotBlank() } }.joinToString(", ")
    }

    private fun name(n: SemanticsNode): String {
        var p: SemanticsNode? = n
        while (p != null) { p.config.getOrNull(SemanticsProperties.TestTag)?.let { return it }; p = p.parent }
        return "node#${n.id}"
    }

    /** "name: problem" for every focusable without a label or a role. */
    fun problems(compose: ComposeTestRule): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused), useUnmergedTree = false).fetchSemanticsNodes().mapNotNull { n ->
            val l = label(n)
            when {
                l.isBlank() -> "${name(n)}: no spoken label"
                n.config.getOrNull(SemanticsProperties.Role) == null -> "${name(n)} ('$l'): no role"
                else -> null
            }
        }

    /** The pane titles on screen: what a screen reader announces when a page, panel or sheet appears. */
    fun paneTitles(compose: ComposeTestRule): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle), useUnmergedTree = false).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.PaneTitle) }
}
