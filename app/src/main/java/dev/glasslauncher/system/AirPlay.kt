package dev.glasslauncher.system

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * AirPlay receiving through PhairPlay (github.com/mazer666/PhairPlay), built and installed with the
 * control patch by `scripts/phairplay install`. Glass only switches the receiver on and off and shows
 * its state; PhairPlay advertises the TV, and brings its own player forward when a Mac or iPhone
 * starts mirroring. Messages go both ways under a signature permission, so only apps signed with
 * Glass's key can drive it.
 */
object AirPlay {
    private val PACKAGES = listOf("com.phairplay.firetv", "com.phairplay.googletv")
    private const val PERMISSION = "com.phairplay.permission.CONTROL"
    private const val RECEIVER = "com.phairplay.service.ControlReceiver"
    private const val ACTION_ENABLE = "com.phairplay.action.ENABLE"
    private const val ACTION_DISABLE = "com.phairplay.action.DISABLE"
    private const val ACTION_QUERY = "com.phairplay.action.QUERY"
    private const val ACTION_STATE = "com.phairplay.action.STATE"

    @Immutable
    data class State(val on: Boolean, val sender: String? = null)

    /** The installed PhairPlay that has the control receiver, or null. */
    fun receiver(context: Context): ComponentName? {
        val pm = context.packageManager
        return PACKAGES.firstNotNullOfOrNull { pkg ->
            ComponentName(pkg, RECEIVER).takeIf {
                runCatching { pm.getReceiverInfo(it, 0) }.isSuccess &&
                    pm.checkPermission(PERMISSION, context.packageName) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    fun set(context: Context, target: ComponentName, on: Boolean) =
        send(context, target, if (on) ACTION_ENABLE else ACTION_DISABLE)

    private fun send(context: Context, target: ComponentName, action: String) {
        runCatching { context.sendBroadcast(Intent(action).setComponent(target).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)) }
    }

    /**
     * PhairPlay's receiver state while the caller is on screen, or null when PhairPlay isn't
     * installed. Asks once when it appears, then follows PhairPlay's broadcasts.
     */
    @Composable
    fun rememberState(active: Boolean): Pair<State, (Boolean) -> Unit>? {
        val context = LocalContext.current
        val target = remember(active) { receiver(context) } ?: return null
        var state by remember { mutableStateOf(State(on = false)) }
        DisposableEffect(target, active) {
            if (!active) return@DisposableEffect onDispose { }
            val listener = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    state = State(intent.getBooleanExtra("running", false), intent.getStringExtra("sender"))
                }
            }
            // Only PhairPlay (holding the signature permission) can deliver these.
            ContextCompat.registerReceiver(context, listener, IntentFilter(ACTION_STATE), PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
            send(context, target, ACTION_QUERY)
            onDispose { runCatching { context.unregisterReceiver(listener) } }
        }
        return state to { on -> state = State(on); set(context, target, on) }
    }
}
