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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    @Volatile private var hubInstance: AirPlayHub? = null

    /** The one hub for the process: it listens to PhairPlay from [start] on, whichever screen is up. */
    fun hub(context: Context): AirPlayHub =
        hubInstance ?: synchronized(this) { hubInstance ?: AirPlayHub(context.applicationContext).also { hubInstance = it } }

    /** The hub and its latest cleaned state, for a screen. It asks PhairPlay again each time [active] resumes, so a missed event is repaired. */
    @Composable
    fun rememberAirPlay(active: Boolean): Pair<AirPlayUi, AirPlayHub> {
        val context = LocalContext.current
        val hub = remember { hub(context) }
        val ui by hub.ui.collectAsState()
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        DisposableEffect(hub, active, lifecycle) {
            if (!active) return@DisposableEffect onDispose { }
            val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) hub.resume() }
            lifecycle.addObserver(observer)
            hub.resume()
            onDispose { lifecycle.removeObserver(observer) }
        }
        return ui to hub
    }
}

/** How the hub reaches PhairPlay: look it up, send it an intent, hear its events. A fake in tests. */
interface AirPlayLink {
    fun target(): ComponentName?
    fun send(intent: Intent): Boolean
    fun listen(onEvent: (Event) -> Unit)
}

private class ContextLink(private val app: Context) : AirPlayLink {
    override fun target() = AirPlay.receiver(app)
    override fun send(intent: Intent) = runCatching { app.sendBroadcast(intent, AirPlayProtocol.PERMISSION) }.isSuccess
    override fun listen(onEvent: (Event) -> Unit) { AirPlay.register(app, onEvent = onEvent) }
}

/**
 * The process-wide AirPlay link: listens to PhairPlay (registered with the signature permission, so only PhairPlay
 * can deliver), folds events through [AirPlayReducer] and publishes the result. Screens read [ui]; none keeps its own copy.
 */
class AirPlayHub(private val link: AirPlayLink, clock: () -> Long = android.os.SystemClock::elapsedRealtime) {
    constructor(app: Context) : this(ContextLink(app))

    private val reducer = AirPlayReducer(clock)
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val _ui = MutableStateFlow(AirPlayUi())
    val ui: StateFlow<AirPlayUi> = _ui
    private var target: ComponentName? = null
    private var started = false

    /** Process start: listen for good and ask for the state. */
    fun start() {
        if (started) return
        started = true
        link.listen { publish(reducer.onEvent(it)) }
        resume()
    }

    /** A screen resumed (or the link may have come back): find PhairPlay again and ask for its state, so a missed event is repaired. */
    fun resume() {
        if (!started) { start(); return }
        target = link.target()
        publish(reducer.setInstalled(target != null))
        target?.let { link.send(AirPlayProtocol.encodeQuery(it)) }
    }

    fun setOn(on: Boolean) { target?.let { link.send(AirPlayProtocol.encodeToggle(it, on)) } }

    /** Sends one setting. Nothing changes here until PhairPlay answers with its STATE. */
    fun setSetting(key: AirPlayProtocol.SettingKey, value: Any): Boolean {
        val t = target ?: return false
        val ui = _ui.value
        if (ui.availability != AirPlayAvailability.Ready) return false
        val intent = runCatching {
            when (value) {
                is Boolean -> AirPlayProtocol.encodeSet(t, key, value)
                is Int -> AirPlayProtocol.encodeSet(t, key, value)
                is String -> AirPlayProtocol.encodeSet(t, key, value, ui.settings?.displayNameMax ?: AirPlayProtocol.DISPLAY_NAME_MAX)
                else -> null
            }
        }.getOrNull() ?: return false
        return link.send(intent)
    }

    fun playPause() {
        val t = target ?: return
        val ui = _ui.value
        val playing = ui.track?.playing ?: return
        if (ui.availability != AirPlayAvailability.Ready) return
        val cmd = if (playing) AirPlayProtocol.Transport.Pause else AirPlayProtocol.Transport.Play
        if (link.send(AirPlayProtocol.encodeTransport(t, cmd))) publish(reducer.onTransport(!playing))
    }

    /** Back on the pairing card for [code]: a newer code that replaced it stays up. */
    fun dismissPairing(code: String) { if (_ui.value.pairingCode == code) publish(reducer.dismissPairing()) }

    /** Drops an expired pairing card; runs by itself when the TTL ends. */
    fun tick() = publish(reducer.tick())

    private fun publish(ui: AirPlayUi) {
        _ui.value = ui
        runCatching {
            handler.removeCallbacks(expire)
            reducer.pairingRemaining()?.let { handler.postDelayed(expire, it + 10) }
        }
    }

    private val expire = Runnable { tick() }
}
