package dev.glasslauncher.system

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A remote Glass has seen (P100): when, how strong, and its name. */
data class RemoteSeen(val address: String, val name: String, val lastSeenMs: Long, val rssi: Int)

/** The words the Remotes panel shows, kept free of Android so they are tested on the JVM. */
object RemoteStatus {
    /** Signal from a scan's RSSI (dBm): the panel says "Strong", "Good" or "Weak", never the number. */
    fun signal(rssi: Int?): String? = when {
        rssi == null || rssi >= 0 -> null
        rssi >= -60 -> "Strong"
        rssi >= -75 -> "Good"
        else -> "Weak"
    }

    /** "now", "3 min ago", "2 h ago", "4 d ago"; null when never seen. */
    fun ago(lastSeenMs: Long, nowMs: Long): String? {
        if (lastSeenMs <= 0) return null
        val s = ((nowMs - lastSeenMs) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    /** Battery percent from the stack (-1 = unknown), or null so the panel leaves it out. */
    fun battery(level: Int): String? = if (level in 0..100) "$level%" else null

    /** One line under a remote's name: "Connected · Strong · battery 80%", or "Last seen 3 min ago". */
    fun line(connected: Boolean, rssi: Int?, lastSeenMs: Long, batteryLevel: Int, nowMs: Long): String {
        val parts = ArrayList<String>()
        if (connected) parts += "Connected"
        else parts += ago(lastSeenMs, nowMs)?.let { "Last seen $it" } ?: "Not seen yet"
        signal(rssi)?.takeIf { !connected || rssi != null }?.let { parts += "Signal $it" }
        battery(batteryLevel)?.let { parts += "Battery $it" }
        return parts.joinToString(" · ")
    }
}

/** At most one write per key per [everyMs]; keeps the registry off the prefs for every advert. */
class WriteThrottle(private val everyMs: Long = 30_000L) {
    private val last = HashMap<String, Long>()
    @Synchronized
    fun allow(key: String, nowMs: Long): Boolean {
        val prev = last[key]
        if (prev != null && nowMs - prev < everyMs && nowMs >= prev) return false
        last[key] = nowMs
        return true
    }
}

/** Last-seen record per remote address, in the app's prefs. A handful of entries: no database. */
class RemoteRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("remotes", 0)
    private companion object { val throttle = WriteThrottle() }

    fun all(): List<RemoteSeen> = runCatching {
        val a = JSONArray(prefs.getString("seen", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { RemoteSeen(it.getString("a"), it.optString("n"), it.optLong("t"), it.optInt("r", 0)) }
    }.getOrDefault(emptyList())

    /** Records a sighting; [rssi] 0 keeps the one already stored (a connect or disconnect has no signal). */
    @Synchronized
    fun record(address: String, name: String?, rssi: Int, nowMs: Long = System.currentTimeMillis()) {
        // A remote in pairing mode advertises about 500 times a minute: one write per address per 30 s.
        if (!throttle.allow(address, nowMs)) return
        val old = all().associateBy { it.address }
        val prev = old[address]
        val next = old + (address to RemoteSeen(address, name ?: prev?.name.orEmpty(), nowMs, if (rssi < 0) rssi else prev?.rssi ?: 0))
        val a = JSONArray()
        next.values.sortedByDescending { it.lastSeenMs }.take(8).forEach {
            a.put(JSONObject().put("a", it.address).put("n", it.name).put("t", it.lastSeenMs).put("r", it.rssi))
        }
        prefs.edit().putString("seen", a.toString()).apply()
    }
}

/** One remote in the Remotes panel. */
data class RemoteRow(val address: String, val name: String, val line: String)

object RemoteList {
    /** Bonded Amazon remotes with their live state (connected, battery) and what the registry last saw. */
    @android.annotation.SuppressLint("MissingPermission")
    fun read(context: Context, nowMs: Long = System.currentTimeMillis()): List<RemoteRow> {
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return emptyList()
        val seen = RemoteRegistry(context).all().associateBy { it.address }
        return runCatching {
            adapter.bondedDevices.filter {
                RemotePairPolicy.looksLikeRemote(runCatching { it.bluetoothClass?.majorDeviceClass }.getOrNull(), runCatching { it.name }.getOrNull())
            }.map { d ->
                val connected = runCatching { android.bluetooth.BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)
                val battery = runCatching { android.bluetooth.BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as Int }.getOrDefault(-1)
                val s = seen[d.address]
                RemoteRow(d.address, d.name ?: "Fire TV Remote", RemoteStatus.line(connected, s?.rssi, s?.lastSeenMs ?: 0L, battery, nowMs))
            }
        }.getOrDefault(emptyList())
    }
}
