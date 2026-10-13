package dev.glasslauncher.browserlink

import java.net.URI
import java.util.Locale

/**
 * The wire between Glass and the Glass Browser (a separate, unprivileged APK). This file is identical in both modules
 * (app/.../browserlink/Link.kt and browser/.../browserlink/Link.kt); each side has a test that pins the same constants.
 * Messages are plain strings in a Bundle; nothing else crosses, and each side re-validates what it receives.
 */
object Link {
    const val PERMISSION = "dev.glasslauncher.permission.BROWSER"
    const val GLASS_PACKAGE = "dev.glasslauncher"
    const val GLASS_ACTIVITY = "dev.glasslauncher.MainActivity"
    const val BROWSER_PACKAGE = "com.glasslauncher.browser"
    const val BROWSER_ACTIVITY = "com.glasslauncher.browser.BrowserActivity"
    /** Brings Glass forward (no payload: MainActivity is exported, so an extra here could be forged). */
    const val ACTION_SHOW_GLASS = "dev.glasslauncher.action.BROWSER"
    const val EXTRA_URL = "url"
    /** The Messenger Glass listens on, handed to the browser in the launch intent. */
    const val EXTRA_LINK = "link"

    const val MAX_URL = 2048
    const val MAX_NAME = 255
    const val MAX_TEXT = 4096

    private val HOST = Regex("^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$")

    /** The lower-case host of an https [url] (length-capped, no spaces or control characters), else null. */
    fun httpsHost(url: String?): String? {
        if (url == null || url.length > MAX_URL || url.any { it <= ' ' }) return null
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        if (!"https".equals(u.scheme, ignoreCase = true)) return null
        return u.host?.lowercase(Locale.ROOT)?.takeIf { HOST.matches(it) }
    }

    /** [s] as a bare host name for a label (no port, path or spaces), else null. */
    fun labelHost(s: String): String? = s.lowercase(Locale.ROOT).takeIf { HOST.matches(it) }

    object What {
        const val DOWNLOAD = 1
        const val TEXT_REQUEST = 2
        const val CLOSED = 3
        const val TEXT_RESULT = 10
        const val SHOW_GLASS = 11
    }

    /** Field kinds the browser may name; Glass maps each to its own wording, never page text. */
    val KINDS = setOf("text", "password", "search", "email", "number", "url", "tel", "address")
}

/** One message in either direction. [fields] is what goes in the Bundle; [decode] is its inverse and rejects bad input. */
sealed interface Msg {
    val what: Int
    fun fields(): Map<String, String> = emptyMap()

    /** browser to Glass */
    data class Download(val url: String, val suggestedName: String, val mimeType: String, val referrerHost: String) : Msg {
        override val what get() = Link.What.DOWNLOAD
        override fun fields() = mapOf("url" to url, "name" to suggestedName, "mime" to mimeType, "referrer" to referrerHost)
    }

    /** browser to Glass: [hostLabel] is the page's host ("" for the browser's own address bar), [fieldHint] one of [Link.KINDS]. */
    data class TextRequest(val id: Long, val hostLabel: String, val fieldHint: String, val isPassword: Boolean) : Msg {
        override val what get() = Link.What.TEXT_REQUEST
        override fun fields() = mapOf("id" to id.toString(), "host" to hostLabel, "hint" to fieldHint, "password" to isPassword.toString())
    }

    data object Closed : Msg { override val what get() = Link.What.CLOSED }

    /** Glass to browser */
    data class TextResult(val id: Long, val text: String) : Msg {
        override val what get() = Link.What.TEXT_RESULT
        override fun fields() = mapOf("id" to id.toString(), "text" to text)
    }

    /** Glass to browser: "bring Glass forward, it has something to show" (a download to confirm, a keyboard). */
    data object ShowGlass : Msg { override val what get() = Link.What.SHOW_GLASS }

    companion object {
        fun decode(what: Int, get: (String) -> String?): Msg? {
            fun s(k: String, max: Int): String? = get(k)?.takeIf { it.length <= max }
            return when (what) {
                Link.What.DOWNLOAD -> Download(
                    s("url", Link.MAX_URL) ?: return null, s("name", Link.MAX_NAME) ?: return null,
                    s("mime", 127) ?: return null, s("referrer", 253) ?: return null,
                )
                Link.What.TEXT_REQUEST -> TextRequest(
                    s("id", 20)?.toLongOrNull() ?: return null, s("host", 253) ?: return null,
                    s("hint", 16)?.takeIf { it in Link.KINDS } ?: return null,
                    when (get("password")) { "true" -> true; "false" -> false; else -> return null },
                )
                Link.What.CLOSED -> Closed
                Link.What.TEXT_RESULT -> TextResult(s("id", 20)?.toLongOrNull() ?: return null, s("text", Link.MAX_TEXT) ?: return null)
                Link.What.SHOW_GLASS -> ShowGlass
                else -> null
            }
        }
    }
}
