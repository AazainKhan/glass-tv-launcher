package dev.glasslauncher.sonos

import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Fixed-capacity byte ring buffer. A producer [write]s PCM; the server [read]s it.
 * [read] returns what is available and zero-fills the rest (silence on underrun), so the
 * stream clock never stalls. Old data is dropped when the producer overruns.
 */
class PcmRingBuffer(private val capacity: Int) {
    private val buf = ByteArray(capacity)
    private var head = 0
    private var size = 0

    @Synchronized fun write(src: ByteArray, off: Int = 0, len: Int = src.size) {
        var n = len
        var o = off
        if (n >= capacity) { o += n - capacity; n = capacity; head = 0; size = 0 }
        val overflow = size + n - capacity
        if (overflow > 0) { head = (head + overflow) % capacity; size -= overflow }
        val tail = (head + size) % capacity
        val first = minOf(n, capacity - tail)
        System.arraycopy(src, o, buf, tail, first)
        System.arraycopy(src, o + first, buf, 0, n - first)
        size += n
    }

    /** Fills [dst] fully; returns the number of real (non-silence) bytes. */
    @Synchronized fun read(dst: ByteArray, off: Int = 0, len: Int = dst.size): Int {
        val n = minOf(len, size)
        val first = minOf(n, capacity - head)
        System.arraycopy(buf, head, dst, off, first)
        System.arraycopy(buf, 0, dst, off + first, n - first)
        head = (head + n) % capacity
        size -= n
        java.util.Arrays.fill(dst, off + n, off + len, 0)
        return n
    }

    @Synchronized fun available(): Int = size
}

/**
 * Minimal local HTTP server streaming 48 kHz/16-bit stereo WAV at `/glass.wav`.
 * Binds to [bindAddress] (the Wi-Fi IP) on a random port and serves only [allowedClient].
 */
class PcmHttpServer(
    private val bindAddress: InetAddress,
    private val allowedClient: InetAddress,
    val ring: PcmRingBuffer = PcmRingBuffer(SAMPLE_RATE * FRAME_BYTES), // 1 s
) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    val port: Int get() = server?.localPort ?: -1

    fun start(): Int {
        val s = ServerSocket()
        s.bind(InetSocketAddress(bindAddress, 0))
        server = s
        running = true
        Thread({ acceptLoop(s) }, "glass-pcm-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val c = try { s.accept() } catch (_: Exception) { return }
            if (c.inetAddress != allowedClient) { c.close(); continue }
            Thread({ serve(c) }, "glass-pcm-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(c: Socket) = c.use {
        try {
            // Read and discard the request head (cap at 8 KB).
            val inp = c.getInputStream()
            var tail = 0
            var n = 0
            while (n++ < 8192) {
                val b = inp.read()
                if (b < 0) return
                tail = (tail shl 8) or b
                if (tail == 0x0D0A0D0A) break
            }
            val out = c.getOutputStream()
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nConnection: close\r\n" +
                "Cache-Control: no-cache\r\n\r\n").toByteArray(Charsets.US_ASCII))
            out.write(wavHeader())
            stream(out)
        } catch (_: Exception) { }
    }

    private fun stream(out: OutputStream) {
        val chunk = ByteArray(CHUNK_BYTES)
        var next = System.nanoTime()
        while (running) {
            ring.read(chunk)
            out.write(chunk)
            out.flush()
            // Pace at real time so the speaker buffer is not flooded with silence.
            next += CHUNK_MS * 1_000_000L
            val wait = next - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
        }
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val FRAME_BYTES = 4
        const val CHUNK_MS = 20
        const val CHUNK_BYTES = SAMPLE_RATE * FRAME_BYTES * CHUNK_MS / 1000

        /** 44-byte WAV header with maximal data length, for an endless live stream. */
        fun wavHeader(): ByteArray {
            val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(-1) // 0xFFFFFFFF
            h.put("WAVE".toByteArray()).put("fmt ".toByteArray())
            h.putInt(16).putShort(1).putShort(CHANNELS.toShort())
            h.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * FRAME_BYTES)
            h.putShort(FRAME_BYTES.toShort()).putShort(16)
            h.put("data".toByteArray()).putInt(-1)
            return h.array()
        }
    }
}
