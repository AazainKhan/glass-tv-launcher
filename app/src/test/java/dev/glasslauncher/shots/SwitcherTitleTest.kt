package dev.glasslauncher.shots

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/** P61: in the app switcher, after Up the closed app's title fades out as its card leaves; it doesn't change a frame early. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class SwitcherTitleTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private fun count(tagPrefix: String) = compose.onAllNodes(
        androidx.compose.ui.test.SemanticsMatcher("tag $tagPrefix") { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(tagPrefix) == true
        }, useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    @Test fun theClosedAppsTitleStaysAsItsCardLeaves() {
        TvHarness.setUp(config = { c ->
            c.copy(recentApps = listOf("com.netflix.ninja", "com.amazon.firetv.youtube", "com.stremio.one", "com.plexapp.android"))
        })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            scenario.onActivity { activity ->
                val intent = android.content.Intent(MainActivity.ACTION_APP_SWITCHER)
                MainActivity::class.java.getDeclaredMethod("onNewIntent", android.content.Intent::class.java)
                    .apply { isAccessible = true }.invoke(activity, intent)
            }
            compose.settle()
            check(compose.onAllNodes(hasTestTag("app-switcher")).fetchSemanticsNodes().isNotEmpty()) { "the switcher didn't open" }
            compose.mainClock.autoAdvance = false
            try {
                compose.onRoot().performKeyInput { pressKey(Button.Up.key) }
                var cardGoneAt = -1
                var titleGoneAt = -1
                var titleFrames = 0
                repeat(60) { f ->
                    compose.mainClock.advanceTimeBy(16)
                    val card = count("switcher-leaving:")
                    val title = count("switcher-title-leaving")
                    if (title > 0) titleFrames++
                    if (cardGoneAt < 0 && card == 0 && f > 0) cardGoneAt = f
                    if (titleGoneAt < 0 && title == 0 && f > 0) titleGoneAt = f
                }
                assertTrue("the card never left", cardGoneAt > 0)
                assertTrue("the old title never left", titleGoneAt > 0)
                assertTrue("the old title was only on screen $titleFrames frames", titleFrames >= 8)
                assertTrue("the title left at frame $titleGoneAt, the card at $cardGoneAt", abs(titleGoneAt - cardGoneAt) <= 2)
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
    }
}
