package dev.glasslauncher.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Control Center's VPN pill (P83 M5): whether a tunnel is up, and which installed app can open one. Glass can't toggle another app's tunnel. */
object VpnControl {
    /** Installed apps that declare an `android.net.VpnService` (Glass itself never does). */
    fun apps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        return runCatching {
            pm.queryIntentServices(Intent("android.net.VpnService"), 0)
                .map { it.serviceInfo.packageName }.distinct().filter { it != context.packageName }
                .map { pkg -> pkg to (runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)) }
        }.getOrDefault(emptyList())
    }

    fun connected(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    fun open(context: Context, pkg: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: context.packageManager.getLeanbackLaunchIntentForPackage(pkg)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it); true } ?: false
    }.getOrDefault(false)

    /** Live "a VPN is up", from a network callback (no polling) while [active]. */
    @Composable
    fun rememberConnected(active: Boolean): Boolean {
        val context = LocalContext.current
        var connected by remember(active) { mutableStateOf(connected(context)) }
        DisposableEffect(active) {
            if (!active) return@DisposableEffect onDispose { }
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { connected = connected(context) }
                override fun onLost(network: Network) { connected = connected(context) }
            }
            runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), callback) }
            onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
        }
        return connected
    }
}
