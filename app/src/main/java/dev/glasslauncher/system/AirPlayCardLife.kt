package dev.glasslauncher.system

/**
 * When the pairing-code card is up and when to give up on it. A missed close event must not leave a
 * stale card: it expires after the event's TTL, and a resume or reconnect asks PhairPlay for STATE.
 */
class AirPlayCardLife {
    var code: String? = null
        private set
    private var expiresAt = 0L

    fun onEvent(e: AirPlayProtocol.Event.PairingCode, now: Long) {
        code = e.code
        expiresAt = now + e.ttlMs
    }

    /** True while the card should be on screen at [now]. */
    fun visible(now: Long): Boolean = code != null && now < expiresAt

    /** Milliseconds until the card expires, for a timer; null when none is up. */
    fun remaining(now: Long): Long? = if (visible(now)) expiresAt - now else null

    /** A STATE with no session cannot have a pairing in progress: drop a card that survived a missed close. */
    fun onState(s: AirPlayProtocol.Event.State) {
        if (!s.running) code = null
    }

    /** Ask PhairPlay for STATE when the screen resumes or the link comes back. */
    fun resync(context: android.content.Context, target: android.content.ComponentName) = AirPlay.query(context, target)
}

/** The one session in progress. A second "started" replaces it (the kind is refined), it never stacks. */
class AirPlaySessionLife {
    var current: AirPlayProtocol.Event.Session? = null
        private set

    fun onEvent(e: AirPlayProtocol.Event.Session) {
        current = if (e.started) e else current?.takeIf { it.kind != e.kind && e.kind != AirPlayProtocol.SessionKind.Unknown }
    }

    /** The receiver stopped, so no session is in progress whatever events were missed. */
    fun onState(s: AirPlayProtocol.Event.State) {
        if (!s.running) current = null
    }
}
