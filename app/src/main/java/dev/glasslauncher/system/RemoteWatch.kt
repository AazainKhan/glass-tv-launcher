package dev.glasslauncher.system

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One line of text for the user from the system layer: Glass's banner when its service is up, else a toast. */
object RemoteNotice {
    const val STALE_BOND = "The remote kept an old pairing. Hold Left + Menu + Back for 12 s to reset it, then hold Home for 10 s."

    /**
     * [actionable]: the user must answer or act (pairing help, a code to type): it keeps its time and is shown even under Do Not Disturb.
     * A plain notice is a few seconds at most and is not shown at all while Android's Do Not Disturb is on (P163); one at a time, the rest queue.
     */
    fun show(context: Context, text: String, forMs: Long = 4_000, actionable: Boolean = false) {
        if (!actionable && DoNotDisturb.on(context)) return
        Handler(Looper.getMainLooper()).post {
            val banner = RemoteKeysService.instance
            if (banner != null) banner.showBanner(text, forMs, actionable)
            else Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Watches the Amazon remote's connection in every install way (APK needs no privilege for ACL broadcasts):
 * - "Remote connected" when it comes back after being gone at least [RemotePairPolicy.RECONNECT_TOAST_AFTER_MS] (P98 e).
 * - APK mode, where Glass can't pair the remote itself: when it stays gone that long with the screen on, one card
 *   that opens Fire's remote settings (P98 d). Pro/OS with Glass's own pairer on say nothing here; the pairer acts.
 */
object RemoteWatch {
    private val _card = MutableStateFlow(false)
    /** True when Home should show its "Remote not connected" card (consumed with [cardShown]). */
    val card: StateFlow<Boolean> = _card
    fun cardShown() { _card.value = false }

    /** When a key last came from a source that is not an Amazon remote (uptime ms), 0 = never. */
    @Volatile private var otherInputAt = 0L

    /** Called for every key the keys service sees: [deviceName] is the input device's name (null if unknown). */
    fun noteKey(deviceName: String?) {
        if (!RemotePairPolicy.isAmazonRemoteName(deviceName)) otherInputAt = android.os.SystemClock.uptimeMillis()
    }

    /** Ms since the last non-remote key, null if there has not been one. */
    fun sinceOtherInputMs(now: Long = android.os.SystemClock.uptimeMillis()): Long? = otherInputAt.takeIf { it > 0 }?.let { now - it }

    /** Glass Home is the resumed activity (no app or playback in front); set by MainActivity. */
    @Volatile var homeInFront = false

    private var started = false
    private var lastDisconnect = 0L
    private var cardOffered = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        androidx.core.content.ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            @android.annotation.SuppressLint("MissingPermission")
            override fun onReceive(c: Context, i: Intent) {
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val remote = RemotePairPolicy.looksLikeRemote(
                    runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull(), runCatching { d.name }.getOrNull(),
                )
                if (!remote) return
                val now = System.currentTimeMillis()
                // Connect and disconnect are sightings too (no signal strength: the stored one stays).
                runCatching { RemoteRegistry(app).record(d.address, d.name, 0, now) }
                when (i.action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        if (lastDisconnect == 0L) lastDisconnect = now
                        // APK: still gone after the threshold, screen on: offer the page once per disconnect.
                        handler.postDelayed({ offerCard(app) }, RemotePairPolicy.RECONNECT_TOAST_AFTER_MS + 1_000)
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        if (RemotePairPolicy.reconnectToast(lastDisconnect, now)) RemoteNotice.show(app, "Remote connected", 2_500)
                        lastDisconnect = 0L; cardOffered = false; _card.value = false
                    }
                }
            }
        }, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun offerCard(app: Context) {
        if (lastDisconnect == 0L || cardOffered) return
        if (RootFeatures.ownRemotePairing(app)) return
        val on = app.getSystemService(PowerManager::class.java)?.isInteractive == true
        if (!on) return
        if (System.currentTimeMillis() - lastDisconnect < RemotePairPolicy.RECONNECT_TOAST_AFTER_MS) return
        cardOffered = true
        _card.value = true
    }
}
