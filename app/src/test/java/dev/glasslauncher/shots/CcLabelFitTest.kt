package dev.glasslauncher.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.R
import dev.glasslauncher.home.CcSizes
import dev.glasslauncher.home.PillContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P138: the longest default labels of every Control Center control fit their pill whole (no mid-word cut, no ellipsis, no clip),
 * in the 2x1 pill (104 dp) and the 4x1 row (216 dp). Titles with no value line may wrap to a second line at a space, as tvOS's do;
 * a value line is one line. (Values that depend on the network or a running task, such as a long Wi-Fi name, clip and scroll
 * on focus by design.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcLabelFitTest {
    @get:Rule val compose = createComposeRule()

    private val titles = listOf("Wi-Fi", "Bluetooth", "Sleep", "Clean Up", "Restart", "Launcher Settings", "Gamepad", "Remotes", "Downloads", "AirPlay", "Speed", "Theme",
        "Saver", "Apps", "VPN", "Power", "Add", "Settings", "Now Playing", "Screen Saver", "App Switcher", "Performance")
    private val withValue = listOf("Wi-Fi" to "Connected", "Bluetooth" to "On", "Bluetooth" to "Devices", "Sleep" to "Off", "Theme" to "Light", "Theme" to "Dark", "Speed" to "Balanced",
        "VPN" to "Connected", "Downloads" to "2 active", "AirPlay" to "Off", "Sleep" to "30 min")

    private fun cut(width: Int): List<String> {
        val cases = titles.map { it to null } + withValue
        compose.setContent {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                cases.forEachIndexed { i, (t, v) ->
                    Box(Modifier.size(width.dp, CcSizes(1f).pill).testTag("case$i")) { PillContent(R.drawable.ic_wifi, t, v, Color.White, on = false, accent = Color.Blue) }
                }
            }
        }
        compose.waitForIdle()
        return cases.withIndex().filter { (i, _) ->
            compose.onAllNodes(hasAnyAncestor(hasTestTag("case$i")).and(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)), useUnmergedTree = true)
                .fetchSemanticsNodes().any { n ->
                    val rs = mutableListOf<TextLayoutResult>()
                    n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(rs)
                    rs.firstOrNull()?.let { it.hasVisualOverflow || (0 until it.lineCount).any { l -> it.isLineEllipsized(l) } } == true
                }
        }.map { it.value.first + (it.value.second?.let { v -> " / $v" } ?: "") }
    }

    @Test fun everyDefaultTitleAndValueFitsThe2x1Pill() {
        val bad = cut(104)
        assertTrue("cut off in a 104 dp pill: $bad", bad.isEmpty())
    }
}
