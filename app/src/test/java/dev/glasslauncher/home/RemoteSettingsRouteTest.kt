package dev.glasslauncher.home

import dev.glasslauncher.settings.SettingsEntry
import dev.glasslauncher.system.RemoteList
import dev.glasslauncher.system.RemoteRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteSettingsRouteTest {
    @After fun reset() { SettingsEntry.page = null }

    @Test fun remoteSettingsOpensSettingsOnRemoteButtonsNotTheControllersPage() {
        val opened = mutableListOf<Overlay>(); var closed = 0
        openRemoteSettings({ closed++ }, { opened += it })
        assertEquals(1, closed)
        assertEquals(listOf<Overlay>(Overlay.Settings), opened)
        assertEquals(SettingsEntry.REMOTE_BUTTONS, SettingsEntry.take())
        assertNull("one-shot", SettingsEntry.take())
    }

    @Test fun aRemoteThatIsAnInputDeviceButNotBondedIsStillListed() {
        val bonded = listOf(RemoteRow("AA", "Amazon Fire TV Remote", "Connected"))
        val rows = RemoteList.withInputOnly(bonded, setOf("Amazon Fire TV Remote", "Alexa Voice Remote"))
        assertEquals(listOf("Amazon Fire TV Remote", "Alexa Voice Remote"), rows.map { it.name })
        assertEquals("the bonded one is not listed twice", 1, rows.count { it.name == "Amazon Fire TV Remote" })
        assertEquals(true, rows.last().line.startsWith("Connected"))
    }
}
