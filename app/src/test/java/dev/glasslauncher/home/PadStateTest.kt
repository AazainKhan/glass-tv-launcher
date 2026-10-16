package dev.glasslauncher.home

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PadStateTest {
    @Test fun buttonsAreHeldUntilReleased() {
        var s = PadState()
        assertTrue(s.idle)
        s = s.key(KeyEvent.KEYCODE_BUTTON_A, true).key(KeyEvent.KEYCODE_BUTTON_L1, true)
        assertEquals(setOf("A", "LB"), s.pressed)
        s = s.key(KeyEvent.KEYCODE_BUTTON_A, false)
        assertEquals(setOf("LB"), s.pressed)
        s = s.key(KeyEvent.KEYCODE_BUTTON_L1, false)
        assertTrue(s.idle)
    }

    @Test fun aWornStickInsideTheDeadZoneReadsCentred() {
        val s = PadState().axes(0.05f, -0.1f, 0.5f, -0.9f, 0.3f, 1f, -1f, 0f)
        assertEquals(0f, s.leftX, 0f)
        assertEquals(0f, s.leftY, 0f)
        assertEquals(0.5f, s.rightX, 0f)
        assertEquals(-0.9f, s.rightY, 0f)
        assertEquals(0.3f, s.leftTrigger, 0f)
        assertEquals(1f, s.rightTrigger, 0f)
        assertEquals(-1f, s.hatX, 0f)
        assertTrue(!s.idle)
    }
}
