package dev.glasslauncher.sonos

import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** Thrown for malformed, oversized or hostile XML from a speaker. */
class SonosXmlException(message: String) : Exception(message)

/** Hardened XML parsing for untrusted LAN responses: 256 KB cap, no DOCTYPE (no entities, no XXE). */
object SafeXml {
    const val MAX_BYTES = 256 * 1024

    fun parse(text: String): Document {
        if (text.length > MAX_BYTES) throw SonosXmlException("XML body over $MAX_BYTES bytes")
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) {
            throw SonosXmlException("DOCTYPE rejected")
        }
        try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            f.isExpandEntityReferences = false
            return f.newDocumentBuilder().parse(text.byteInputStream(Charsets.UTF_8))
        } catch (e: Exception) {
            throw SonosXmlException("bad XML: ${e.message}")
        }
    }

    /** First descendant text by local name, or null. */
    fun text(doc: Document, localName: String): String? {
        val all = doc.getElementsByTagNameNS("*", localName)
        if (all.length == 0) return null
        return (all.item(0) as Element).textContent
    }

    fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
