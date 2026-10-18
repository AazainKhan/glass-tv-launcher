package dev.glasslauncher.shots

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import dev.glasslauncher.home.CcOpenReads
import dev.glasslauncher.system.RemoteNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P203: Home keeps Control Center composed while it is closed (parked) so it opens without composing ~20 controls.
 * Parked, it must be invisible to everything and do nothing: no banner hold, no open-time reads, no focus, nothing
 * in the semantics tree. Opened, it works as before; closed again, it goes back to idle.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcParkTest {

    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private fun ccNodes() = compose.onAllNodes(hasTestTag("control-center")).fetchSemanticsNodes().size // the merged tree: what TalkBack reads

    private fun assertIdle(why: String, readsBefore: Int) {
        assertFalse("$why: a parked Control Center holds device banners back", RemoteNotice.controlCenterOpen)
        assertEquals("$why: a parked Control Center read Wi-Fi, Bluetooth, network or root", readsBefore, CcOpenReads.count)
        assertEquals("$why: a parked Control Center is in the semantics tree", 0, ccNodes())
        val focused = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$why: focus left Home ($focused)", focused.isNotEmpty())
    }

    @Test fun parkedControlCenterIsIdleAndInvisible() {
        TvHarness.setUp()
        CcOpenReads.count = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            val reads = 0
            // Past the park delay: Control Center is composed now, hidden.
            compose.settle()
            assertIdle("after parking", reads)
            // Home's own focus moves still work and never land in it.
            compose.press(Button.Right, Button.Left)
            compose.settle()
            assertIdle("after Home focus moves", reads)

            // Open: the same tree comes forward and does its open-time work.
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            assertTrue("open: Control Center holds device banners", RemoteNotice.controlCenterOpen)
            assertTrue("open: Control Center read its state as it opened", CcOpenReads.count > reads)

            // Close: parked again, idle again.
            // The harness's Back key doesn't reach the activity's dispatcher (see MotionStrips).
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.settle()
            val afterClose = CcOpenReads.count
            compose.press(Button.Right, Button.Left)
            compose.settle()
            assertIdle("after closing", afterClose)
        }
    }
}
