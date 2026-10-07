package dev.glasslauncher.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings

/** What Control Center reads from the TV, and the system pages its tiles open. */
object SystemControls {

    /** The Wi-Fi network name, "Ethernet", "Not Connected", or "Connected" when Android hides the SSID. */
    fun network(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return "Not Connected"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet"
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Connected"
        // SSIDs need location access (grant once: adb shell pm grant <pkg> android.permission.ACCESS_FINE_LOCATION).
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "Connected"
        @Suppress("DEPRECATION")
        val ssid = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        // Without permission Android reports "<unknown ssid>".
        return ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && !it.startsWith("<") } ?: "Connected"
    }

    fun wifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm?.getNetworkCapabilities(cm.activeNetwork)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
    }

    /** Bluetooth on/off; null when the TV has no adapter or won't say. */
    @Suppress("DEPRECATION", "MissingPermission")
    fun bluetoothOn(): Boolean? = runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled }.getOrNull()

    fun open(context: Context, action: String): Boolean = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** The standard Bluetooth page, else Fire TV's "Controllers & Bluetooth Devices". */
    fun openBluetooth(context: Context): Boolean =
        open(context, Settings.ACTION_BLUETOOTH_SETTINGS) || open(context, "com.amazon.device.settings.action.CONTROLLERS")

    /** A top-level section of the TV's settings: a Fire TV screen, or a standard Android action elsewhere. */
    data class TvSettingsSection(val title: String, val fireActivity: String, val fallbackAction: String)

    /**
     * Fire TV's settings app has no main screen of its own (the stock home screen draws that list, and
     * Android's ACTION_SETTINGS lands on a do-nothing compliance stub), so Glass lists the sections.
     */
    val tvSettingsSections = listOf(
        TvSettingsSection("Network", "tv.network.NetworkActivity", Settings.ACTION_WIFI_SETTINGS),
        TvSettingsSection("Display & Sounds", "tv.display_sounds.DisplayAndSoundsActivity", Settings.ACTION_DISPLAY_SETTINGS),
        TvSettingsSection("Applications", "tv.applications.ApplicationsActivity", Settings.ACTION_APPLICATION_SETTINGS),
        TvSettingsSection("Controllers & Bluetooth Devices", "tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity", Settings.ACTION_BLUETOOTH_SETTINGS),
        TvSettingsSection("Preferences", "tv.preferences.PreferencesActivity", Settings.ACTION_LOCALE_SETTINGS),
        TvSettingsSection("My Fire TV", "tv.device.DeviceActivity", Settings.ACTION_DEVICE_INFO_SETTINGS),
        TvSettingsSection("Accessibility", "tv.accessibility.AccessibilityActivity", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        TvSettingsSection("Account & Profile Settings", "tv.my_account.MyAccountActivity", Settings.ACTION_SYNC_SETTINGS),
    )

    fun openTvSettings(context: Context, section: TvSettingsSection): Boolean = runCatching {
        context.startActivity(
            Intent().setClassName(FIRE_SETTINGS, "$FIRE_SETTINGS.${section.fireActivity}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        true
    }.getOrDefault(false) || open(context, section.fallbackAction)

    private const val FIRE_SETTINGS = "com.amazon.tv.settings.v2"

    fun openGameControllers(context: Context): Boolean =
        open(context, "com.amazon.device.settings.action.GAMEPADS") || openBluetooth(context)
}
