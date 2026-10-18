package dev.glasslauncher.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.R
import dev.glasslauncher.home.CC_PANEL_TOP
import dev.glasslauncher.home.CcPairPlatter
import dev.glasslauncher.home.CcSizes
import dev.glasslauncher.home.ccPanelSlot
import dev.glasslauncher.system.BtFound
import dev.glasslauncher.system.BtKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P174 / P176: the pairing page, a controller's own page, and the shared gap under the header. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcDevicePagesTest {
    @get:Rule val compose = createComposeRule()
    private val sz = CcSizes(1f)
    private fun dp(px: Float) = px / compose.density.density
    private fun press(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun everySubPanelStartsTheSameGapBelowTheHeader() {
        compose.setContent { Box { Box(Modifier.ccPanelSlot()) { Box(Modifier.size(40.dp).testTag("content")) } } }
        compose.waitForIdle()
        assertEquals(CC_PANEL_TOP.value, dp(bounds("content").top), 0.6f)
        assertTrue("a real tvOS gap, not the 2 dp the platters used to have", CC_PANEL_TOP >= 16.dp)
    }

    @Test fun pairPageListsWhatIsNearbyAndPairsOnSelect() {
        var picked: String? = null
        compose.setContent {
            CcPairPlatter(sz, "Pair a Controller", "pair", listOf(BtFound("AA", "8BitDo Pro 2", BtKind.Controller), BtFound("BB", "Xbox Controller", BtKind.Controller, bonding = true)),
                scanning = true, canScan = true, deviceIcon = R.drawable.ic_sports_esports, onPair = { picked = it.address }, onSystem = {})
        }
        compose.waitForIdle()
        compose.onNodeWithTag("cc-pair-status").assertIsDisplayed()
        assertEquals(sz.column.value, dp(bounds("cc-pair-0").width), 0.8f)
        press("cc-pair-0")
        assertEquals("AA", picked)
    }

    @Test fun pairPageFallsBackToTheSystemBluetoothPageWhenGlassCannotScan() {
        var system = 0
        compose.setContent {
            CcPairPlatter(sz, "Pair a Remote", "pair", emptyList(), scanning = false, canScan = false, deviceIcon = R.drawable.ic_settings_remote, onPair = {}, onSystem = { system++ })
        }
        compose.waitForIdle()
        press("cc-pair-system")
        assertEquals(1, system)
        assertEquals(0, compose.onAllNodes(hasTestTag("cc-pair-status")).fetchSemanticsNodes().size)
    }
}
