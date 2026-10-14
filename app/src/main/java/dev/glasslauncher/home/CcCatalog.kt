package dev.glasslauncher.home

import androidx.annotation.DrawableRes
import dev.glasslauncher.R
import dev.glasslauncher.addons.AddonHost

/** Which tier a control needs (spec 3): shown as a tag in the gallery. */
enum class CcTier(val tag: String?) { None(null), System("System"), Root("Root") }

/** One control the gallery can add. [app] groups it under By App; [addon] is the add-on's name for "via ...". */
data class CcCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    @DrawableRes val icon: Int,
    val tier: CcTier = CcTier.None,
    val app: String? = null,
    val addon: String? = null,
    /** False for built-ins that exist in the model but whose control isn't built yet (they aren't offered). */
    val implemented: Boolean = true,
)

/** The gallery's content: Glass built-ins, installed add-ons' controls, and both grouped by the app they act on. */
object CcCatalog {
    val glass: List<CcCatalogEntry> = listOf(
        CcCatalogEntry("wifi", "Wi-Fi", "The network, and its settings", R.drawable.ic_wifi),
        CcCatalogEntry("bluetooth", "Bluetooth", "Devices and their settings", R.drawable.ic_bluetooth),
        CcCatalogEntry("launcher", "Launcher Settings", "Glass's own settings", R.drawable.ic_tune),
        CcCatalogEntry("controllers", "Game Controllers", "Pair and manage controllers", R.drawable.ic_sports_esports),
        CcCatalogEntry("remotes", "Remotes", "Battery, signal and pairing for your remotes", R.drawable.ic_settings_remote),
        CcCatalogEntry("appearance", "Theme", "Switch between Dark and Light", R.drawable.ic_dark_mode),
        CcCatalogEntry("screensaver", "Screen Saver", "Start the screen saver now", R.drawable.ic_landscape),
        CcCatalogEntry("switcher", "App Switcher", "Recent apps", R.drawable.ic_app_switcher),
        CcCatalogEntry("airplay", "AirPlay", "Receive AirPlay (PhairPlay)", R.drawable.ic_airplay, app = "PhairPlay"),
        CcCatalogEntry("performance", "Performance", "Fast frees memory (closes background apps)", R.drawable.ic_speed, tier = CcTier.Root),
        CcCatalogEntry("sleep_timer", "Sleep Timer", "Pause and sleep after 15 to 90 minutes", R.drawable.ic_do_not_disturb_on),
        CcCatalogEntry("vpn", "VPN", "Shows if a VPN is on; opens its app", R.drawable.ic_vpn),
        CcCatalogEntry("restart", "Restart", "Restart Fire TV", R.drawable.ic_restart, tier = CcTier.System),
        CcCatalogEntry("power", "Power Off", "Turns off the TV over HDMI-CEC and sleeps the stick", R.drawable.ic_power_settings_new),
        CcCatalogEntry("cleanup", "Clean Up", "Free memory and clear caches", R.drawable.ic_cleaning_services, tier = CcTier.System),
        CcCatalogEntry("downloads", "Downloads", "Progress and speed; opens the download manager", R.drawable.ic_download),
        CcCatalogEntry("now_playing", "Now Playing", "Play, pause, skip any app", R.drawable.ic_music_note),
    ).filter { it.implemented }

    /** Controls of the enabled add-ons, plus the demo tiles while those are on. */
    val addons: List<CcCatalogEntry>
        get() = AddonHost.catalog + if (!CcShowcase.enabled) emptyList() else CcShowcase.tiles.map {
            CcCatalogEntry(it.id, it.name, "Demo data · ${it.description}", it.icon, addon = it.addon)
        }

    fun all(): List<CcCatalogEntry> = glass + addons
    fun entry(id: String): CcCatalogEntry? = all().firstOrNull { it.id == id }

    /** By App: controls grouped under the app they act on ("Fire TV" for the system's own). */
    fun byApp(): Map<String, List<CcCatalogEntry>> =
        all().groupBy { it.app ?: it.addon ?: "Fire TV" }.toSortedMap(compareBy({ it != "Fire TV" }, { it }))
}
