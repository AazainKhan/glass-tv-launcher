package dev.glasslauncher.system

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
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
    fun show(context: Context, text: String, forMs: Long = 4_000, actionable: Boolean = false) = show(context, Notice(text, forMs, actionable))

    /** A device came or went (P185): glyph, name, then Connected or Disconnected. Plain, so Do Not Disturb holds it back. */
    fun showDevice(context: Context, name: String, detail: String, iconRes: Int) {
        if (controlCenterOpen) return
        show(context, Notice(name, 3_500, false, detail, iconRes))
    }

    /** True while Control Center is up: a device banner would sit over its top tiles, and the device list there already shows the change. */
    @Volatile var controlCenterOpen = false

    private fun show(context: Context, notice: Notice) {
        if (!notice.actionable && DoNotDisturb.on(context)) return
        Handler(Looper.getMainLooper()).post {
            val banner = RemoteKeysService.instance
            if (banner != null) banner.showNotice(notice)
            else Toast.makeText(context.applicationContext, listOfNotNull(notice.text, notice.detail).joinToString(": "), Toast.LENGTH_LONG).show()
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

    private var lastDisconnect = 0L
    private var cardOffered = false
    private var loaded = false

    private const val PREFS = "remote_watch"
    /** Boot time read twice differs by clock jitter, never by seconds; a different boot is another life of this state. */
    private const val BOOT_SLACK_MS = 5_000L

    private fun bootAt(now: Long = System.currentTimeMillis()) = now - android.os.SystemClock.elapsedRealtime()

    /**
     * The state lives in a small prefs file, so a process the system started for one broadcast knows what the last one said (P209). It is
     * only valid for the boot it was written in: after a reboot the remote's own connect broadcast is the fresh observation, and until
     * then nothing is known, so no "not connected" card.
     */
    @Synchronized private fun load(app: Context) {
        if (loaded) return
        loaded = true
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (kotlin.math.abs(prefs.getLong("boot", 0L) - bootAt()) <= BOOT_SLACK_MS) {
            lastDisconnect = prefs.getLong("gone", 0L)
            cardOffered = prefs.getBoolean("offered", false)
        }
    }

    private fun save(app: Context) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("boot", bootAt()).putLong("gone", lastDisconnect).putBoolean("offered", cardOffered).apply()
    }

    /** Test hook: forget what was loaded, as a fresh process does. */
    internal fun resetForTest() { loaded = false; lastDisconnect = 0L; cardOffered = false; _card.value = false }

    fun start(context: Context) = load(context.applicationContext)

    /**
     * A Bluetooth link came or went. Called by [AclReceiver], which the manifest declares, so it also runs in a process the system started
     * for this broadcast (P209): a runtime receiver was gone with Glass's process.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun onAcl(context: Context, i: Intent) {
        val app = context.applicationContext
        load(app)
        @Suppress("DEPRECATION")
        val d = (if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
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
                save(app)
                // APK: still gone after the threshold, screen on: offer the page once per disconnect. Best effort here (the process may
                // be gone by then); [evaluate] asks again whenever Home comes to the front.
                Handler(Looper.getMainLooper()).postDelayed({ offerCard(app) }, RemotePairPolicy.RECONNECT_TOAST_AFTER_MS + 1_000)
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                lastDisconnect = 0L; cardOffered = false; _card.value = false
                save(app)
            }
        }
    }

    /** Home came to the front: a remote that has been gone for a while with nothing else asking gets its card now. */
    fun evaluate(context: Context) {
        val app = context.applicationContext
        load(app)
        offerCard(app)
    }

    private fun offerCard(app: Context) {
        if (lastDisconnect == 0L || cardOffered || _card.value) return
        if (RootFeatures.ownRemotePairing(app)) return
        val on = app.getSystemService(PowerManager::class.java)?.isInteractive == true
        if (!on) return
        if (System.currentTimeMillis() - lastDisconnect < RemotePairPolicy.RECONNECT_TOAST_AFTER_MS) return
        cardOffered = true
        save(app)
        _card.value = true
    }
}
