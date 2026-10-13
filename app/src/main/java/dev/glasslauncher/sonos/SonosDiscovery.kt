package dev.glasslauncher.sonos

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class SonosSpeaker(val host: String, val roomName: String, val modelName: String)

/** SSDP discovery of Sonos ZonePlayers. Needs CHANGE_WIFI_MULTICAST_STATE. */
class SonosDiscovery(
    private val context: Context,
    private val http: LanHttp = LanHttp(),
) {
    /** Searches for [timeoutMs] and returns the speakers found, deduplicated by host. */
    suspend fun discover(timeoutMs: Int = 3000): List<SonosSpeaker> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("glass-sonos")?.apply { setReferenceCounted(false); acquire() }
        try {
            val locations = search(timeoutMs)
            val seen = HashSet<String>()
            locations.mapNotNull { loc ->
                val host = hostOf(loc) ?: return@mapNotNull null
                if (!seen.add(host)) return@mapNotNull null
                describe(host, loc)
            }
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }

    private fun search(timeoutMs: Int): List<String> {
        val found = ArrayList<String>()
        DatagramSocket().use { s ->
            s.soTimeout = 500
            val req = buildSearch().toByteArray(Charsets.US_ASCII)
            val group = InetAddress.getByName(SSDP_ADDR)
            repeat(2) { s.send(DatagramPacket(req, req.size, group, SSDP_PORT)) }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                parseResponse(String(p.data, 0, p.length, Charsets.US_ASCII))?.let { found += it }
            }
        }
        return found
    }

    private fun describe(host: String, location: String): SonosSpeaker? = try {
        val r = http.request("GET", host, 1400, "/xml/device_description.xml")
        if (r.code in 200..299) parseDescription(host, r.body) else null
    } catch (e: Exception) { warn("describe", host, e); null }

    companion object {
        const val SSDP_ADDR = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val ST = "urn:schemas-upnp-org:device:ZonePlayer:1"

        fun buildSearch(): String =
            "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_ADDR:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $ST\r\n\r\n"

        /** Returns the LOCATION of a 200 response whose ST matches ZonePlayer, else null. */
        fun parseResponse(text: String): String? {
            val lines = text.split("\r\n", "\n")
            if (lines.isEmpty() || !lines[0].startsWith("HTTP/1.1 200")) return null
            val h = HashMap<String, String>()
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0) h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            if (h["st"] != ST) return null
            return h["location"]?.takeIf { it.startsWith("http://") }
        }

        fun hostOf(location: String): String? =
            Regex("^http://([^:/]+)").find(location)?.groupValues?.get(1)

        fun parseDescription(host: String, xml: String): SonosSpeaker {
            val doc = SafeXml.parse(xml)
            return SonosSpeaker(
                host,
                SafeXml.text(doc, "roomName")?.trim().orEmpty(),
                SafeXml.text(doc, "modelName")?.trim().orEmpty(),
            )
        }
    }
}
