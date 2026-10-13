package dev.glasslauncher.sonos

import dev.glasslauncher.data.AudioOutputConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    @Volatile private var muteTv = false
    @Volatile private var pcmVerified = false // real (non-silent) PCM is flowing
    private var capture: PcmCapture? = null
    private var session: SpeakerSession? = null

    @Synchronized private fun dispatch(e: RouteEvent) { _state.value = RouteLifecycle.reduce(_state.value, e) }

    /** App start: undo a TV mute a crashed or killed run left behind. */
    fun recoverMute() = guard.recover()

    /** Begins a route; the caller then asks for consent. Ignored while one is active. */
    @Synchronized fun begin(speaker: AudioOutputConfig, muteTvWhilePlaying: Boolean): Boolean {
        val s = _state.value
        if (s != RouteState.Idle && s !is RouteState.Failed) return false
        _state.value = RouteState.Idle
        target = speaker; muteTv = muteTvWhilePlaying
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

    /** The foreground service has its capture: open it, start the speaker, pump until stopped. */
    fun run(cap: PcmCapture) {
        val speaker = synchronized(this) {
            if (_state.value != RouteState.Capturing) { runCatching { cap.release() }; return }
            capture = cap // before open(): a throw in open() must still release the projection
            target
        } ?: return stop()
        val j = scope.launch(start = CoroutineStart.LAZY) {
            try {
                cap.open()
                ensureActive()
                dispatch(RouteEvent.CaptureReady)
                if (_state.value != RouteState.StartingSpeaker) return@launch // stopped meanwhile
                val s = sessionFor(speaker)
                synchronized(this@RouteRunner) { session = s } // before start(): cleanup must stop a half-started session
                s.start()
                ensureActive()
                dispatch(RouteEvent.SpeakerStarted)
                if (_state.value != RouteState.Playing) return@launch
                pump(cap, s)
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

    private suspend fun pump(cap: PcmCapture, s: SpeakerSession) {
        val ring = s.ring ?: return
        val silence = SilenceDetector(clock)
        val buf = ByteArray(3840) // 20 ms
        var lastCheck = clock()
        var lastTransport = clock()
        var transportMisses = 0
        while (currentCoroutineContext().isActive) {
            val n = cap.read(buf)
            if (n < 0) { dispatch(RouteEvent.Error("capture ended")); return }
            if (n == 0) { delay(10) } else {
                ring.write(buf, 0, n)
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
                transportMisses = if (s.isPlaying()) 0 else transportMisses + 1
                if (transportMisses >= 2) { dispatch(RouteEvent.Error("speaker stopped")); return }
            }
        }
    }

    /** Stops from any state. Safe to call twice and from any thread; cleanup is idempotent. */
    fun stop() {
        val j = synchronized(this) { dispatch(RouteEvent.Stop); job }
        j?.cancel() // its finally block runs cleanup too (a job cancelled before it started never does, hence the next line)
        scope.launch { cleanup() }
    }

    private suspend fun cleanup() {
        val s: SpeakerSession?
        val c: PcmCapture?
        synchronized(this) { s = session; session = null; c = capture; capture = null }
        runCatching { s?.stop() }.onFailure { warn("session stop", target?.host, it) }
        pcmVerified = false
        guard.release()
        runCatching { c?.release() }
        synchronized(this) { job = null }
        teardown()
    }
}
