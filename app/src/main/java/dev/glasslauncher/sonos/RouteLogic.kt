package dev.glasslauncher.sonos

/** Where a Sonos route is. Consent, then capture, then the speaker; Stop is legal from anywhere. */
sealed interface RouteState {
    data object Idle : RouteState
    /** Waiting for RECORD_AUDIO and the MediaProjection dialog (asked every session, all install modes). */
    data object NeedsConsent : RouteState
    /** Consent given; the capture service is starting. */
    data object Capturing : RouteState
    /** Capture runs; SonosSession.start is in flight. */
    data object StartingSpeaker : RouteState
    data object Playing : RouteState
    /** The playing app opted out of capture: the TV keeps the sound. [app] is its label. */
    data class Blocked(val app: String) : RouteState
    data class Failed(val reason: String) : RouteState
}

sealed interface RouteEvent {
    data object Start : RouteEvent
    data object ConsentGranted : RouteEvent
    data object ConsentDenied : RouteEvent
    data object CaptureReady : RouteEvent
    data object SpeakerStarted : RouteEvent
    data class Error(val reason: String) : RouteEvent
    data class OptedOut(val app: String) : RouteEvent
    /** PCM came back (the app changed or was un-blocked). */
    data object AudioResumed : RouteEvent
    data object Stop : RouteEvent
}

object RouteLifecycle {
    private fun active(s: RouteState) = s != RouteState.Idle && s !is RouteState.Failed

    fun reduce(s: RouteState, e: RouteEvent): RouteState = when (e) {
        RouteEvent.Start -> if (active(s)) s else RouteState.NeedsConsent
        RouteEvent.ConsentGranted -> if (s == RouteState.NeedsConsent) RouteState.Capturing else s
        RouteEvent.ConsentDenied -> if (s == RouteState.NeedsConsent) RouteState.Idle else s
        RouteEvent.CaptureReady -> if (s == RouteState.Capturing) RouteState.StartingSpeaker else s
        RouteEvent.SpeakerStarted -> if (s == RouteState.StartingSpeaker) RouteState.Playing else s
        is RouteEvent.Error -> if (active(s)) RouteState.Failed(e.reason) else s
        is RouteEvent.OptedOut -> if (s == RouteState.Playing || s is RouteState.Blocked) RouteState.Blocked(e.app) else s
        RouteEvent.AudioResumed -> if (s is RouteState.Blocked) RouteState.Playing else s
        RouteEvent.Stop -> RouteState.Idle
    }

    /** True while the route owns the TV mute and the capture: everything but Idle and Failed. */
    fun holdsResources(s: RouteState) = s != RouteState.Idle && s !is RouteState.Failed && s != RouteState.NeedsConsent

    /** The platter's state line, or null when nothing needs saying. */
    fun label(s: RouteState): String? = when (s) {
        RouteState.Idle -> null
        RouteState.NeedsConsent -> "Waiting for permission"
        RouteState.Capturing, RouteState.StartingSpeaker -> "Connecting…"
        RouteState.Playing -> "Playing on Sonos"
        is RouteState.Blocked -> "${s.app} doesn't allow playing on other speakers"
        is RouteState.Failed -> "Couldn't play on Sonos"
    }
}

/**
 * Opt-outs are never bypassed. An app that declared allowAudioPlaybackCapture=false ([appAllows] false) is blocked at
 * once; an app we cannot read ([appAllows] null) is judged by silence: a session that plays but yields no PCM for
 * [silenceLimitMs]. Both keep the sound on the TV.
 */
object CapturePolicy {
    const val SILENCE_LIMIT_MS = 5_000L

    fun blocked(appAllows: Boolean?, sessionPlaying: Boolean, silentMs: Long, silenceLimitMs: Long = SILENCE_LIMIT_MS): Boolean {
        if (!sessionPlaying) return false
        return appAllows == false || silentMs >= silenceLimitMs
    }
}

/** Counts how long the captured PCM has been all zeros. */
class SilenceDetector(private val clock: () -> Long) {
    private var since: Long? = null

    fun onPcm(buf: ByteArray, len: Int) {
        var loud = false
        var i = 0
        while (i < len) { if (buf[i].toInt() != 0) { loud = true; break }; i++ }
        if (loud) since = null else if (since == null) since = clock()
    }

    fun silentMs(): Long = since?.let { clock() - it } ?: 0L
    fun reset() { since = null }
}

/** The local TV mute ("Mute TV while playing on Sonos"). The speaker's volume is never involved. */
interface StreamMuter {
    fun isMuted(): Boolean
    fun setMuted(muted: Boolean)
}

/** Mutes on [engage] when enabled and restores on [release] only what it changed; releasing twice is harmless. */
class TvMuteGuard(private val muter: StreamMuter) {
    private var changed = false

    fun engage(enabled: Boolean) {
        if (!enabled || changed) return
        if (!muter.isMuted()) { muter.setMuted(true); changed = true }
    }

    fun release() {
        if (changed) { muter.setMuted(false); changed = false }
    }
}
