package dev.glasslauncher.downloads

import java.util.Locale

/** Files that run code. Used by the browser hand-off and by the engine's re-check after its probe. */
object ExecutableTypes {
    private val EXT = setOf("apk", "xapk", "apks", "apkm", "exe", "msi", "bat", "cmd", "sh", "jar", "dex", "scr", "com", "vbs", "ps1", "dmg", "pkg")
    private val MIME = setOf(
        "application/vnd.android.package-archive", "application/x-msdownload", "application/x-msdos-program",
        "application/x-sh", "application/java-archive", "application/x-apple-diskimage",
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT).take(10)

    fun mime(raw: String?): String = raw?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()

    fun isExecutable(name: String?, mime: String?): Boolean =
        (name != null && extension(name) in EXT) || mime(mime) in MIME
}
