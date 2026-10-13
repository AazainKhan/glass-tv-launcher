package dev.glasslauncher.sonos

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build

/**
 * Captures what other apps play (USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN) as 48 kHz 16-bit stereo little-endian PCM.
 * Apps that opted out (allowAudioPlaybackCapture=false) are silent here; the system enforces it and we never work
 * around it. Needs RECORD_AUDIO and a live [MediaProjection] from a foreground service of type mediaProjection.
 */
class CaptureSource(private val projection: MediaProjection) : PcmCapture {
    private var rec: AudioRecord? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by CaptureConsentActivity before the projection exists.
    override fun open() {
        if (Build.VERSION.SDK_INT < 29) error("audio capture needs Android 10")
        openApi29()
    }

    @androidx.annotation.RequiresApi(29)
    @SuppressLint("MissingPermission")
    private fun openApi29() {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(cfg)
            .setBufferSizeInBytes(maxOf(min, RATE / 5 * 4)).build()
        rec = r // kept before it starts, so release() frees it if startRecording throws
        r.startRecording()
    }

    override fun read(buf: ByteArray): Int = rec?.read(buf, 0, buf.size) ?: -1

    override fun release() {
        runCatching { rec?.stop() }; runCatching { rec?.release() }; rec = null
        runCatching { projection.stop() }
    }

    companion object { const val RATE = 48_000 }
}
