package dev.glasslauncher

import dev.glasslauncher.system.RemoteAction
import dev.glasslauncher.system.RemoteButtons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteButtonsTest {
    @Test fun remappedCodesKeepFireDefaults() {
        assertEquals(RemoteAction.OpenApp("com.netflix.ninja"), RemoteButtons.actionFor("KEYCODE_BUTTON_9", emptyMap()))
        assertEquals(RemoteAction.OpenApp("com.amazon.firebat"), RemoteButtons.actionFor("KEYCODE_BUTTON_10", emptyMap()))
        assertEquals(RemoteAction.AppSwitcher, RemoteButtons.actionFor("KEYCODE_BUTTON_13", emptyMap()))
        assertEquals(RemoteAction.ControlCenter, RemoteButtons.actionFor("KEYCODE_UNKNOWN", emptyMap(), scanCode = 185))
        assertNull(RemoteButtons.actionFor("KEYCODE_UNKNOWN", emptyMap(), scanCode = 999))
    }

    @Test fun userMappingWins() {
        val cfg = mapOf("app1" to "app:org.videolan.vlc", "recents" to "none", "app2" to "default")
        assertEquals(RemoteAction.OpenApp("org.videolan.vlc"), RemoteButtons.actionFor("KEYCODE_BUTTON_9", cfg))
        assertEquals(RemoteAction.Nothing, RemoteButtons.actionFor("KEYCODE_BUTTON_13", cfg))
        // "default" means the button's own Fire TV behaviour.
        assertEquals(RemoteAction.OpenApp("com.amazon.firebat"), RemoteButtons.actionFor("KEYCODE_BUTTON_10", cfg))
    }

    @Test fun otherKeysPassThrough() {
        assertNull(RemoteButtons.actionFor("KEYCODE_DPAD_DOWN", emptyMap()))
        assertNull(RemoteButtons.actionFor("KEYCODE_HOME", emptyMap()))
    }

    @Test fun actionsRoundTrip() {
        listOf(RemoteAction.Default, RemoteAction.Nothing, RemoteAction.Home, RemoteAction.ControlCenter, RemoteAction.AppSwitcher, RemoteAction.TvSettings, RemoteAction.OpenApp("a.b"))
            .forEach { assertEquals(it, RemoteAction.parse(it.key)) }
    }
}
