package dev.glasslauncher.debug

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Pure parts of the P144 capture probe (JVM-tested). */
data class ProbeFormat(val sampleRate: Int, val channels: Int) {
    override fun toString() = "${sampleRate}Hz/${if (channels == 1) "mono" else "${channels}ch"}"
}

object ProbeLogic {
    // Widest first: the first one the platform accepts is what a privileged mix is allowed.
    val LADDER = listOf(ProbeFormat(48000, 2), ProbeFormat(44100, 2), ProbeFormat(48000, 1), ProbeFormat(16000, 1))

    // AudioAttributes.USAGE_* values (stable framework constants).
    private val USAGES = mapOf(
        "unknown" to 0, "media" to 1, "voice" to 2, "alarm" to 4, "notification" to 5,
        "assistance_sonification" to 13, "game" to 14, "assistant" to 16,
    )

    /** "game,unknown" -> [1, 14, 0]; USAGE_MEDIA is always first. Unknown names are ignored. */
    fun parseUsages(csv: String?): List<Int> =
        (listOf(1) + csv.orEmpty().split(',').mapNotNull { USAGES[it.trim().lowercase()] }).distinct()

    fun usageName(usage: Int) = USAGES.entries.firstOrNull { it.value == usage }?.key ?: "usage$usage"

    fun verdict(granted: ProbeFormat?, refusal: String?): String = when {
        granted == null -> "REGISTRATION REFUSED: ${refusal ?: "unknown"}"
        granted.sampleRate <= 16000 && granted.channels == 1 -> "CAP 16k/mono ENFORCED"
        granted.sampleRate >= 44100 && granted.channels >= 2 -> "FULL FORMAT ALLOWED (${granted.sampleRate / 1000}k stereo)"
        else -> "PARTIAL: only $granted"
    }

    data class Level(val rms: Double, val peak: Int, val samples: Long) {
        val rmsDbfs get() = if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20 * log10(rms / 32768.0)
        val silent get() = peak == 0
    }

    fun level(pcm: ShortArray, count: Int, prior: Level? = null): Level {
        var sum = prior?.let { it.rms * it.rms * it.samples } ?: 0.0
        var peak = prior?.peak ?: 0
        for (i in 0 until count) {
            val v = pcm[i].toInt()
            sum += v.toDouble() * v
            peak = maxOf(peak, abs(v))
        }
        val n = (prior?.samples ?: 0L) + count
        return Level(if (n == 0L) 0.0 else sqrt(sum / n), peak, n)
    }
}
