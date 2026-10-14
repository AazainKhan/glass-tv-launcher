package dev.glasslauncher.sonos

import dev.glasslauncher.data.AudioOutputConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where PCM comes from: 48 kHz 16-bit stereo little-endian. [read] returns the byte count, 0 for none yet, -1 at the end. */
interface PcmCapture {
    fun open()
    fun read(buf: ByteArray): Int
    fun release()
}

/** The speaker side of a route. [SonosSession] is the real one. */
interface SpeakerSession {
    val ring: PcmRingBuffer?
    suspend fun start()
    suspend fun stop()
    suspend fun isPlaying(): Boolean
}

/** What the foreground media session is doing, for the opt-out decision. */
data class PlayingApp(val label: String, val playing: Boolean, val allowsCapture: Boolean?)

/**
 * The route's lifecycle, free of Android so it can be tested: consent, then capture, then the speaker. Every exit
 * (Stop, capture end, an error, the speaker dropping, cancellation mid-start) runs the same [cleanup]: stop the
 * session (which sends the speaker Stop and closes the HTTP server), release the capture, restore the TV mute, and
 * stop the foreground service. Never sets the speaker volume.
 */
class RouteRunner(
    private val scope: CoroutineScope,
    private val muter: StreamMuter,
    flag: MuteFlag,
    private val sessionFor: (AudioOutputConfig) -> SpeakerSession,
    private val playingApp: () -> PlayingApp?,
    private val teardown: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val checkEveryMs: Long = 1_000,
    private val transportEveryMs: Long = 10_000,
) {
    private val _state = MutableStateFlow<RouteState>(RouteState.Idle)
    val state: StateFlow<RouteState> = _state

    private val guard = TvMuteGuard(muter, flag)
    private var job: Job? = null
    private var target: AudioOutputConfig? = null
    private var targets: List<AudioOutputConfig> = emptyList()
    @Volatile private var muteTv = false
    @Volatile private var pcmVerified = false // real (non-silent) PCM is flowing
    private var capture: PcmCapture? = null

    /** One speaker the PCM goes to. Several run at once (network speakers multi-select); [ready] once its session has started. */
    private class Leg(val speaker: AudioOutputConfig, val session: SpeakerSession) {
        @Volatile var ready = false
        var misses = 0
    }
    private val legs = mutableListOf<Leg>() // guarded by this
    private var epoch = 0 // bumped when a route begins or ends, so a leg that finishes starting after that can tell it is orphaned; guarded by this
    private val adding = mutableSetOf<String>() // hosts whose add is in flight, so a double click adds once; guarded by this

    @Synchronized private fun dispatch(e: RouteEvent) { _state.value = RouteLifecycle.reduce(_state.value, e) }

    /** App start: undo a TV mute a crashed or killed run left behind. */
    fun recoverMute() = guard.recover()

    /** Begins a route to one speaker; see [begin] with a list. */
    fun begin(speaker: AudioOutputConfig, muteTvWhilePlaying: Boolean): Boolean = begin(listOf(speaker), muteTvWhilePlaying)

    /** Begins a route to every speaker in [speakers] (at least one); the caller then asks for consent. Ignored while one is active. */
    @Synchronized fun begin(speakers: List<AudioOutputConfig>, muteTvWhilePlaying: Boolean): Boolean {
        if (speakers.isEmpty()) return false
        val s = _state.value
        if (s != RouteState.Idle && s !is RouteState.Failed) return false
        _state.value = RouteState.Idle
        epoch++
        targets = speakers.distinctBy { it.host }; target = targets.first(); muteTv = muteTvWhilePlaying
        dispatch(RouteEvent.Start)
        return true
    }

    /** The toggle changed mid-route: ON engages the mute if PCM is already verified, OFF releases it. */
    fun setMuteTv(enabled: Boolean) {
        muteTv = enabled
        if (enabled) { if (pcmVerified && _state.value == RouteState.Playing) guard.engage(true) }
        else guard.release()
    }

    fun consentGranted() = dispatch(RouteEvent.ConsentGranted)
    fun consentDenied() = dispatch(RouteEvent.ConsentDenied)

    /** True while something is held that [stop] would release. */
    fun holdsResources() = RouteLifecycle.holdsResources(_state.value)

    /** The foreground service has its capture: open it, start the speakers, pump until stopped. */
    fun run(cap: PcmCapture) {
        val speakers = synchronized(this) {
            if (_state.value != RouteState.Capturing) { runCatching { cap.release() }; return }
            capture = cap // before open(): a throw in open() must still release the projection
            targets
        }
        if (speakers.isEmpty()) return stop()
        val j = scope.launch(start = CoroutineStart.LAZY) {
            try {
                cap.open()
                ensureActive()
                dispatch(RouteEvent.CaptureReady)
                if (_state.value != RouteState.StartingSpeaker) return@launch // stopped meanwhile
                val errors = kotlinx.coroutines.coroutineScope { speakers.map { sp -> async { startLeg(sp) } }.awaitAll() }
                ensureActive()
                if (synchronized(this@RouteRunner) { legs.none { it.ready } }) {
                    dispatch(RouteEvent.Error(errors.firstNotNullOfOrNull { it } ?: "no speaker started"))
                    return@launch
                }
                dispatch(RouteEvent.SpeakerStarted)
                if (_state.value != RouteState.Playing) return@launch
                pump(cap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("route", target?.host, e)
                dispatch(RouteEvent.Error(e.message ?: e.javaClass.simpleName))
            } finally {
                withContext(NonCancellable) { cleanup() }
            }
        }
        synchronized(this) { job = j }
        j.start()
    }

    /** Starts one speaker's session. The leg is listed before start(): cleanup must stop a half-started session. Null on success, else the reason. */
    private suspend fun startLeg(sp: AudioOutputConfig): String? {
        val leg = try { Leg(sp, sessionFor(sp)) } catch (e: Exception) { warn("session", sp.host, e); return e.message ?: e.javaClass.simpleName }
        val born = synchronized(this) { legs += leg; epoch }
        return try {
            leg.session.start()
            // stop() or cleanup() may have run while start() was in flight: the route is gone, so this speaker must not keep playing.
            val live = synchronized(this) { (born == epoch && leg in legs).also { if (it) leg.ready = true else legs -= leg } }
            if (!live) {
                runCatching { leg.session.stop() }
                return "route stopped"
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("speaker start", sp.host, e)
            synchronized(this) { legs -= leg }
            runCatching { leg.session.stop() }
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Adds a speaker to a route that is playing (network speakers multi-select). False when no route plays or it is already there. */
    fun addSpeaker(sp: AudioOutputConfig): Boolean {
        val ok = synchronized(this) {
            val st = _state.value
            (st == RouteState.Playing || st is RouteState.Blocked) && legs.none { it.speaker.host == sp.host } && adding.add(sp.host)
        }
        if (!ok) return false
        scope.launch {
            try {
                val err = startLeg(sp)
                if (err != null) warn("add speaker", sp.host, IllegalStateException(err))
            } finally { synchronized(this@RouteRunner) { adding -= sp.host } }
        }
        return true
    }

    /** Takes one speaker off a playing route; the last one off stops the route. */
    fun removeSpeaker(host: String) {
        val leg = synchronized(this) { legs.firstOrNull { it.speaker.host == host }?.also { legs -= it } } ?: return
        scope.launch {
            runCatching { leg.session.stop() }.onFailure { warn("session stop", host, it) }
            if (synchronized(this@RouteRunner) { legs.isEmpty() }) stop()
        }
    }

    private suspend fun pump(cap: PcmCapture) {
        val silence = SilenceDetector(clock)
        val buf = ByteArray(3840) // 20 ms
        var lastCheck = clock()
        var lastTransport = clock()
        while (currentCoroutineContext().isActive) {
            val n = cap.read(buf)
            if (n < 0) { dispatch(RouteEvent.Error("capture ended")); return }
            if (n == 0) { delay(10) } else {
                val live = synchronized(this) { legs.filter { it.ready } }
                for (leg in live) leg.session.ring?.write(buf, 0, n)
                // The TV stays loud until real PCM has arrived; then it is muted (if the user asked for that).
                if (silence.onPcm(buf, n)) {
                    pcmVerified = true
                    if (_state.value is RouteState.Blocked) dispatch(RouteEvent.AudioResumed)
                    if (_state.value == RouteState.Playing) guard.engage(muteTv)
                }
            }
            val now = clock()
            if (now - lastCheck >= checkEveryMs) {
                lastCheck = now
                val app = playingApp()
                // Silence is judged even with no media session; a session just adds who to name and the flag.
                val blocked = app != null && CapturePolicy.blocked(app.allowsCapture, app.playing, silence.heard, silence.silentMs())
                if (blocked) {
                    dispatch(RouteEvent.OptedOut(app!!.label))
                    pcmVerified = false
                    guard.release() // the TV keeps the sound
                }
            }
            if (now - lastTransport >= transportEveryMs) {
                lastTransport = now
                // A speaker that stopped twice in a row is let go; the route ends only when none is left.
                val live = synchronized(this) { legs.filter { it.ready } }
                for (leg in live) {
                    leg.misses = if (leg.session.isPlaying()) 0 else leg.misses + 1
                    if (leg.misses >= 2) {
                        synchronized(this) { legs -= leg }
                        runCatching { leg.session.stop() }
                    }
                }
                if (synchronized(this) { legs.none { it.ready } }) { dispatch(RouteEvent.Error("speaker stopped")); return }
            }
        }
    }

    /** Stops from any state. Safe to call twice and from any thread; cleanup is idempotent. */
    fun stop() {
        val j = synchronized(this) { dispatch(RouteEvent.Stop); epoch++; job }
        j?.cancel() // its finally block runs cleanup too (a job cancelled before it started never does, hence the next line)
        scope.launch { cleanup() }
    }

    private suspend fun cleanup() {
        val all: List<Leg>
        val c: PcmCapture?
        synchronized(this) { epoch++; all = legs.toList(); legs.clear(); c = capture; capture = null }
        for (leg in all) runCatching { leg.session.stop() }.onFailure { warn("session stop", leg.speaker.host, it) }
        pcmVerified = false
        guard.release()
        runCatching { c?.release() }
        synchronized(this) { job = null }
        teardown()
    }
}
