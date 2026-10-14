package dev.glasslauncher.sonos

import dev.glasslauncher.data.AudioOutputConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P148: tvOS's unified picker: one list grouped by role, per-device glyphs, Bluetooth single-sink, no transport sections. */
class OutputPickerTest {
    private val kitchen = SonosSpeaker("10.0.0.5", "Kitchen", "Sonos Roam")
    private val buds = BtOutput("AA:01", "Pixel Buds", BtRole.Headphones, connected = false, battery = 80)
    private val jbl = BtOutput("AA:02", "JBL Flip", BtRole.Speaker, connected = false, battery = null)

    @Test fun classesMapToHeadphonesOrSpeakersAndEverythingElseIsNotAnOutput() {
        assertEquals(BtRole.Headphones, BtRoles.of(0x0418))
        assertEquals(BtRole.Headphones, BtRoles.of(0x0404))
        for (c in listOf(0x0400, 0x0414, 0x041C, 0x0420, 0x0428)) assertEquals(BtRole.Speaker, BtRoles.of(c))
        for (c in listOf(0x0410, 0x0424, 0x0508, 0x0540, 0x0000)) assertNull("class $c", BtRoles.of(c))
    }

    @Test fun groupsAreThisTvThenHeadphonesThenSpeakersWithNetworkAndBluetoothSpeakersTogether() {
        val rows = OutputPicker.build(listOf(kitchen), null, listOf(jbl, buds))
        assertEquals(listOf("This TV", "Pixel Buds", "JBL Flip", "Kitchen"), rows.map { it.title })
        assertEquals(listOf(OutputSection.ThisTv, OutputSection.Headphones, OutputSection.Speakers, OutputSection.Speakers), rows.map { it.section })
    }

    @Test fun glyphIsPerDeviceNotPerTransport() {
        val rows = OutputPicker.build(listOf(kitchen), null, listOf(jbl, buds)).associateBy { it.title }
        assertEquals(OutputGlyph.Tv, rows.getValue("This TV").glyph)
        assertEquals(OutputGlyph.Headphones, rows.getValue("Pixel Buds").glyph)
        assertEquals(OutputGlyph.Speaker, rows.getValue("JBL Flip").glyph)
        assertEquals("a network speaker and a Bluetooth speaker look alike", rows.getValue("JBL Flip").glyph, rows.getValue("Kitchen").glyph)
    }

    @Test fun thisTvIsCheckedOnlyWhileNothingElseIs() {
        assertTrue(OutputPicker.build(listOf(kitchen), null, listOf(buds)).first().selected)
        val withBt = OutputPicker.build(listOf(kitchen), null, listOf(buds.copy(connected = true)))
        assertFalse(withBt.first().selected)
        assertTrue(withBt.first { it.title == "Pixel Buds" }.selected)
        val withNet = OutputPicker.build(listOf(kitchen), AudioOutputConfig("10.0.0.5", "Kitchen"), listOf(buds))
        assertFalse(withNet.first().selected)
        assertTrue(withNet.first { it.title == "Kitchen" }.selected)
    }

    @Test fun subtitleShowsConnectedOrPairedAndBatteryOnlyWhenKnown() {
        assertEquals("Paired · Battery 80%", OutputPicker.btSubtitle(buds))
        assertEquals("Connected · Battery 80%", OutputPicker.btSubtitle(buds.copy(connected = true)))
        assertEquals("Paired", OutputPicker.btSubtitle(jbl))
        assertEquals("Paired", OutputPicker.btSubtitle(jbl.copy(battery = 250)))
    }

    @Test fun storedNetworkChoiceThatIsNotFoundStaysListedAndChecked() {
        val rows = OutputPicker.build(emptyList(), AudioOutputConfig("10.0.0.9", "Bedroom"), emptyList())
        assertEquals(listOf("This TV" to false, "Bedroom" to true), rows.map { it.title to it.selected })
        assertEquals("Not found", rows[1].subtitle)
    }

    @Test fun noBluetoothAndNoSpeakersLeavesOnlyThisTv() {
        val rows = OutputPicker.build(emptyList(), null, emptyList())
        assertEquals(listOf("This TV"), rows.map { it.title })
        assertTrue(rows.single().selected)
    }

    @Test fun connectingReplacesTheCurrentSinkBecauseBluetoothIsOneAtATime() {
        val rows = OutputPicker.build(emptyList(), null, listOf(buds.copy(connected = true), jbl))
        val click = OutputPicker.click(rows.first { it.title == "JBL Flip" }, rows, canRoute = true) as BtAction.Connect
        assertEquals(jbl, click.device)
        assertEquals(listOf(buds.copy(connected = true)), click.replacing)
    }

    @Test fun clickingTheConnectedDeviceDisconnectsIt() {
        val on = buds.copy(connected = true)
        val rows = OutputPicker.build(emptyList(), null, listOf(on))
        assertEquals(BtAction.Disconnect(on), OutputPicker.click(rows.first { it.title == "Pixel Buds" }, rows, canRoute = true))
    }

    @Test fun aPlainApkOpensSettingsInsteadOfRouting() {
        val rows = OutputPicker.build(emptyList(), null, listOf(buds))
        assertEquals(BtAction.OpenSettings, OutputPicker.click(rows.first { it.title == "Pixel Buds" }, rows, canRoute = false))
    }

    @Test fun networkAndThisTvRowsAreNotBluetoothActions() {
        val rows = OutputPicker.build(listOf(kitchen), null, emptyList())
        assertNull(OutputPicker.click(rows.first(), rows, canRoute = true))
        assertNull(OutputPicker.click(rows.last(), rows, canRoute = true))
    }
}
