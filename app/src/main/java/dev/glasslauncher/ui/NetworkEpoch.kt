package dev.glasslauncher.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Counts "the network came back": every time a default network becomes available, [value] goes up. Art that failed
 * to load reads it (as Compose state), so it asks again when connectivity returns while the view is still up,
 * instead of staying on its fallback until the page is re-entered.
 */
object NetworkEpoch {
    var value by mutableIntStateOf(0)
        private set

    private var registered = false

    /** Starts listening (once). Safe to call from the application. */
    fun register(context: Context) {
        if (registered) return
        registered = true
        val main = Handler(Looper.getMainLooper())
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { main.post { value++ } }
            })
        }
    }

    /** For tests: as if the network returned. */
    internal fun bump() { value++ }
}
