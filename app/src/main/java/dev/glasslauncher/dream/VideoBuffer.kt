package dev.glasslauncher.dream

import androidx.media3.exoplayer.DefaultLoadControl

/**
 * How much of a background video Glass buffers. ExoPlayer's defaults are made for films (up to 50 s ahead
 * and a 128 MB video buffer); a looping Aerial or Home's motion background needs a few seconds, and that
 * buffer counts toward Glass's memory. A cached clip (Aerials) refills from disk almost instantly.
 */
object VideoBuffer {
    const val MIN_MS = 5_000
    const val MAX_MS = 15_000
    const val START_MS = 1_500
    const val RESTART_MS = 3_000
    const val BACK_MS = 0
    /** ~15 s of a 1080p Aerial (about 12 Mbit/s). */
    const val TARGET_BYTES = 24 * 1024 * 1024

    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(MIN_MS, MAX_MS, START_MS, RESTART_MS)
        .setBackBuffer(BACK_MS, false)
        .setTargetBufferBytes(TARGET_BYTES)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
}
