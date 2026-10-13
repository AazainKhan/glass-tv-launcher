package dev.glasslauncher.shots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.R
import dev.glasslauncher.home.CcDevicePlatter
import dev.glasslauncher.home.CcDeviceRow
import dev.glasslauncher.home.CcSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P146: every device platter (Remotes, Game Controllers) follows one rule: a device or info pill is the full column wide, and the
 * Settings and Back actions are a pair of half-width pills on one row, one grid gap apart.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcDevicePlatterTest {
    @get:Rule val compose = createComposeRule()
    private val sz = CcSizes(1f)

    private fun show(rows: List<CcDeviceRow>, settings: String, empty: String = "Nothing here") {
        compose.setContent { CcDevicePlatter(sz, "Test", "platter", rows, R.drawable.ic_settings_remote, empty, null, settings, {}, {}) }
        compose.waitForIdle()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun dp(px: Float) = px / compose.density.density

    private fun check(settings: String) {
        val settingsB = bounds("cc-action-settings"); val backB = bounds("cc-action-back")
        assertEquals("the two actions are on one row", settingsB.top, backB.top, 0.5f)
        assertEquals("equal half widths", settingsB.width, backB.width, 0.5f)
        assertEquals("half width", sz.half.value, dp(settingsB.width), 0.6f)
        assertEquals("one grid gap between them", sz.gap.value, dp(backB.left - settingsB.right), 0.6f)
        assertTrue("Settings is the left one", settingsB.left < backB.left)
        assertEquals("the pair spans the column", sz.column.value, dp(backB.right - settingsB.left), 0.8f)
        // The action label fits its half pill (a title with no value wraps at its spaces, never cut).
        val cut = compose.onAllNodes(hasAnyAncestor(hasTestTag("cc-action-settings")).and(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)), useUnmergedTree = true)
            .fetchSemanticsNodes().any { n ->
                val rs = mutableListOf<TextLayoutResult>(); n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(rs)
                rs.firstOrNull()?.let { it.hasVisualOverflow } == true
            }
        assertTrue("'$settings' is cut off in a half pill", !cut)
    }

    @Test fun remotesDevicePillIsFullWidthAndTheActionsAreHalves() {
        show(listOf(CcDeviceRow("Amazon Fire TV Remote", "Connected · Battery 77%", true)), "Remote Settings")
        assertEquals("device pill is the column wide", sz.column.value, dp(bounds("cc-device-0").width), 0.6f)
        assertTrue("the actions sit under the device", bounds("cc-action-settings").top > bounds("cc-device-0").bottom)
        check("Remote Settings")
    }

    @Test fun controllersListEveryDeviceFullWidthThenTheHalves() {
        show(listOf(CcDeviceRow("Xbox Wireless Controller", "Connected · Battery 60%", true), CcDeviceRow("8BitDo Pro 2", "Connected", true)), "Controller Settings")
        for (i in 0..1) assertEquals(sz.column.value, dp(bounds("cc-device-$i").width), 0.6f)
        assertTrue(bounds("cc-device-1").top > bounds("cc-device-0").bottom)
        check("Controller Settings")
    }

    @Test fun theEmptyStateIsAFullWidthInfoPill() {
        show(emptyList(), "Controller Settings", "No controllers connected")
        assertEquals(sz.column.value, dp(bounds("cc-device-empty").width), 0.6f)
        check("Controller Settings")
    }
}
