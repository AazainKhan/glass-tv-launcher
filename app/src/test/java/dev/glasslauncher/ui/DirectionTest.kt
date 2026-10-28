package dev.glasslauncher.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** P308: a Left or Right key as a step along reading order. */
class DirectionTest {
    @Test fun leftToRightLayoutsStepWithTheKey() {
        assertEquals(1, horizontalStep(KeyEvent.KEYCODE_DPAD_RIGHT, rtl = false))
        assertEquals(-1, horizontalStep(KeyEvent.KEYCODE_DPAD_LEFT, rtl = false))
    }

    @Test fun rightToLeftLayoutsStepAgainstIt() {
        assertEquals(-1, horizontalStep(KeyEvent.KEYCODE_DPAD_RIGHT, rtl = true))
        assertEquals(1, horizontalStep(KeyEvent.KEYCODE_DPAD_LEFT, rtl = true))
    }

    @Test fun otherKeysDoNotStep() {
        assertEquals(0, horizontalStep(KeyEvent.KEYCODE_DPAD_UP, rtl = true))
        assertEquals(0, horizontalStep(KeyEvent.KEYCODE_ENTER, rtl = false))
    }
}
