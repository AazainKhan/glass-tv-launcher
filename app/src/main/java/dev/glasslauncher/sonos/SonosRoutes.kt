package dev.glasslauncher.sonos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import dev.glasslauncher.data.AudioOutputConfig
import dev.glasslauncher.widgets.NowPlaying
import dev.glasslauncher.widgets.NowPlayingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Plays Glass's audio (captured from other apps) on one Sonos speaker. Order: consent, capture, SonosSession.start.
 * Consent is asked every session in every install mode (no appops grant, no root shortcut). The speaker volume is
 * never touched. Stop ends the session and the capture and restores the TV mute.
 */
object SonosRoutes {
    private val _state = MutableStateFlow<RouteState>(RouteState.Idle)
    val state: StateFlow<RouteState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var target: AudioOutputConfig? = null
    private var muteTv = false
    private var guard: TvMuteGuard? = null
    private var source: CaptureSource? = null
    private var session: SonosSession? = null

    @Synchronized
    private fun dispatch(e: RouteEvent) { _state.value = RouteLifecycle.reduce(_state.value, e) }

    /** Asks for consent (RECORD_AUDIO, then the projection dialog); everything else follows from the result. */
    @Synchronized
    fun start(context: Context, speaker: AudioOutputConfig, muteTvWhilePlaying: Boolean) {
        if (_state.value != RouteState.Idle && _state.value !is RouteState.Failed) return
        _state.value = RouteState.Idle
        target = speaker; muteTv = muteTvWhilePlaying
        dispatch(RouteEvent.Start)
        context.startActivity(Intent(context, CaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun onConsent(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) { dispatch(RouteEvent.ConsentDenied); return }
        dispatch(RouteEvent.ConsentGranted)
        context.startForegroundService(Intent(context, CaptureService::class.java).putExtra("code", resultCode).putExtra("data", data))
    }

    fun onDenied() = dispatch(RouteEvent.ConsentDenied)

    /** The foreground service has its projection: open the capture, start the speaker, pump. */
    fun onProjection(context: Context, projection: MediaProjection) {
        val app = context.applicationContext
        val speaker = target ?: return stop(app)
        job = scope.launch {
            val src = CaptureSource(projection)
            val g = TvMuteGuard(app.getSystemService(AudioManager::class.java).let(::MusicMuter))
            val s = try {
                if (android.os.Build.VERSION.SDK_INT >= 29) src.open() else error("audio capture needs Android 10")
                source = src; guard = g
                dispatch(RouteEvent.CaptureReady)
                val ip = localIp() ?: error("no network")
                SonosSession(speaker.host, ip).also { it.start(); session = it }
            } catch (e: Exception) {
                dispatch(RouteEvent.Error(e.message ?: "failed")); cleanup(app); return@launch
            }
            dispatch(RouteEvent.SpeakerStarted)
            g.engage(muteTv)
            pump(app, src, s, g)
        }
    }

    private suspend fun pump(context: Context, src: CaptureSource, s: SonosSession, g: TvMuteGuard) {
        val ring = s.ring ?: return
        val clock = { System.currentTimeMillis() }
        val silence = SilenceDetector(clock)
        var playing: NowPlaying? = null
        val watch = scope.launch { NowPlayingSource.flow(context).collect { playing = it } }
        val buf = ByteArray(3840) // 20 ms
        var lastCheck = 0L
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val n = src.read(buf)
                if (n < 0) { dispatch(RouteEvent.Error("capture ended")); break }
                if (n == 0) { delay(10); continue }
                ring.write(buf, 0, n)
                silence.onPcm(buf, n)
                val now = clock()
                if (now - lastCheck < 1_000) continue
                lastCheck = now
                val np = playing
                val blocked = np != null && CapturePolicy.blocked(AppCapture.allows(context, np.packageName), np.playing, silence.silentMs())
                if (blocked) {
                    dispatch(RouteEvent.OptedOut(AppCapture.label(context, np!!.packageName)))
                    g.release() // the TV keeps the sound
                } else if (_state.value is RouteState.Blocked) {
                    dispatch(RouteEvent.AudioResumed); g.engage(muteTv)
                }
            }
        } finally { watch.cancel() }
        if (_state.value is RouteState.Failed) cleanup(context)
    }

    fun stop(context: Context) {
        job?.cancel(); job = null
        scope.launch { runCatching { session?.stop() }; cleanup(context) }
        dispatch(RouteEvent.Stop)
    }

    @Synchronized
    private fun cleanup(context: Context) {
        guard?.release(); guard = null
        source?.release(); source = null
        session = null
        context.stopService(Intent(context, CaptureService::class.java))
    }

    private fun localIp(): InetAddress? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
}

private class MusicMuter(private val audio: AudioManager) : StreamMuter {
    override fun isMuted() = audio.isStreamMute(AudioManager.STREAM_MUSIC)
    override fun setMuted(muted: Boolean) =
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
}

/** Reads an app's allowAudioPlaybackCapture opt-out where the platform exposes it. */
object AppCapture {
    /** false = the app opted out; true = allowed; null = not readable here (silence detection decides). */
    fun allows(context: Context, pkg: String): Boolean? = try {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        ApplicationInfo::class.java.getMethod("isAudioPlaybackCaptureAllowed").invoke(info) as? Boolean
    } catch (_: Throwable) { null }

    fun label(context: Context, pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}
