package dev.glasslauncher.sonos

import dev.glasslauncher.data.AudioOutputConfig

/** The tvOS unified output picker (TVSSAudioRoutingPanelPlatterController): one list, grouped by what a device IS, not how it connects. */
enum class OutputSection { ThisTv, Headphones, Speakers }

/** Per-device glyph (tvOS deviceSymbolName), never a per-transport icon. */
enum class OutputGlyph { Tv, Headphones, Speaker }

enum class BtRole { Headphones, Speaker }

/** A paired Bluetooth audio device. [connected] means it is the system's A2DP sink right now; [battery] only when the stack reports one. */
data class BtOutput(val address: String, val name: String, val role: BtRole, val connected: Boolean, val battery: Int?)

/** What a click on a Bluetooth row does: Bluetooth is one sink at a time, so connecting replaces the previous one. */
sealed interface BtAction {
    data class Connect(val device: BtOutput, val replacing: List<BtOutput>) : BtAction
    data class Disconnect(val device: BtOutput) : BtAction
    /** Plain APK (no BLUETOOTH_PRIVILEGED): the system's own Bluetooth page connects the device. */
    data object OpenSettings : BtAction
}

object BtRoles {
    /** Headphones or Speaker for a BluetoothClass device class; null for what is not an audio output (mic, set-top box, video, a keyboard...). */
    fun of(deviceClass: Int): BtRole? = when (deviceClass) {
        0x0404, 0x0418 -> BtRole.Headphones               // wearable headset, headphones
        0x0400, 0x0414, 0x041C, 0x0420, 0x0428 -> BtRole.Speaker // uncategorised A/V, loudspeaker, portable audio, car audio, hi-fi
        else -> null
    }
}

/** One line of the picker. [network] is set for a Sonos speaker, [bt] for a Bluetooth device, neither for This TV. */
data class PickerRow(
    val key: String,
    val section: OutputSection,
    val title: String,
    val subtitle: String?,
    val glyph: OutputGlyph,
    val selected: Boolean,
    val network: AudioOutputConfig? = null,
    val bt: BtOutput? = null,
)

object OutputPicker {
    const val THIS_TV = "This TV"

    /**
     * This TV first, then Headphones (paired Bluetooth headsets), then Speakers: the network speakers and the paired Bluetooth
     * speakers together, by name. This TV is checked only while nothing else is (a connected Bluetooth sink takes the system audio).
     * A stored network choice the scan did not find stays listed ("Not found") so it can still be cleared.
     */
    fun build(found: List<SonosSpeaker>, selected: AudioOutputConfig?, bt: List<BtOutput>): List<PickerRow> {
        val network = found.distinctBy { it.host }.map {
            PickerRow("net:" + it.host, OutputSection.Speakers, it.roomName.ifBlank { it.host }, it.modelName.ifBlank { null }, OutputGlyph.Speaker,
                it.host == selected?.host, network = AudioOutputConfig(it.host, it.roomName))
        }
        val missing = if (selected != null && network.none { it.selected }) listOf(
            PickerRow("net:" + selected.host, OutputSection.Speakers, selected.room.ifBlank { selected.host }, "Not found", OutputGlyph.Speaker, true, network = selected),
        ) else emptyList()
        val devices = bt.distinctBy { it.address }.map {
            PickerRow("bt:" + it.address, if (it.role == BtRole.Headphones) OutputSection.Headphones else OutputSection.Speakers, it.name.ifBlank { it.address },
                btSubtitle(it), if (it.role == BtRole.Headphones) OutputGlyph.Headphones else OutputGlyph.Speaker, it.connected, bt = it)
        }
        val rest = (missing + network + devices)
        val tv = PickerRow("this-tv", OutputSection.ThisTv, THIS_TV, null, OutputGlyph.Tv, rest.none { it.selected })
        return listOf(tv) +
            rest.filter { it.section == OutputSection.Headphones }.sortedBy { it.title.lowercase() } +
            rest.filter { it.section == OutputSection.Speakers }.sortedBy { it.title.lowercase() }
    }

    /** "Connected" with the battery when the stack reports one, else "Paired" (tvOS: battery for headphones, what plays for the rest). */
    fun btSubtitle(d: BtOutput): String =
        (if (d.connected) "Connected" else "Paired") + (d.battery?.takeIf { it in 0..100 }?.let { " · Battery $it%" } ?: "")

    /** The action a click on a Bluetooth row asks for. [canRoute]: Glass may connect and disconnect A2DP itself (BLUETOOTH_PRIVILEGED granted). */
    fun click(row: PickerRow, all: List<PickerRow>, canRoute: Boolean): BtAction? {
        val d = row.bt ?: return null
        if (!canRoute) return BtAction.OpenSettings
        return if (d.connected) BtAction.Disconnect(d)
        else BtAction.Connect(d, replacing = all.mapNotNull { it.bt }.filter { it.connected && it.address != d.address })
    }
}
