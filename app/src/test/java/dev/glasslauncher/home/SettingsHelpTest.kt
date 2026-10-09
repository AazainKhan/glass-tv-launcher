package dev.glasslauncher.home

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.shots.TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The words under a Settings page's icon change as focus moves between rows. The icon above them must not
 * move whatever the length of the words (P41), and a change must be a fade-through (P42).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class SettingsHelpTest {
    @get:Rule val compose = createComposeRule()

    private var help by mutableStateOf<String?>(null)
    private val page = "page"

    private val short = "Short."
    private val long = (1..40).joinToString(" ") { "word$it" } // far more lines than the slot's height

    private fun show() {
        compose.setContent {
            SettingsPage(active = true) {
                val sink = LocalTitleSink.current!!
                LaunchedEffect(Unit) { sink.title = "Appearance"; sink.page = page }
                LaunchedEffect(help) { sink.help = help?.let { page to it } }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun iconTop(): Float = compose.onNodeWithTag("settings-icon:Appearance").fetchSemanticsNode().boundsInRoot.top

    @Test fun theIconDoesNotMoveWhateverTheLengthOfTheWords() {
        show()
        val nothing = iconTop()
        for (words in listOf(short, long, null, long, short)) {
            help = words
            compose.mainClock.advanceTimeBy(600)
            assertEquals("the icon moved with ${words?.length ?: 0} characters of help", nothing, iconTop(), 0.5f)
        }
    }

    /** The left and right edges of every text node in the slot (the node tagged settings-help's descendants). */
    private fun textEdges(): List<Pair<Float, Float>> =
        compose.onAllNodes(androidx.compose.ui.test.hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text.startsWith("First") || it.text.startsWith("A quite") } == true }
            .map { it.boundsInRoot.left to it.boundsInRoot.right }

    @Test fun aChangeNeverSlidesSideways() {
        show()
        help = "First words."
        compose.mainClock.advanceTimeBy(600)
        val rest = textEdges().single()
        compose.mainClock.autoAdvance = false
        help = "A quite different line of words that is much longer than the first one."
        // Frame by frame, while both texts are on screen and after: every text keeps the same left and right
        // edges (the slot's width), so nothing can drift or re-centre when the old text leaves.
        var sawBoth = false
        repeat(24) {
            compose.mainClock.advanceTimeBy(16)
            if (textEdges().size == 2) sawBoth = true
            for (edges in textEdges()) {
                assertEquals("left edge moved at frame $it", rest.first, edges.first, 0.5f)
                assertEquals("right edge moved at frame $it", rest.second, edges.second, 0.5f)
            }
        }
        assertTrue("the old and the new text should be on screen together for a while (a cross-fade)", sawBoth)
    }

    @Test fun veryLongHelpIsBoundedToFourLines() {
        show()
        help = long
        compose.mainClock.advanceTimeBy(600)
        val node = compose.onAllNodes(androidx.compose.ui.test.hasText("word1", substring = true), useUnmergedTree = true).fetchSemanticsNodes().single()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val lines = results.single().lineCount
        assertTrue("the help shows $lines lines", lines <= 4)
    }
}
