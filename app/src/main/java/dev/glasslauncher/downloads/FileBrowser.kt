package dev.glasslauncher.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** One row of the file browser. */
data class FileEntry(val file: File, val dir: Boolean, val size: Long, val modified: Long) {
    val name: String get() = file.name
}

enum class FileKind { Folder, Apk, Video, Audio, Image, Other }

/** What the file browser can do to files in the downloads folder; plain Java, so JVM tests cover it. */
object FileBrowser {
    /** Bumped after a delete or rename, so open pages list again. */
    val changes = MutableStateFlow(0)

    private val MIME = mapOf(
        "apk" to "application/vnd.android.package-archive",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo", "mov" to "video/quicktime", "ts" to "video/mp2t",
        "mp3" to "audio/mpeg", "flac" to "audio/flac", "m4a" to "audio/mp4", "ogg" to "audio/ogg", "wav" to "audio/x-wav",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "pdf" to "application/pdf", "zip" to "application/zip", "txt" to "text/plain", "json" to "application/json",
    )

    /** The MIME type for a file name by its extension; null when unknown. */
    fun mime(name: String): String? = MIME[name.substringAfterLast('.', "").lowercase(Locale.ROOT)]

    fun kind(entry: FileEntry): FileKind = if (entry.dir) FileKind.Folder else when {
        mime(entry.name) == MIME["apk"] -> FileKind.Apk
        mime(entry.name)?.startsWith("video/") == true -> FileKind.Video
        mime(entry.name)?.startsWith("audio/") == true -> FileKind.Audio
        mime(entry.name)?.startsWith("image/") == true -> FileKind.Image
        else -> FileKind.Other
    }

    private fun isSidecar(name: String) = name.endsWith(".part") || name.endsWith(".part.json")

    /** [dir]'s children, newest first, without the engine's `.part` files or names in [busy] (still downloading). */
    fun list(dir: File, busy: Set<String> = emptySet()): List<FileEntry> =
        dir.listFiles().orEmpty()
            .filter { !isSidecar(it.name) && it.name !in busy && !it.name.startsWith(".") }
            .map { FileEntry(it, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
            .sortedWith(compareByDescending<FileEntry> { it.modified }.thenBy { it.name.lowercase(Locale.ROOT) })

    /** Names of files an unfinished download will write or is writing, which the browser leaves alone. */
    fun busyNames(items: List<DownloadItem>): Set<String> =
        items.filter { it.state != DownloadState.Done }.flatMap { listOfNotNull(it.file, it.name) }.toSet()

    /** Why [newName] can't replace [old] in [dir]; null when it can (or is unchanged). */
    fun renameError(dir: File, old: String, newName: String): String? {
        val n = newName.trim()
        return when {
            n == old -> null
            n.isEmpty() -> "Enter a name."
            n.startsWith(".") -> "A name can't start with a dot; the file would be hidden."
            n == "." || n == ".." || '/' in n || '\\' in n || '\u0000' in n -> "A name can't contain slashes."
            isSidecar(n) -> "Names ending in .part are used by downloads."
            File(dir, n).exists() -> "A file called “$n” is already here."
            else -> null
        }
    }

    /** Renames [file] to [newName] (trimmed) beside it; the error text when that isn't possible. */
    fun rename(file: File, newName: String): String? {
        val n = newName.trim()
        renameError(file.parentFile ?: return "Can't rename that.", file.name, newName)?.let { return it }
        if (n == file.name) return null
        // No REPLACE_EXISTING: a name taken since the check fails instead of overwriting.
        return try {
            java.nio.file.Files.move(file.toPath(), File(file.parentFile, n).toPath())
            null
        } catch (e: java.nio.file.FileAlreadyExistsException) { "A file called “$n” is already here." }
        catch (e: java.io.IOException) { "Couldn't rename that." }
    }

    /** Lower-case hex SHA-256 of [file], read in blocks (it can be gigabytes: call off the main thread). */
    fun sha256(file: File, check: () -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                check()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    enum class Deleted { Ok, NotEmpty, Failed }

    /** Deletes a file or an empty folder, and says why when it can't. */
    fun delete(file: File): Deleted = when {
        file.isDirectory && file.list().orEmpty().isNotEmpty() -> Deleted.NotEmpty
        file.delete() || !file.exists() -> Deleted.Ok
        else -> Deleted.Failed
    }

    /** The hash in two lines of 32, so it fits a small card. */
    fun hashLines(hash: String): String = hash.chunked(32).joinToString("\n")
}
