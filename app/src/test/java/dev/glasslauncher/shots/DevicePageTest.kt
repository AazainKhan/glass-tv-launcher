package dev.glasslauncher.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.home.DevicePage
import dev.glasslauncher.home.PadGeometry
import dev.glasslauncher.home.PadLabels
import dev.glasslauncher.home.PageKind
import dev.glasslauncher.home.RemoteGeometry
import dev.glasslauncher.home.sticksOf
import dev.glasslauncher.system.RemoteButtons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P186: the device pages. The geometry is pure; the layout and the stick sliders run in Compose. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class DevicePageTest {
    @get:Rule val compose = createComposeRule()
    private var cfg by mutableStateOf(LauncherConfig())
    private val opened = mutableListOf<String>()

    private fun show(kind: PageKind, name: String) {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF181A20))) {
                DevicePage(kind, name, cfg, { edit -> cfg = edit(cfg) }, active = true, openMapping = { opened += it })
            }
        }
        compose.waitForIdle()
        // The page asks for its first focus after a frame: let that happen before a test moves focus itself.
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun press(tag: String, key: Key = Key.DirectionCenter) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test fun everyPadButtonHasAnAnchorOnTheDrawingAndALabel() {
        val all = RemoteButtons.pad.map { PadLabels.shortName(it.id.removePrefix("pad:")) }.toSet()
        assertEquals("the right column and the bottom row label every mappable button exactly once", all, (PadGeometry.rightColumn + PadGeometry.bottomRow).toSet())
        assertEquals(PadGeometry.rightColumn.size + PadGeometry.bottomRow.size, all.size)
        for ((n, o) in PadGeometry.anchors) assertTrue("$n anchor $o inside the drawing", o.x in 0f..PadGeometry.W && o.y in 0f..PadGeometry.H)
        for ((n, o) in RemoteGeometry.anchors) assertTrue("$n anchor $o inside the remote", o.x in 0f..RemoteGeometry.W && o.y in 0f..RemoteGeometry.H)
        assertTrue("every mappable remote button is drawn", RemoteButtons.all.all { it.id in RemoteGeometry.anchors })
    }

    @Test fun theControllerPageLaysOutEveryLabelOnScreenWithoutOverlap() {
        show(PageKind.Gamepad, "Xbox Wireless Controller")
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val labels = (PadGeometry.rightColumn + PadGeometry.bottomRow).map { it to bounds("pad-label:$it") }
        for ((n, b) in labels) assertTrue("$n inside the screen: $b", b.left >= screen.left && b.right <= screen.right && b.top >= screen.top && b.bottom <= screen.bottom)
        for (i in labels.indices) for (j in i + 1 until labels.size) {
            val a = labels[i].second; val b = labels[j].second
            assertTrue("${labels[i].first} overlaps ${labels[j].first}", a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
        }
        val panel = bounds("stick-panel")
        assertTrue("the stick panel is left of every label", labels.all { it.second.left >= panel.right })
        assertTrue(exists("device-switch") && exists("stick-reset") && exists("stick-circle"))
    }

    @Test fun aLabelOpensItsButtonsOwnMappingForThisController() {
        show(PageKind.Gamepad, "Xbox Wireless Controller")
        press("pad-label:Start")
        assertEquals(listOf(RemoteButtons.padKey("Xbox Wireless Controller", "start")), opened)
    }

    @Test fun theSticksSlidersMoveWithTheDpadAndResetRestoresTheDefaults() {
        show(PageKind.Gamepad, "Pad A")
        compose.onNodeWithTag("stick-slider:x").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(0.14f, cfg.sticksOf("Pad A").left.deadzoneX, 0.001f)
        assertEquals("another controller is untouched", 0.12f, cfg.sticksOf("Pad B").left.deadzoneX, 0.001f)
        press("stick-reset")
        assertEquals(0.12f, cfg.sticksOf("Pad A").left.deadzoneX, 0.001f)
    }

    @Test fun theRightStickAndTheSensitivityTabHaveTheirOwnValues() {
        show(PageKind.Gamepad, "Pad A")
        press("stick-which")
        press("stick-tab:Sensitivity")
        compose.onNodeWithTag("stick-slider:y").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        val t = cfg.sticksOf("Pad A")
        assertEquals(1.05f, t.right.sensY, 0.001f)
        assertEquals("the left stick is untouched", 1f, t.left.sensY, 0.001f)
    }

    @Test fun theRemotePageLabelsEveryMappableButton() {
        show(PageKind.Remote, "Amazon Fire TV Remote")
        for (b in RemoteButtons.all) assertTrue("${b.id} labelled", exists("remote-label:${b.id}"))
        press("remote-label:app1")
        assertEquals(listOf("app1"), opened)
    }

    @Test fun goldenGamepad() {
        show(PageKind.Gamepad, "Xbox Wireless Controller")
        compose.onRoot().captureRoboImage(shot("device-gamepad"))
    }

    @Test fun goldenRemote() {
        show(PageKind.Remote, "Amazon Fire TV Remote")
        compose.onRoot().captureRoboImage(shot("device-remote"))
    }
}
