package dev.glasslauncher.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * P144 spike, debug builds only. Measures whether Fire OS enforces AOSP's privileged-capture cap
 * (16 kHz mono) on an AudioPolicy mix over the SYSTEM MIX of the given usages (no uid/package rule).
 * Audio is read for a few seconds, reduced to RMS/peak and discarded: never stored or sent anywhere.
 * Result: filesDir-or-external `capture-probe.txt` plus logcat tag [TAG].
 * Everything platform-side is reflection because the AudioPolicy classes are hidden on API 30.
 */
object CaptureProbe {
    const val TAG = "GlassCaptureProbe"
    private const val RECORD_MS = 4000L
    private const val HARD_TIMEOUT_MS = 15_000L
    private const val RULE_MATCH_ATTRIBUTE_USAGE = 1
    private const val ROUTE_RENDER_AND_LOOP_BACK = 1 or 2 // keep playing on the TV while capturing

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start(context: Context, usages: List<Int>, onDone: () -> Unit = {}) {
        if (!running.compareAndSet(false, true)) { Log.w(TAG, "already running"); onDone(); return }
        val app = context.applicationContext
        scope.launch {
            try {
                val text = runCatching { coroutineScope { run(app, usages) } }
                    .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else "ERROR ${it}" }
                write(app, text)
            } finally {
                running.set(false)
                onDone()
            }
        }
    }

    private fun write(context: Context, text: String) {
        Log.i(TAG, text)
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        runCatching { File(dir, "capture-probe.txt").writeText(text) }
            .onFailure { Log.e(TAG, "cannot write result: $it") }
    }

    private suspend fun run(context: Context, usages: List<Int>): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t0 = SystemClock.elapsedRealtime()
        val lines = mutableListOf<String>()
        val attempts = mutableListOf<String>()
        var policy: Any? = null
        var record: AudioRecord? = null
        var granted: ProbeFormat? = null
        var refusal: String? = null
        var level = ProbeLogic.Level(0.0, 0, 0)
        var frames = 0L
        var bufferBytes = 0
        var activePlayers = "n/a"
        try {
            for (fmt in ProbeLogic.LADDER) {
                try {
                    val p = buildPolicy(context, usages, fmt)
                    val rc = Reflect.call(am, "registerAudioPolicy", p) as Int
                    if (rc != 0) { attempts += "$fmt: registerAudioPolicy=$rc"; continue }
                    policy = p
                    val mix = Reflect.call(Reflect.call(p, "getMixes") as List<*>, "get", 0)
                    record = Reflect.call(p, "createAudioRecordSink", mix) as AudioRecord
                    granted = fmt
                    attempts += "$fmt: registered"
                    break
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val c = (e as? InvocationTargetException)?.cause ?: e
                    attempts += "$fmt: ${c.javaClass.simpleName}: ${c.message}"
                    refusal = "${c.javaClass.simpleName}: ${c.message}"
                    policy?.let { runCatching { Reflect.call(am, "unregisterAudioPolicy", it) } }
                    policy = null
                }
            }
            if (record != null) {
                val rec = record
                bufferBytes = rec.bufferSizeInFrames * rec.channelCount * 2
                withContext(Dispatchers.IO) {
                    // A read can block when nothing plays; the watchdog stop() unblocks it.
                    val watchdog = launch { delay(HARD_TIMEOUT_MS - 1000); runCatching { rec.stop() } }
                    try {
                        rec.startRecording()
                        val buf = ShortArray(rec.sampleRate * rec.channelCount / 10)
                        val end = SystemClock.elapsedRealtime() + RECORD_MS
                        var polled = false
                        while (SystemClock.elapsedRealtime() < end) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n <= 0) break
                            level = ProbeLogic.level(buf, n, level)
                            frames += n / rec.channelCount
                            if (!polled && SystemClock.elapsedRealtime() > end - RECORD_MS / 2) {
                                polled = true; activePlayers = activePlayers(am)
                            }
                        }
                    } finally {
                        watchdog.cancel()
                    }
                }
            }
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            policy?.let { runCatching { Reflect.call(am, "unregisterAudioPolicy", it) } }
        }
        val rec = record
        val elapsed = SystemClock.elapsedRealtime() - t0
        val verdict = ProbeLogic.verdict(granted, refusal)
        lines += verdict
        lines += "usages in rule: ${usages.joinToString { ProbeLogic.usageName(it) }} (system mix, no uid/package rule)"
        lines += "requested: ${ProbeLogic.LADDER.first()}; ladder attempts: ${attempts.joinToString(" | ")}"
        lines += "registered format: ${granted ?: "none"}"
        if (rec != null) {
            lines += "AudioRecord granted: ${rec.sampleRate} Hz, ${rec.channelCount} ch, channelMask=${rec.channelConfiguration}, encoding=${rec.audioFormat}, buffer=$bufferBytes bytes"
        }
        lines += "frames read: $frames (${if (granted != null && frames > 0) frames * 1000 / granted.sampleRate else 0} ms of audio)"
        lines += "level: rms=${"%.1f".format(level.rms)} (${"%.1f".format(level.rmsDbfs)} dBFS) peak=${level.peak} " +
            if (frames == 0L) "NO DATA" else if (level.silent) "SILENT" else "AUDIO"
        lines += "active playback during capture: $activePlayers"
        lines += "elapsed: $elapsed ms; API ${Build.VERSION.SDK_INT}; ${Build.FINGERPRINT}"
        return lines.joinToString("\n")
    }

    /** Usage and allowedCapturePolicy of each active player: tells "silent because nothing plays" from "app opted out". */
    private fun activePlayers(am: AudioManager): String =
        am.activePlaybackConfigurations.joinToString("; ") {
            val a = it.audioAttributes
            // getAllowedCapturePolicy is API 29; the stick is API 30, the project's minSdk is 28.
            if (Build.VERSION.SDK_INT < 29) return@joinToString ProbeLogic.usageName(a.usage) + " capturePolicy=unknown (API < 29)"
            "${ProbeLogic.usageName(a.usage)} capturePolicy=${a.allowedCapturePolicy}" +
                " (${when (a.allowedCapturePolicy) { AudioAttributes.ALLOW_CAPTURE_BY_ALL -> "ALL"; AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM -> "SYSTEM"; else -> "NONE" }})"
        }.ifEmpty { "none" }

    private fun buildPolicy(context: Context, usages: List<Int>, fmt: ProbeFormat): Any {
        val rule = Reflect.new("android.media.audiopolicy.AudioMixingRule\$Builder")
        for (u in usages) {
            val attrs = AudioAttributes.Builder().setUsage(u).build()
            Reflect.call(rule, "addMixRule", RULE_MATCH_ATTRIBUTE_USAGE, attrs)
        }
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(fmt.sampleRate)
            .setChannelMask(if (fmt.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO).build()
        val mix = Reflect.new("android.media.audiopolicy.AudioMix\$Builder", Reflect.call(rule, "build"))
        Reflect.call(mix, "setFormat", format)
        Reflect.call(mix, "setRouteFlags", ROUTE_RENDER_AND_LOOP_BACK)
        val pb = Reflect.new("android.media.audiopolicy.AudioPolicy\$Builder", context)
        Reflect.call(pb, "addMix", Reflect.call(mix, "build"))
        Reflect.call(pb, "setLooper", Looper.getMainLooper())
        return Reflect.call(pb, "build")!!
    }
}

/** Minimal reflection over hidden API: matches by name and argument count/assignability. */
private object Reflect {
    fun new(cls: String, vararg args: Any?): Any {
        val c = Class.forName(cls)
        val ctor = c.declaredConstructors.first { fits(it.parameterTypes, args) }
        ctor.isAccessible = true
        return ctor.newInstance(*args)
    }

    fun call(target: Any?, name: String, vararg args: Any?): Any? {
        val m = generateSequence<Class<*>>(target!!.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .first { it.name == name && fits(it.parameterTypes, args) }
        m.isAccessible = true
        return m.invoke(target, *args)
    }

    private fun fits(types: Array<Class<*>>, args: Array<out Any?>) =
        types.size == args.size && types.indices.all { i ->
            val t = types[i]; val a = args[i]
            when (t) {
                Int::class.javaPrimitiveType -> a is Int
                else -> a == null || t.isInstance(a)
            }
        }
}

/** Debug-only trigger: `adb shell am broadcast -a dev.glasslauncher.debug.CAPTURE_PROBE [--es usages game,unknown]`. */
class CaptureProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CaptureProbe.start(context, ProbeLogic.parseUsages(intent.getStringExtra("usages"))) { pending.finish() }
    }
}
