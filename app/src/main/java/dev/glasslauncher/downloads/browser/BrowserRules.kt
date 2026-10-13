package dev.glasslauncher.downloads.browser

import dev.glasslauncher.browserlink.Link
import dev.glasslauncher.browserlink.Msg
import dev.glasslauncher.downloads.DownloadEngine
import java.util.Locale

/** What Glass does with a download the browser asked for. The browser is not trusted: every field is checked again here. */
sealed interface BrowserDownload {
    data class Queue(val url: String, val name: String?) : BrowserDownload
    /** An executable type not yet allowed from this host: show the confirm card first; [key] is what a yes is stored under. */
    data class Ask(val url: String, val name: String?, val host: String, val referrer: String, val key: String) : BrowserDownload
    data class Refuse(val reason: String) : BrowserDownload

    companion object {
        private val EXECUTABLE_EXT = setOf("apk", "xapk", "apks", "apkm", "exe", "msi", "bat", "cmd", "sh", "jar", "dex", "scr", "com", "vbs", "ps1", "dmg", "pkg")
        private val EXECUTABLE_MIME = setOf(
            "application/vnd.android.package-archive", "application/x-msdownload", "application/x-msdos-program",
            "application/x-sh", "application/java-archive", "application/x-apple-diskimage",
        )

        private fun extension(name: String?, url: String): String =
            (name?.takeIf { it.contains('.') } ?: url.substringBefore('?').substringBefore('#').substringAfterLast('/'))
                .substringAfterLast('.', "").lowercase(Locale.ROOT).take(10)

        private fun mime(raw: String) = raw.substringBefore(';').trim().lowercase(Locale.ROOT)

        /** [approved] holds "host|ext" keys the user already allowed (kept for this Glass process). */
        fun decide(d: Msg.Download, approved: Set<String>): BrowserDownload {
            val host = Link.httpsHost(d.url) ?: return Refuse("Downloads need an https link")
            val name = d.suggestedName.takeIf { it.isNotBlank() }?.let { DownloadEngine.safeName(it).take(120) }
            val ext = extension(name, d.url)
            val type = mime(d.mimeType)
            if (ext !in EXECUTABLE_EXT && type !in EXECUTABLE_MIME) return Queue(d.url, name)
            val key = "$host|${ext.ifEmpty { type }}"
            return if (key in approved) Queue(d.url, name) else Ask(d.url, name, host, Link.labelHost(d.referrerHost).orEmpty(), key)
        }
    }
}

/** What the on-screen keyboard shows for a text request: its own wording, plus the page's host. Never page-supplied text. */
data class TextPrompt(val title: String, val hint: String, val initial: String) {
    companion object {
        private val WORDS = mapOf(
            "text" to "Enter text", "password" to "Enter password", "search" to "Search", "email" to "Enter email",
            "number" to "Enter a number", "url" to "Enter a web address", "tel" to "Enter a phone number", "address" to "Web address",
        )

        /** Null for a request with a malformed host label. */
        fun of(r: Msg.TextRequest): TextPrompt? {
            val word = WORDS[r.fieldHint] ?: return null
            if (r.fieldHint == "address") return if (r.hostLabel.isEmpty()) TextPrompt("Browser: $word", "A web address, or words to search for.", "https://") else null
            val host = Link.labelHost(r.hostLabel) ?: return null
            val hint = if (r.isPassword || r.fieldHint == "password") "This is a password field. It is shown as you type." else "Typed on this screen for the page."
            return TextPrompt("$host: $word", hint, "")
        }
    }
}
