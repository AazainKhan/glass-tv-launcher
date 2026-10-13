package dev.glasslauncher.sonos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun warn(what: String, host: String?, e: Exception) {
    try { android.util.Log.w("GlassSonos", "$what failed for $host: ${e.javaClass.simpleName}") } catch (_: RuntimeException) {}
}

class SonosException(message: String, val upnpCode: Int? = null) : Exception(message)

data class TransportInfo(val state: String, val status: String)

/**
 * UPnP SOAP control of one Sonos speaker on port 1400.
 * [baseUrl] is `http://host:1400`; tests point it at MockWebServer with a permissive [LanHttp].
 * Goes through [LanHttp], so only private IPv4 hosts on port 1400 are ever called.
 */
class SonosControl(
    private val baseUrl: String,
    private val http: LanHttp = LanHttp(),
) {
    suspend fun setAvTransportUri(uri: String, metadata: String = radioDidl("Glass")) {
        call(AVT, "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>${SafeXml.escape(uri)}</CurrentURI>" +
            "<CurrentURIMetaData>${SafeXml.escape(metadata)}</CurrentURIMetaData>")
    }

    suspend fun play() { call(AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") }
    suspend fun stop() { call(AVT, "Stop", "<InstanceID>0</InstanceID>") }

    suspend fun getTransportInfo(): TransportInfo {
        val d = call(AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        return TransportInfo(
            SafeXml.text(d, "CurrentTransportState") ?: "",
            SafeXml.text(d, "CurrentTransportStatus") ?: "",
        )
    }

    suspend fun setVolume(volume: Int) {
        call(RC, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>")
    }

    suspend fun getVolume(): Int {
        val d = call(RC, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
        return SafeXml.text(d, "CurrentVolume")?.trim()?.toIntOrNull()
            ?: throw SonosException("GetVolume: no CurrentVolume")
    }

    private suspend fun call(svc: Service, action: String, args: String) = withContext(Dispatchers.IO) {
        val u = java.net.URI(baseUrl)
        val r = try {
            http.request(
                "POST", u.host, if (u.port > 0) u.port else 80, "${u.path.orEmpty()}/MediaRenderer/${svc.path}/Control",
                mapOf("SOAPACTION" to "\"${svc.urn}#$action\"", "Content-Type" to "text/xml; charset=\"utf-8\""),
                envelope(svc, action, args).toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            warn("SOAP $action", u.host, e); throw e
        }
        parseResponse(r.code, r.body)
    }

    class Service(val path: String, val urn: String)

    companion object {
        val AVT = Service("AVTransport", "urn:schemas-upnp-org:service:AVTransport:1")
        val RC = Service("RenderingControl", "urn:schemas-upnp-org:service:RenderingControl:1")

        fun envelope(svc: Service, action: String, args: String): String =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                "<u:$action xmlns:u=\"${svc.urn}\">$args</u:$action></s:Body></s:Envelope>"

        /** Minimal DIDL-Lite for a live radio-style stream. */
        fun radioDidl(title: String): String =
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"-1\" parentID=\"-1\" restricted=\"true\"><dc:title>${SafeXml.escape(title)}</dc:title>" +
                "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class></item></DIDL-Lite>"

        /** Sonos plays live streams via the x-rincon-mp3radio scheme in place of http. */
        fun radioUri(httpUrl: String): String = "x-rincon-mp3radio://" + httpUrl.removePrefix("http://")

        /** Returns the response Body document or throws [SonosException] on HTTP error or SOAP fault. */
        fun parseResponse(httpCode: Int, body: String): org.w3c.dom.Document {
            val doc = try { SafeXml.parse(body) } catch (e: SonosXmlException) {
                throw SonosException("HTTP $httpCode: ${e.message}")
            }
            if (doc.getElementsByTagNameNS("*", "Fault").length > 0) {
                val code = SafeXml.text(doc, "errorCode")?.trim()?.toIntOrNull()
                val desc = SafeXml.text(doc, "errorDescription")?.trim() ?: SafeXml.text(doc, "faultstring")?.trim() ?: "fault"
                throw SonosException("SOAP fault: $desc ($code)", code)
            }
            if (httpCode !in 200..299) throw SonosException("HTTP $httpCode")
            return doc
        }
    }
}
