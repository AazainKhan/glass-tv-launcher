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

    /** Fire TV's own Settings app (the one from the stock home screen), else Android's. */
    fun openSystemSettings(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage("com.amazon.tv.settings.v2")?.let { intent ->
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        } == true || open(context, Settings.ACTION_SETTINGS)

    fun openGameControllers(context: Context): Boolean =
        open(context, "com.amazon.device.settings.action.GAMEPADS") || openBluetooth(context)
}
