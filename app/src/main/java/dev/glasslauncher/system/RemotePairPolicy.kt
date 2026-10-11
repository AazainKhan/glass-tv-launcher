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
    )

    enum class Action { Ignore, Bond, Connect, Rebond }

    /** Connect failures in a row that count as "the remote forgot us" (a key/auth error), before a re-bond. */
    const val REBOND_AFTER_FAILURES = 3
    /** At most one re-bond per this long. */
    const val REBOND_EVERY_MS = 2 * 60_000L

    fun decide(ad: Advert, s: State): Action {
        if (!s.screenOn || s.remoteConnected) return Action.Ignore
        if (!ad.amazon || !ad.hid) return Action.Ignore
        if (!ad.discoverable) return Action.Ignore
        if (ad.address !in s.bonded) return Action.Bond
        // Bonded but not connected: connect, and nothing else, unless it keeps failing (the remote was reset while
        // we kept the bond), and then at most one fresh bond per two minutes.
        if (s.connectFailures >= REBOND_AFTER_FAILURES && s.nowMs - s.lastRebondMs >= REBOND_EVERY_MS) return Action.Rebond
        return Action.Connect
    }

    /** Whether to scan at all: the screen is on and no Amazon remote is connected. */
    fun shouldScan(screenOn: Boolean, remoteConnected: Boolean) = screenOn && !remoteConnected
}
