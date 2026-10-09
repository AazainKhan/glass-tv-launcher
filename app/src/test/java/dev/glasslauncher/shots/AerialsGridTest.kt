package dev.glasslauncher.shots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Choose Aerials with a long Landscape list: every row must be reachable and fully on screen, clear of Hide All. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class AerialsGridTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private val landscape = "A33A55D9-EDEA-4596-A850-6C10B54FBBB5"

    private fun seedCatalog(count: Int) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val items = (1..count).joinToString(",") { i ->
            val n = "%02d".format(i)
            """{"id":"clip$n","label":"Landscape $n","hd":"https://example.invalid/$n.mov","uhd":null,"shotId":"shot$n","categories":["$landscape"]}"""
        }
        File(context.filesDir, "aerials-v2.json").apply { writeText("[$items]"); setLastModified(System.currentTimeMillis()) }
    }

    private fun focusText(text: String) {
        val row = androidx.compose.ui.test.SemanticsMatcher("row '$text'") { n ->
            n.config.contains(SemanticsActions.RequestFocus) && (n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text == text } == true ||
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.any { it.startsWith(text) } == true)
        }
        compose.onAllNodes(row).onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.settle()
    }

    @Test fun everyLandscapeClipIsReachableAndOnScreen() {
        TvHarness.setUp()
        seedCatalog(30)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.focusTag("settings-tile"); compose.press(Button.Select); compose.settle()
            focusText("Screen Saver"); compose.press(Button.Select); compose.settle()
            focusText("Aerials"); compose.press(Button.Select); compose.settle()
            focusText("Choose Aerials"); compose.press(Button.Select); compose.settle()
            compose.press(Button.Down, Button.Down) // Cityscape -> Earth -> Landscape
            compose.settle()
            compose.press(Button.Right); compose.settle() // into the grid
            val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
            val hideAll = compose.onAllNodes(hasText("Hide All")).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            var last: String? = null
            for (step in 0 until 14) {
                last = compose.focused()
                compose.press(Button.Down); compose.settle()
            }
            // The last row is reached and everything of it (tile and caption) is clear of the page's bottom fade and
            // of the grid's own edge; and the grid starts clear of Hide All, scrolled or not.
            val grid = compose.onAllNodes(hasTestTag("aerials-grid"), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
            val label = compose.onAllNodes(hasText("Landscape 30"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            check(last in listOf("aerial:clip28", "aerial:clip29", "aerial:clip30")) { "never reached the last row: focus ended on $last" }
            val fadeStart = grid.bottom - 36f * density // the page fades its last 36 dp
            check(label != null && label.height > 0f && label.bottom <= fadeStart) { "the last caption ($label) runs into the bottom fade (from $fadeStart)" }
            check(grid.top - hideAll!!.bottom >= 8f * density) { "the grid starts only ${grid.top - hideAll.bottom} px under Hide All (want 8 dp)" }
        }
    }
}
