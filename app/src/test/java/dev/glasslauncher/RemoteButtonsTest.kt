package dev.glasslauncher

import dev.glasslauncher.system.RemoteAction
import dev.glasslauncher.system.RemoteButtons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteButtonsTest {
    @org.junit.Before fun fireTv() { RemoteButtons.fireDefaults = { true } }
    @org.junit.After fun reset() { RemoteButtons.fireDefaults = { dev.glasslauncher.system.HomeSetup.isFireTv } }

    @Test fun remappedCodesKeepFireDefaults() {
        assertEquals(RemoteAction.OpenApp("com.netflix.ninja"), RemoteButtons.actionFor("KEYCODE_BUTTON_9", emptyMap()))
        assertEquals(RemoteAction.OpenApp("com.amazon.firebat"), RemoteButtons.actionFor("KEYCODE_BUTTON_10", emptyMap()))
        // Bottom row, as printed on the remote (verified with getevent on the stick): key 746 = Amazon Music, 747 = Disney+.
        assertEquals(RemoteAction.OpenApp("com.amazon.bueller.music"), RemoteButtons.actionFor("KEYCODE_BUTTON_11", emptyMap()))
        assertEquals(RemoteAction.OpenApp("com.disney.disneyplus"), RemoteButtons.actionFor("KEYCODE_BUTTON_12", emptyMap()))
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

    @org.junit.Test fun gamepadCodesAreRemoteButtonsOnlyFromAFireRemote() {
        val app1 = RemoteAction.OpenApp("com.netflix.ninja")
        assertEquals("the Fire remote", app1, RemoteButtons.actionFor("KEYCODE_BUTTON_9", emptyMap(), 0, "Fire TV Remote"))
        assertEquals("an unknown device is accepted as before", app1, RemoteButtons.actionFor("KEYCODE_BUTTON_9", emptyMap(), 0, null))
        assertEquals("a game controller on any TV box is not", null, RemoteButtons.actionFor("KEYCODE_BUTTON_9", emptyMap(), 0, "Xbox Wireless Controller"))
        assertEquals("the standard app keys stay generic", app1, RemoteButtons.actionFor("KEYCODE_APP_1", emptyMap(), 0, "Xbox Wireless Controller"))
    }

    @Test fun aControllerButtonDoesNothingOfGlassesUntilMapped() {
        val pad = "Xbox Wireless Controller"
        assertNull("unmapped: the app in front keeps it", RemoteButtons.actionFor("KEYCODE_BUTTON_A", emptyMap(), 0, pad))
        assertNull("mapped to Default is unmapped", RemoteButtons.actionFor("KEYCODE_BUTTON_START", mapOf("pad:start" to "default"), 0, pad))
        assertEquals(RemoteAction.ControlCenter, RemoteButtons.actionFor("KEYCODE_BUTTON_START", mapOf("pad:start" to "glass:control_center"), 0, pad))
        assertEquals(RemoteAction.Home, RemoteButtons.actionFor("KEYCODE_BUTTON_MODE", mapOf("pad:mode" to "glass:home"), 0, pad))
        assertEquals(RemoteAction.Nothing, RemoteButtons.actionFor("KEYCODE_BUTTON_L1", mapOf("pad:l1" to "none"), 0, pad))
    }

    @Test fun mappingAControllerButtonNeverChangesTheRemotesButtons() {
        val cfg = mapOf("pad:a" to "glass:home")
        assertEquals(RemoteAction.OpenApp("com.netflix.ninja"), RemoteButtons.actionFor("KEYCODE_BUTTON_9", cfg))
        assertEquals(RemoteButtons.pad.size, RemoteButtons.pad.map { it.id }.toSet().size)
        assertEquals(RemoteButtons.pad.first(), RemoteButtons.find("pad:a"))
        assertEquals(RemoteButtons.all.first(), RemoteButtons.find("app1"))
    }

    @Test fun noFireAppsAreAssumedOnOtherDevices() {
        RemoteButtons.fireDefaults = { false }
        assertNull("an unmapped app button keeps its system behaviour", RemoteButtons.actionFor("KEYCODE_BUTTON_9", emptyMap()))
        assertNull(RemoteButtons.actionFor("KEYCODE_APP_1", emptyMap(), 0, "Some Remote"))
        assertEquals("a mapped one still works", RemoteAction.Home, RemoteButtons.actionFor("KEYCODE_APP_1", mapOf("app1" to "glass:home"), 0, "Some Remote"))
        assertEquals("non-app defaults stay", RemoteAction.AppSwitcher, RemoteButtons.actionFor("KEYCODE_BUTTON_13", emptyMap()))
    }

    @Test fun aControllerButtonCanBeMappedForOneControllerOnly() {
        val cfg = mapOf(RemoteButtons.padKey("Xbox Wireless Controller", "start") to "glass:control_center")
        assertEquals(RemoteAction.ControlCenter, RemoteButtons.actionFor("KEYCODE_BUTTON_START", cfg, 0, "Xbox Wireless Controller"))
        assertNull("another controller keeps the default", RemoteButtons.actionFor("KEYCODE_BUTTON_START", cfg, 0, "8BitDo Pro 2"))
        // The one for every controller still applies where the controller has none of its own.
        val both = cfg + ("pad:start" to "glass:home")
        assertEquals(RemoteAction.Home, RemoteButtons.actionFor("KEYCODE_BUTTON_START", both, 0, "8BitDo Pro 2"))
        assertEquals(RemoteAction.ControlCenter, RemoteButtons.actionFor("KEYCODE_BUTTON_START", both, 0, "Xbox Wireless Controller"))
    }

    @Test fun aPerControllerIdFindsItsButton() {
        val b = RemoteButtons.find(RemoteButtons.padKey("Xbox Wireless Controller", "mode"))!!
        assertEquals("pad:Xbox Wireless Controller:mode", b.id)
        assertEquals("Guide", b.label)
        assertNull(RemoteButtons.find("pad:Xbox:nonsense"))
    }
}
