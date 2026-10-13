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
 * Opt-outs are never bypassed. A playing app that declared allowAudioPlaybackCapture=false ([appAllows] false) is
 * blocked at once. Otherwise silence decides, and only for a session that plays: [SILENCE_LIMIT_MS] of silence after
 * real PCM was heard, or [NEVER_HEARD_LIMIT_MS] when none ever came (an opted-out app we cannot read). No session
 * playing means nothing to judge, so a pause or a gap between tracks never counts. Blocked keeps the sound on the TV.
 */
object CapturePolicy {
    const val SILENCE_LIMIT_MS = 10_000L
    const val NEVER_HEARD_LIMIT_MS = 15_000L

    fun blocked(appAllows: Boolean?, sessionPlaying: Boolean, heardPcm: Boolean, silentMs: Long): Boolean {
        if (!sessionPlaying) return false
        if (appAllows == false) return true
        return silentMs >= if (heardPcm) SILENCE_LIMIT_MS else NEVER_HEARD_LIMIT_MS
    }
}

/** Counts how long the captured PCM has been all zeros. */
class SilenceDetector(private val clock: () -> Long) {
    private var since: Long? = clock()
    /** True once any non-zero PCM has arrived. */
    var heard = false
        private set

    /** Returns true when this buffer holds sound. */
    fun onPcm(buf: ByteArray, len: Int): Boolean {
        var loud = false
        var i = 0
        while (i < len) { if (buf[i].toInt() != 0) { loud = true; break }; i++ }
        if (loud) { since = null; heard = true } else if (since == null) since = clock()
        return loud
    }

    /** How long the PCM has been silent, counted from the start of capture until the first sound. */
    fun silentMs(): Long = since?.let { clock() - it } ?: 0L
}

/** The local TV mute ("Mute TV while playing on Sonos"). The speaker's volume is never involved. */
interface StreamMuter {
    fun isMuted(): Boolean
    fun setMuted(muted: Boolean)
}

/** Survives a crash or process death: set while Glass is the one holding the TV muted. */
interface MuteFlag {
    var heldByGlass: Boolean
}

/**
 * Mutes on [engage] when enabled and restores on [release] only what it changed; releasing twice is harmless.
 * The "Glass muted the TV" flag is persisted, so [recover] at the next app start unmutes after a crash.
 */
class TvMuteGuard(private val muter: StreamMuter, private val flag: MuteFlag) {
    private var changed = false

    @Synchronized fun engage(enabled: Boolean) {
        if (!enabled || changed) return
        if (!muter.isMuted()) { flag.heldByGlass = true; muter.setMuted(true); changed = true }
    }

    @Synchronized fun release() {
        if (changed) { muter.setMuted(false); changed = false }
        flag.heldByGlass = false
    }

    /** App start: if a previous run left the TV muted by us, unmute it and clear the flag. */
    @Synchronized fun recover() {
        if (flag.heldByGlass) { muter.setMuted(false); flag.heldByGlass = false }
    }
}

/**
 * Whether "Mute TV while playing on Sonos" can do anything. A local STREAM_MUSIC mute is ignored on fixed-volume
 * outputs, and on Fire TV that is HDMI (the TV owns the volume over CEC; measured 2026-10-13: mFixedVolumeDevices
 * includes HDMI and ADJUST_MUTE left the stream unmuted). Speakers and Bluetooth outputs mute locally.
 */
object MuteSupport {
    fun supported(volumeFixed: Boolean, hdmiOut: Boolean, bluetoothOut: Boolean): Boolean =
        !volumeFixed && (!hdmiOut || bluetoothOut)
}
