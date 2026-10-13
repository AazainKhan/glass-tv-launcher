package dev.glasslauncher.downloads

import java.util.Locale

/** Files that run code. Used by the browser hand-off and by the engine's re-check after its probe. */
object ExecutableTypes {
    private val EXT = setOf("apk", "xapk", "apks", "apkm", "exe", "msi", "bat", "cmd", "sh", "jar", "dex", "scr", "com", "vbs", "ps1", "dmg", "pkg")
    private val MIME = setOf(
        "application/vnd.android.package-archive", "application/x-msdownload", "application/x-msdos-program",
        "application/x-sh", "application/java-archive", "application/x-apple-diskimage",
    )

    private val MIME_FOR = mapOf(
        "apk" to setOf("application/vnd.android.package-archive"),
        "sh" to setOf("application/x-sh"),
        "jar" to setOf("application/java-archive"),
        "exe" to setOf("application/x-msdownload", "application/x-msdos-program"),
        "msi" to setOf("application/x-msdownload"),
        "dmg" to setOf("application/x-apple-diskimage"),
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT).take(10)

    fun mime(raw: String?): String = raw?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()

    /**
     * Whether a file named [names] with content type [mime] runs code beyond what the user allowed. [allowed] is the one
     * extension (or type) approved; "" allows nothing.
     */
    fun offends(names: List<String>, mime: String?, allowed: String): Boolean {
        if (names.any { isExecutable(it, null) && extension(it) != allowed }) return true
        val type = mime(mime)
        if (type !in MIME) return false
        // A type must fit the approved extension too: x.apk served as a shell script is not the apk that was approved.
        return !(type == allowed || names.any { extension(it) == allowed && type in (MIME_FOR[allowed] ?: emptySet()) })
    }

    fun isExecutable(name: String?, mime: String?): Boolean =
        (name != null && extension(name) in EXT) || mime(mime) in MIME
}
