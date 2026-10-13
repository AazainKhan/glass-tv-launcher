package dev.glasslauncher.downloads.browser

import dev.glasslauncher.browserlink.Link
import dev.glasslauncher.browserlink.Msg
import dev.glasslauncher.downloads.DownloadEngine
import dev.glasslauncher.downloads.ExecutableTypes

/** What Glass does with a download the browser asked for. The browser is not trusted: every field is checked again here. */
sealed interface BrowserDownload {
    /** [execOk]: the engine re-checks the type after its probe (the server may send something else than the browser saw), allowing only this. */
    data class Queue(val url: String, val name: String?, val execOk: String = "") : BrowserDownload
    /** An executable type not yet allowed from this host: show the confirm card first; [key] is what a yes is stored under. */
    data class Ask(val url: String, val name: String?, val host: String, val referrer: String, val key: String, val allow: String) : BrowserDownload
    data class Refuse(val reason: String) : BrowserDownload

    companion object {
        private fun extension(name: String?, url: String): String =
            ExecutableTypes.extension(name?.takeIf { it.contains('.') } ?: url.substringBefore('?').substringBefore('#').substringAfterLast('/'))

        /** [approved] holds "host|ext" keys the user already allowed (kept for this Glass process). */
        fun decide(d: Msg.Download, approved: Set<String>): BrowserDownload {
            val host = Link.httpsHost(d.url) ?: return Refuse("Downloads need an https link")
            val name = d.suggestedName.takeIf { it.isNotBlank() }?.let { DownloadEngine.safeName(it).take(120) }
            val ext = extension(name, d.url)
            val type = ExecutableTypes.mime(d.mimeType)
            if (!ExecutableTypes.isExecutable("x.$ext", type)) return Queue(d.url, name)
            val allow = ext.ifEmpty { type }
            val key = "$host|$allow"
            // An approval covers exactly this extension on this host; the engine still stops any other executable type.
            return if (key in approved) Queue(d.url, name, execOk = allow) else Ask(d.url, name, host, Link.labelHost(d.referrerHost).orEmpty(), key, allow)
        }
    }
}

/** What the on-screen keyboard shows for a text request: its own wording, plus the page's host. Never page-supplied text. */
data class TextPrompt(val title: String, val hint: String, val initial: String, val password: Boolean, val phoneOk: Boolean = true) {
    companion object {
        private val WORDS = mapOf(
            "text" to "Enter text", "password" to "Enter password", "search" to "Search", "email" to "Enter email",
            "number" to "Enter a number", "url" to "Enter a web address", "tel" to "Enter a phone number", "address" to "Web address",
        )

        /** A host for a title: long ones are shortened in the middle; one that is no plain host name (IPv6, underscores) is "this site". */
        fun siteLabel(raw: String): String {
            val h = Link.labelHost(raw) ?: return "this site"
            return if (h.length > 40) h.take(20) + "..." + h.takeLast(17) else h
        }

        /** Null for a request that names an unknown kind, or an address-bar request that names a page host. */
        fun of(r: Msg.TextRequest): TextPrompt? {
            val word = WORDS[r.fieldHint] ?: return null
            if (r.fieldHint == "address") return if (r.hostLabel.isEmpty()) TextPrompt("Browser: $word", "A web address, or words to search for.", "https://", false) else null
            val password = r.isPassword || r.fieldHint == "password"
            val hint = if (password) "This is a password field." else "Typed on this screen for the page."
            // The phone form is plain http on the LAN: not for secrets or personal details.
            return TextPrompt("${siteLabel(r.hostLabel)}: $word", hint, "", password, phoneOk = !password && r.fieldHint != "email" && r.fieldHint != "tel")
        }
    }
}

/** Allows one keyboard request at a time, per request id, and lets a stale one lapse. */
class TextGate(private val ttlMs: Long) {
    private var id: Long? = null
    private var at = 0L

    fun tryBegin(newId: Long, now: Long): Boolean {
        if (id != null && now - at < ttlMs) return false
        id = newId; at = now
        return true
    }

    /** The keyboard closed (done, cancelled or the browser closed): only that request's own end frees the gate. */
    fun end(endedId: Long) { if (id == endedId) id = null }
    fun clear() { id = null }
}
