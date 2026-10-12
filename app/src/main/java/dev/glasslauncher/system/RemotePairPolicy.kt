package dev.glasslauncher.system

/**
 * When Glass pairs the Fire TV remote itself (P75 B), the decision for one advertisement, kept free of Android so
 * it is tested on the JVM. The rule that matters is Fire's own bug in reverse: never remove a bond on sight (Fire's
 * AutoPairService did unpair-then-pair, and the pair failed with reason_smp_fail, which dropped the bond).
 */
object RemotePairPolicy {
    /** What one received advertisement says. */
    data class Advert(
        val address: String,
        /** Manufacturer data of Amazon's company id 0x0171 is present. */
        val amazon: Boolean,
        /** The HID service (0x1812) is advertised. */
        val hid: Boolean,
        /** Flags carry LE General Discoverable (0x02): a remote in pairing mode, not one reconnecting after sleep. */
        val discoverable: Boolean,
        /** Signal strength in dBm: a stale-bond banner is only for a remote in the room, not a neighbour's. */
        val rssi: Int = -127,
        /** GAP appearance (AD type 0x19), when advertised: the Fire remote says 0x0180 (generic remote control). */
        val appearance: Int? = null,
    )

    data class State(
        val bonded: Set<String>,
        /** An Amazon remote (vendor 0x0171 HID) is connected right now. */
        val remoteConnected: Boolean,
        val screenOn: Boolean,
        val nowMs: Long,
        /** When Glass last removed a bond (0 = never). */
        val lastRebondMs: Long = 0L,
        /** Connect attempts to this address that did not end connected, newest run. */
        val connectFailures: Int = 0,
        /** The stick holds a bond with some Amazon remote (a different, unbonded advertiser is then probably a neighbour's). */
        val haveBondedRemote: Boolean = false,
    )

    /**
     * StaleBond: an Amazon remote nearby advertises as a bonded device waking up (not discoverable) but the stick has
     * no bond with it. The remote kept a bond the stick lost, so hold Home never makes it discoverable: the user must
     * reset it (Amazon's Left + Menu + Back 12 s) first. Glass only says so.
     */
    enum class Action { Ignore, Bond, Connect, Rebond, StaleBond }

    /** GAP appearance "Generic Remote Control" (0x0180), which the Fire remote advertises. */
    const val REMOTE_APPEARANCE = 0x0180

    /** Scanned this long with a bonded remote that never advertised: say so (fresh batteries are the usual cause). */
    const val BATTERY_HINT_AFTER_MS = 90_000L
    /** At most one battery hint per this long. */
    const val BATTERY_HINT_EVERY_MS = 10 * 60_000L

    fun batteryHint(
        haveBondedRemote: Boolean, remoteConnected: Boolean, screenOn: Boolean,
        scanningForMs: Long, silentForMs: Long, sinceLastHintMs: Long,
    ): Boolean = haveBondedRemote && !remoteConnected && screenOn &&
        scanningForMs >= BATTERY_HINT_AFTER_MS && silentForMs >= BATTERY_HINT_AFTER_MS && sinceLastHintMs >= BATTERY_HINT_EVERY_MS

    /** A remote that is close enough to be the user's. */
    const val NEAR_RSSI = -65

    /** How long a remote must have been gone before its return is worth a "Remote connected" toast. */
    const val RECONNECT_TOAST_AFTER_MS = 30_000L

    /** The reconnect toast: a disconnect of at least [RECONNECT_TOAST_AFTER_MS] (0 = never seen disconnect), not a brief blip. */
    fun reconnectToast(lastDisconnectMs: Long, nowMs: Long): Boolean =
        lastDisconnectMs > 0 && nowMs - lastDisconnectMs >= RECONNECT_TOAST_AFTER_MS

    /** An input peripheral (class 0x0500) or one named like Fire's remote. */
    fun looksLikeRemote(majorDeviceClass: Int?, name: String?): Boolean =
        majorDeviceClass == 0x0500 || name.orEmpty().contains("Remote", ignoreCase = true) || name == "AR"

    /** Connect failures in a row that count as "the remote forgot us" (a key/auth error), before a re-bond. */
    const val REBOND_AFTER_FAILURES = 3
    /** At most one re-bond per this long. */
    const val REBOND_EVERY_MS = 2 * 60_000L

    fun decide(ad: Advert, s: State): Action {
        // The screen state doesn't matter: with no remote the stick idles to sleep (no input), and that is exactly
        // when pairing is needed. A scan with a hardware filter at low power costs next to nothing.
        if (s.remoteConnected) return Action.Ignore
        if (!ad.amazon || !ad.hid) return Action.Ignore
        // Never bond a random HID device: where an appearance is advertised it must be the remote's.
        if (ad.appearance != null && ad.appearance != REMOTE_APPEARANCE) return Action.Ignore
        if (!ad.discoverable) {
            return if (ad.address !in s.bonded && !s.haveBondedRemote && ad.rssi >= NEAR_RSSI) Action.StaleBond else Action.Ignore
        }
        if (ad.address !in s.bonded) return Action.Bond
        // Bonded but not connected: connect, and nothing else, unless it keeps failing (the remote was reset while
        // we kept the bond), and then at most one fresh bond per two minutes.
        if (s.connectFailures >= REBOND_AFTER_FAILURES && s.nowMs - s.lastRebondMs >= REBOND_EVERY_MS) return Action.Rebond
        return Action.Connect
    }

    /** Whether to scan at all: no Amazon remote is connected (screen on or off). */
    fun shouldScan(remoteConnected: Boolean) = !remoteConnected

    /**
     * The scan is registered again this often while it should run: Fire's BtLeManager logs "stop all scans" about
     * every 27 s and Glass gets no callback when it does (stick finding, 02:23Z), so a silent scan can't be trusted.
     */
    const val RESCAN_EVERY_MS = 25_000L
}

/**
 * Raw advertisement parser (the Fire remote: flags 0x05 = LE Limited Discoverable + BR/EDR not supported, HID 0x1812,
 * appearance 0x0180, Amazon company 0x0171). Android's ScanRecord exposes some of this; reading the bytes keeps
 * the accept rules in one tested place.
 */
object AdvParser {
    data class Parsed(val flags: Int?, val uuids16: Set<Int>, val companyIds: Set<Int>, val appearance: Int?)

    /** LE Limited (0x01) or General (0x02) Discoverable: the remote in pairing mode, whichever it uses. */
    fun discoverable(flags: Int?): Boolean = flags != null && (flags and 0x03) != 0

    fun parse(raw: ByteArray): Parsed {
        var flags: Int? = null
        val uuids = HashSet<Int>(); val companies = HashSet<Int>(); var appearance: Int? = null
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len == 0 || i + 1 + len > raw.size) break
            val type = raw[i + 1].toInt() and 0xFF
            val d = raw.copyOfRange(i + 2, i + 1 + len)
            fun le16(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
            when (type) {
                0x01 -> if (d.isNotEmpty()) flags = d[0].toInt() and 0xFF
                0x02, 0x03 -> for (o in 0 until d.size - 1 step 2) uuids += le16(o)
                0x19 -> if (d.size >= 2) appearance = le16(0)
                0xFF -> if (d.size >= 2) companies += le16(0)
            }
            i += 1 + len
        }
        return Parsed(flags, uuids, companies, appearance)
    }
}
