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
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import dev.glasslauncher.data.MappingProfiles
import org.junit.Assert.assertNull
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
import dev.glasslauncher.home.EditorSlots
import dev.glasslauncher.home.PadLabels
import dev.glasslauncher.home.PageKind
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
                DevicePage(kind, name, cfg, { edit -> cfg = edit(cfg) }, active = true, openApp = { opened += it })
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

    private val xbox = "Xbox Wireless Controller"

    @Test fun theEditorListsEveryMappableButtonOnce() {
        val pad = EditorSlots.of(PageKind.Gamepad).filterNot { it.isStick }.map { it.id }
        assertEquals(RemoteButtons.pad.map { it.id }.sorted(), pad.sorted())
        assertEquals(RemoteButtons.all.map { it.id }.sorted(), EditorSlots.of(PageKind.Remote).map { it.id }.sorted())
    }

    @Test fun theControllerPageLaysOutEveryCapOnScreenWithoutOverlapAndTheGridLeftOfThePane() {
        show(PageKind.Gamepad, xbox)
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val caps = EditorSlots.of(PageKind.Gamepad).map { it.id to bounds("cap:${it.id}") }
        for ((n, b) in caps) assertTrue("$n inside the screen: $b", b.left >= screen.left && b.right <= screen.right && b.top >= screen.top && b.bottom <= screen.bottom)
        for (i in caps.indices) for (j in i + 1 until caps.size) assertTrue("${caps[i].first} overlaps ${caps[j].first}", !caps[i].second.overlaps(caps[j].second))
        val pane = bounds("action-pane")
        assertTrue("the grid is left of the pane", caps.all { it.second.right <= pane.left })
        assertTrue("the profile bar clears the caps", caps.none { it.second.overlaps(bounds("profile-saveas")) })
        assertTrue(exists("device-switch"))
    }

    @Test fun theFocusedCapsActionIsShownAndAChoiceSetsItForThisControllerOnly() {
        show(PageKind.Gamepad, xbox)
        compose.onNodeWithTag("cap:pad:start").performSemanticsAction(SemanticsActions.RequestFocus); compose.waitForIdle()
        compose.onNodeWithTag("action-current").assertTextEquals("Does: Default")
        press("option:glass:home")
        assertEquals("glass:home", cfg.remoteButtons[RemoteButtons.padKey(xbox, "start")])
        assertNull("another controller is untouched", cfg.remoteButtons[RemoteButtons.padKey("Pad B", "start")])
        compose.onNodeWithTag("action-current").assertTextEquals("Does: Home")
    }

    @Test fun selectOnACapStepsIntoThePickerAndOpenAnotherAppAsksForTheButtonsKey() {
        show(PageKind.Gamepad, xbox)
        press("cap:pad:b")
        compose.onNodeWithTag("option:default").assertIsFocused()
        press("option:app")
        assertEquals(listOf(RemoteButtons.padKey(xbox, "b")), opened)
    }

    @Test fun theSticksHaveATuningPaneWithSlidersThatMoveWithTheDpadAndReset() {
        show(PageKind.Gamepad, "Pad A")
        compose.onNodeWithTag("cap:stick:left").performSemanticsAction(SemanticsActions.RequestFocus); compose.waitForIdle()
        compose.onNodeWithTag("stick-slider:x").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(0.14f, cfg.sticksOf("Pad A").left.deadzoneX, 0.001f)
        assertEquals("another controller is untouched", 0.12f, cfg.sticksOf("Pad B").left.deadzoneX, 0.001f)
        press("stick-reset")
        assertEquals(0.12f, cfg.sticksOf("Pad A").left.deadzoneX, 0.001f)
    }

    @Test fun theRightSticksSensitivityHasItsOwnValues() {
        show(PageKind.Gamepad, "Pad A")
        compose.onNodeWithTag("cap:stick:right").performSemanticsAction(SemanticsActions.RequestFocus); compose.waitForIdle()
        press("stick-tab:Sensitivity")
        compose.onNodeWithTag("stick-slider:y").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        val t = cfg.sticksOf("Pad A")
        assertEquals(1.05f, t.right.sensY, 0.001f)
        assertEquals("the left stick is untouched", 1f, t.left.sensY, 0.001f)
    }

    @Test fun theRemotePageHasACapForEveryButtonAndAChoiceSetsItsSharedKey() {
        show(PageKind.Remote, "Amazon Fire TV Remote")
        for (b in RemoteButtons.all) assertTrue("${b.id} has a cap", exists("cap:${b.id}"))
        compose.onNodeWithTag("cap:app1").performSemanticsAction(SemanticsActions.RequestFocus); compose.waitForIdle()
        press("option:none")
        assertEquals("none", cfg.remoteButtons["app1"])
    }

    @Test fun goldenGamepad() {
        show(PageKind.Gamepad, xbox)
        cfg = cfg.copy(remoteButtons = mapOf(RemoteButtons.padKey(xbox, "a") to "glass:home", RemoteButtons.padKey(xbox, "y") to "glass:switcher"))
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("device-gamepad"))
    }

    @Test fun goldenGamepadSticks() {
        show(PageKind.Gamepad, xbox)
        compose.onNodeWithTag("cap:stick:left").performSemanticsAction(SemanticsActions.RequestFocus); compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("device-gamepad-sticks"))
    }

    @Test fun goldenRemote() {
        show(PageKind.Remote, "Amazon Fire TV Remote")
        compose.onRoot().captureRoboImage(shot("device-remote"))
    }

    /** P233: Save As asks for a name (default "Profile 1"), keeps the mapping under it; Save updates it; Delete drops it. */
    @Test fun theProfileBarSavesWithANameAndDeletes() {
        show(PageKind.Gamepad, xbox)
        cfg = cfg.copy(remoteButtons = mapOf(RemoteButtons.padKey(xbox, "a") to "glass:home")); compose.waitForIdle()
        press("profile-saveas")
        compose.onNodeWithTag("profile-name").performTextReplacement("Racing")
        compose.onNodeWithTag("profile-name").performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("Racing"), cfg.mappingProfiles["pad"]!!.map { it.name })
        assertEquals("Racing", cfg.activeProfile[xbox])
        cfg = cfg.copy(remoteButtons = emptyMap()); compose.waitForIdle()
        compose.onNodeWithTag("profile-pick").assertContentDescriptionEquals("Racing (changed)")
        press("profile-save")
        assertTrue(MappingProfiles.of(cfg, dev.glasslauncher.data.ProfileKind.Pad).single().buttons.isEmpty())
        press("profile-delete")
        assertTrue(cfg.mappingProfiles["pad"]!!.isEmpty())
    }

    @Test fun leavingTheNameEmptyKeepsTheDefaultProfileName() {
        show(PageKind.Gamepad, xbox)
        press("profile-saveas")
        compose.onNodeWithTag("profile-name").performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("Profile 1"), cfg.mappingProfiles["pad"]!!.map { it.name })
    }
}
