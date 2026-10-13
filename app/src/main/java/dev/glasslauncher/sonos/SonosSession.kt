package dev.glasslauncher.sonos

import java.net.InetAddress

/**
 * Streams Glass PCM to one Sonos speaker.
 *
 * WARNING: [start] calls SetAVTransportURI, which REPLACES the speaker's current queue and
 * whatever it is playing. The previous queue is not restored by [stop].
 *
 * Write 48 kHz/16-bit stereo little-endian PCM into [ring]. Silence is sent on underrun.
 */
class SonosSession(
    private val speakerHost: String,
    private val stickIp: InetAddress,
    private val control: SonosControl = SonosControl("http://$speakerHost:1400"),
) {
    private var server: PcmHttpServer? = null
    val ring: PcmRingBuffer? get() = server?.ring

    suspend fun start() {
        check(server == null) { "already started" }
        val s = PcmHttpServer(stickIp, InetAddress.getByName(speakerHost))
        val port = s.start()
        server = s
        try {
            val url = "http://${stickIp.hostAddress}:$port/glass.wav"
            control.setAvTransportUri(url) // http + radio DIDL; SonosControl.radioUri() is the x-rincon-mp3radio alternative
            control.play()
        } catch (e: Exception) {
            s.stop(); server = null
            throw e
        }
    }

    suspend fun stop() {
        try { control.stop() } finally { server?.stop(); server = null }
    }

    suspend fun setVolume(v: Int) = control.setVolume(v)
}
