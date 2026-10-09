package dev.glasslauncher.ui

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysTest {
    @Test fun remoteKeyboardAndGamepadAllSelect() {
        for (k in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A))
            assertTrue(KeyEvent.keyCodeToString(k), isSelectKey(k))
        assertFalse(isSelectKey(KeyEvent.KEYCODE_BACK))
    }
}
