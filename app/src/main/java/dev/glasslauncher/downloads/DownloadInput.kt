package dev.glasslauncher.downloads

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What the URL entry's text turned into. */
sealed interface Entry {
    data class Url(val url: String) : Entry
    /** http:// is refused: the app's network policy blocks cleartext, so the download could only fail later. */
    data object NeedsHttps : Entry
    data class Code(val code: String) : Entry
    data object Invalid : Entry
}

object DownloadInput {
    const val HISTORY_MAX = 20
    const val NEEDS_HTTPS = "That link needs https. Glass doesn't download over plain http."

    /** A bare host gets https://; a word or number with no dot or slash is a code. */
    fun parse(text: String): Entry {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return Entry.Invalid
        val scheme = SCHEME.find(t)?.groupValues?.get(1)?.lowercase()
        return when {
            scheme == "http" -> Entry.NeedsHttps
            scheme != null && scheme != "https" -> Entry.Invalid
            scheme == null && '.' !in t && '/' !in t && ':' !in t -> Entry.Code(t)
            else -> {
                val url = if (scheme == null) "https://$t" else "https" + t.substring(5)
                if (url.toHttpUrlOrNull() != null) Entry.Url(url) else Entry.Invalid
            }
        }
    }

    /** [url] is already https; this checks a code's or a saved shortcut's target the same way. */
    fun httpsUrl(url: String): String? = (parse(url) as? Entry.Url)?.url

    fun host(url: String): String? = url.toHttpUrlOrNull()?.host

    /** The clipboard text when it is a link Glass can download, else null. */
    fun linkOrNull(text: String?): String? = text?.trim()?.takeIf { parse(it) is Entry.Url }

    /** What history may keep of a typed link: no query, fragment or sign-in part, which often carry tokens. */
    fun forHistory(text: String): String =
        text.trim().replace(USERINFO, "$1").substringBefore('?').substringBefore('#')

    /** [text] first, any earlier copy of it dropped, at most [HISTORY_MAX] kept. Older entries are cleaned too. */
    fun pushHistory(history: List<String>, text: String): List<String> {
        val t = forHistory(text)
        if (t.isEmpty()) return history
        return (listOf(t) + history.map(::forHistory).filter { it != t && it.isNotEmpty() }).take(HISTORY_MAX)
    }

    /** Clear History: the typed links and the accepted links go; codes and settings stay. */
    fun cleared(d: dev.glasslauncher.data.DownloadsConfig) = d.copy(history = emptyList(), acceptedUrls = emptySet())

    /** What a yes is stored under: the whole link, so a later code that points elsewhere on the same site asks again. */
    fun acceptKey(url: String): String = url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() ?: url

    /** Whether a download from a code must show its link first: once per link. */
    fun needsConfirm(url: String, accepted: Set<String>): Boolean = acceptKey(url) !in accepted

    fun accept(accepted: Set<String>, url: String): Set<String> = accepted + acceptKey(url)

    private val USERINFO = Regex("^([A-Za-z][A-Za-z0-9+.-]*://)[^/?#@]*@")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
}

/** What pressing Done on the URL entry should do. */
sealed interface Plan {
    /** [confirmHost]: the host to show on a confirm card first (a code's link the user hasn't accepted yet). */
    data class Start(val url: String, val name: String?, val sha256: String?, val code: String?, val confirmHost: String?) : Plan
    data class Refuse(val message: String) : Plan
}

object DownloadPlanner {
    /** Turns the typed [text] into a plan. The public index is only loaded for a code the user's own list lacks. */
    suspend fun plan(text: String, mine: List<dev.glasslauncher.data.MyCode>, accepted: Set<String>, index: suspend () -> List<CodeEntry>): Plan =
        when (val e = DownloadInput.parse(text)) {
            Entry.Invalid -> Plan.Refuse("That isn't a link or a code.")
            Entry.NeedsHttps -> Plan.Refuse(DownloadInput.NEEDS_HTTPS)
            is Entry.Url -> Plan.Start(e.url, null, null, null, null)
            is Entry.Code -> {
                val hit = Codes.resolve(e.code, mine, emptyList()) ?: Codes.resolve(e.code, mine, index())
                val url = hit?.let { DownloadInput.httpsUrl(it.url) }
                when {
                    hit == null -> Plan.Refuse("No download is saved under “${e.code}”.")
                    url == null -> Plan.Refuse(DownloadInput.NEEDS_HTTPS)
                    else -> Plan.Start(url, hit.name, hit.sha256, e.code, DownloadInput.host(url)?.takeIf { DownloadInput.needsConfirm(url, accepted) })
                }
            }
        }
}
