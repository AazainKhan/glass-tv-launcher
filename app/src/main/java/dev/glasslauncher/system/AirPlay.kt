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
import dev.glasslauncher.system.AirPlayProtocol.Event
import dev.glasslauncher.system.AirPlayProtocol.SettingKey

/**
 * AirPlay receiving through PhairPlay (github.com/mazer666/PhairPlay), built and installed with the
 * control patch by `scripts/phairplay install`. Glass only switches the receiver on and off and shows
 * its state; PhairPlay advertises the TV, and brings its own player forward when a Mac or iPhone
 * starts mirroring. Messages go both ways under a signature permission, so only apps signed with
 * Glass's key can drive it. The wire format is in [AirPlayProtocol]; a PhairPlay that doesn't report
 * a protocol version is the old one and only gets the plain on/off toggle.
 */
object AirPlay {
    private val PACKAGES = listOf("com.phairplay.firetv", "com.phairplay.googletv")
    private const val RECEIVER = "com.phairplay.service.ControlReceiver"

    /** [protocol] is 1 until PhairPlay reports otherwise; [settings] is null for protocol 1. */
    @Immutable
    data class State(
        val on: Boolean,
        val sender: String? = null,
        val protocol: Int = 1,
        val settings: AirPlayProtocol.Settings? = null,
    ) {
        val supportsControl get() = protocol >= AirPlayProtocol.VERSION
    }

    /** The installed PhairPlay that has the control receiver, or null. */
    fun receiver(context: Context): ComponentName? {
        val pm = context.packageManager
        return PACKAGES.firstNotNullOfOrNull { pkg ->
            ComponentName(pkg, RECEIVER).takeIf {
                runCatching { pm.getReceiverInfo(it, 0) }.isSuccess &&
                    // Both apps declare the permission, so also require PhairPlay to carry Glass's signing cert.
                    pm.checkSignatures(context.packageName, pkg) == PackageManager.SIGNATURE_MATCH &&
                    pm.checkPermission(AirPlayProtocol.PERMISSION, context.packageName) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    fun set(context: Context, target: ComponentName, on: Boolean) = send(context, AirPlayProtocol.encodeToggle(target, on))

    fun query(context: Context, target: ComponentName) = send(context, AirPlayProtocol.encodeQuery(target))

    /** Writes one setting; false (nothing sent) when PhairPlay is the old protocol 1 and has none. */
    fun setSetting(context: Context, target: ComponentName, state: State, key: SettingKey, value: Any): Boolean {
        if (!state.supportsControl) return false
        val intent = when (value) {
            is Boolean -> AirPlayProtocol.encodeSet(target, key, value)
            is Int -> AirPlayProtocol.encodeSet(target, key, value)
            is String -> AirPlayProtocol.encodeSet(target, key, value, state.settings?.displayNameMax ?: AirPlayProtocol.DISPLAY_NAME_MAX)
            else -> throw IllegalArgumentException("unsupported value ${value::class.simpleName}")
        }
        return send(context, intent)
    }

    fun transport(context: Context, target: ComponentName, state: State, command: AirPlayProtocol.Transport): Boolean =
        state.supportsControl && send(context, AirPlayProtocol.encodeTransport(target, command))

    private fun send(context: Context, intent: Intent): Boolean =
        runCatching { context.sendBroadcast(intent, AirPlayProtocol.PERMISSION) }.isSuccess

    /** What a STATE event means for the UI's state; null for the other events. */
    fun stateOf(event: Event): State? = (event as? Event.State)?.let { State(it.running, it.sender, it.protocol, it.settings) }

    /**
     * Listens for PhairPlay's [actions] until the returned function is called. Registered with the
     * signature permission, so only PhairPlay can deliver them (it matters for the pairing code).
     */
    fun register(context: Context, actions: List<String> = AirPlayProtocol.EVENT_ACTIONS, onEvent: (Event) -> Unit): () -> Unit {
        val listener = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                AirPlayProtocol.decode(intent)?.let(onEvent)
            }
        }
        val filter = IntentFilter().also { f -> actions.forEach(f::addAction) }
        ContextCompat.registerReceiver(context, listener, filter, AirPlayProtocol.PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        return { runCatching { context.unregisterReceiver(listener) } }
    }

    /** The pairing-code card's lifetime and the current session, fed by [rememberState]'s receiver. */
    val pairing = AirPlayCardLife()
    val session = AirPlaySessionLife()

    /**
     * PhairPlay's receiver state while the caller is on screen, or null when PhairPlay isn't
     * installed. Listens to every PhairPlay event, and asks for the state again on each resume so
     * a missed event is repaired.
     */
    @Composable
    fun rememberState(active: Boolean): Pair<State, (Boolean) -> Unit>? {
        val context = LocalContext.current
        val target = remember(active) { receiver(context) } ?: return null
        var state by remember { mutableStateOf(State(on = false)) }
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        DisposableEffect(target, active, lifecycle) {
            if (!active) return@DisposableEffect onDispose { }
            val unregister = register(context) { event ->
                val now = android.os.SystemClock.elapsedRealtime()
                when (event) {
                    is Event.State -> { pairing.onState(event); session.onState(event); stateOf(event)?.let { state = it } }
                    is Event.PairingCode -> pairing.onEvent(event, now)
                    is Event.Session -> session.onEvent(event)
                    else -> Unit
                }
            }
            val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) pairing.resync(context, target)
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer); unregister() }
        }
        return state to { on -> state = state.copy(on = on); set(context, target, on) }
    }
}
