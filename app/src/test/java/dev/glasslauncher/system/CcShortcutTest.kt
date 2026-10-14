package dev.glasslauncher.system

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CcShortcutTest {
    @Test fun theDefaultIsHoldingMenuAndAnUnknownStoredValueFallsBackToIt() {
        assertEquals(CcShortcut.MenuHold, CcShortcut.DEFAULT)
        assertEquals(CcShortcut.MenuHold, CcShortcut.parse(null))
        assertEquals(CcShortcut.MenuHold, CcShortcut.parse("something old"))
        assertEquals(CcShortcut.Off, CcShortcut.parse("off"))
        CcShortcut.entries.forEach { assertEquals(it, CcShortcut.parse(it.key)) }
    }

    @Test fun onlyTheChosenKeyMatchesAndOffMatchesNothing() {
        assertTrue(CcShortcut.MenuHold.matches(KeyEvent.KEYCODE_MENU))
        assertFalse(CcShortcut.MenuHold.matches(KeyEvent.KEYCODE_BACK))
        assertTrue(CcShortcut.PlayPauseHold.matches(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(CcShortcut.SettingsKey.matches(KeyEvent.KEYCODE_SETTINGS))
        assertFalse("Off never matches, even the unknown key code", CcShortcut.Off.matches(KeyEvent.KEYCODE_UNKNOWN))
    }

    @Test fun holdsHaveATimeAndTheSettingsKeyIsAPlainPress() {
        assertTrue(CcShortcut.MenuHold.hold && CcShortcut.MenuHold.holdMs in 500..1_000)
        assertTrue(CcShortcut.PlayPauseHold.hold)
        assertFalse(CcShortcut.SettingsKey.hold)
        assertFalse(CcShortcut.Off.hold)
    }
}
