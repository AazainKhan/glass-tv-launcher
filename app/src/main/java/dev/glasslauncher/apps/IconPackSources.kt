package dev.glasslauncher.apps

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.glasslauncher.system.Root
import java.io.File

/** One way to get an icon pack on this device: a label and the intent that opens it. */
data class IconPackSource(val label: String, val intent: Intent)

/**
 * Where an icon pack can come from on a Fire TV stick, found by what is really installed and enabled: Fire OS
 * has no Play Store, the Appstore's deep links bounce through the (often disabled) Amazon launcher, there is no
 * browser and no document picker. So a source is an installed app opened by its own component (the Appstore's
 * activities, Downloader, ES File Explorer), and a pack already downloaded as an .apk is installed straight from
 * the Downloads folder (as root, `pm install`). A source is listed only when it resolves here.
 */
object IconPackSources {
    const val APK_MIME = "application/vnd.android.package-archive"

    const val APPSTORE = "com.amazon.venezia"
    const val DOWNLOADER = "com.esaba.downloader"
    const val ES_FILE_EXPLORER = "com.estrongs.android.pop"

    /** The Appstore's own activities, tried in order: it has no leanback launcher entry on a debloated stick. */
    internal val APPSTORE_ACTIVITIES = listOf(".ade.ADEHomeActivity", ".grid.AppsGridLauncherActivity", ".pdi.AppLaunchActivity")

    private val DOWNLOAD_DIRS = listOf("/sdcard/Download", "/sdcard/Downloads")

    /** The sources this device can open, in the order they are offered. */
    fun available(context: Context): List<IconPackSource> {
        val pm = context.packageManager
        val out = ArrayList<IconPackSource>()
        appstore(pm)?.let { out += IconPackSource("Open the Amazon Appstore", it) }
        launcher(pm, DOWNLOADER)?.let { out += IconPackSource("Open Downloader", it) }
        launcher(pm, ES_FILE_EXPLORER)?.let { out += IconPackSource("Open ES File Explorer", it) }
        return out
    }

    private fun launcher(pm: PackageManager, pkg: String): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun appstore(pm: PackageManager): Intent? {
        if (runCatching { pm.getApplicationInfo(APPSTORE, 0).enabled }.getOrDefault(false).not()) return null
        // By component, as the activity info the system holds for it: present, enabled and exported means it opens.
        return APPSTORE_ACTIVITIES.asSequence()
            .map { ComponentName(APPSTORE, APPSTORE + it) }
            .firstOrNull { cn -> runCatching { pm.getActivityInfo(cn, 0) }.getOrNull()?.let { it.enabled && it.exported } == true }
            ?.let { Intent(Intent.ACTION_MAIN).setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: launcher(pm, APPSTORE)
    }

    /** Opens [source]; false when nothing handled it after all (the caller then tells the user). */
    fun open(context: Context, source: IconPackSource): Boolean =
        try { context.startActivity(source.intent); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    /**
     * The .apk files in the Downloads folder, newest first (at most [limit]). As root when Glass has it (the folder
     * needs storage access that a TV rarely grants), else as far as the app can read.
     */
    suspend fun downloadedApks(limit: Int = 8): List<String> {
        if (Root.available()) {
            // Regular files only (no symlinks), directly inside the folders; the names are checked again below.
            val script = DOWNLOAD_DIRS.joinToString("\n") { dir ->
                "for f in ${shellQuote(dir)}/*.apk; do if [ -f \"\$f\" ] && [ ! -L \"\$f\" ]; then echo \"\$f\"; fi; done"
            }
            return parseListing(Root.run(script).out, limit)
        }
        return DOWNLOAD_DIRS.mapNotNull { File(it).listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) } }
            .flatMap { it.toList() }.sortedByDescending { it.lastModified() }.map { it.path }.filter(::validDownloadPath).take(limit)
    }

    /**
     * Whether [path] is a plain file name directly inside one of the Downloads folders: letters, digits, space and
     * `_ . ( ) + -` only, ending in .apk, no directory part, no `..`. Anything else (a `;`, a `$(`, a quote, a
     * newline, a symlink's odd target path) is never listed, read or installed: names reach a root shell.
     */
    internal fun validDownloadPath(path: String): Boolean {
        val dir = DOWNLOAD_DIRS.firstOrNull { path.startsWith("$it/") } ?: return false
        val name = path.removePrefix("$dir/")
        return name.length in 5..120 && !name.startsWith(".") && ".." !in name && SAFE_NAME.matches(name)
    }

    private val SAFE_NAME = Regex("[A-Za-z0-9_ .()+-]+\\.[Aa][Pp][Kk]")

    /** `ls` output to paths: absolute .apk lines only (an error line, a blank, a directory is not one), in order. */
    internal fun parseListing(out: String, limit: Int): List<String> =
        out.lineSequence().map { it.trim() }.filter(::validDownloadPath).distinct().take(limit).toList()

    /** [path] as one shell word (single-quoted, with embedded quotes closed and escaped). */
    internal fun shellQuote(path: String) = "'" + path.replace("'", "'\\''") + "'"

    /** What an icon pack's manifest declares: the theme intents launchers look for (ADW, Nova, Apex, Go, the picker action). */
    internal val THEME_ACTIONS = listOf(
        "org.adw.launcher.THEMES", "org.adw.launcher.icons.ACTION_PICK_ICON", "com.novalauncher.THEME", "com.anddoes.launcher.THEME",
    )

    /**
     * Whether a compiled AndroidManifest.xml declares one of [THEME_ACTIONS]. The binary XML keeps its strings in a
     * pool (UTF-8 or UTF-16), so the NUL bytes are dropped and the text searched: the action names are plain ASCII.
     */
    internal fun declaresIconPack(manifest: ByteArray): Boolean {
        val text = String(manifest.filter { it.toInt() != 0 }.toByteArray(), Charsets.ISO_8859_1)
        return THEME_ACTIONS.any { it in text }
    }

    /** [input]'s bytes, or null when there are [max] or more (it is not read past that: a manifest is a few KB). */
    private fun readAtMost(input: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() >= max) return null
        }
        return out.toByteArray()
    }

    /** A real manifest is a few KB; anything this big is not one (and is not read into memory). */
    private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024

    /**
     * AndroidManifest.xml of the .apk file [apk], read through its central directory (as the package manager does:
     * a stream reader that walks local headers could be shown a different "first" manifest). Null if absent or huge.
     */
    internal fun manifestOf(apk: File): ByteArray? = runCatching {
        java.util.zip.ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return@use null
            if (entry.size !in 1 until MAX_MANIFEST_BYTES) return@use null
            zip.getInputStream(entry).use { readAtMost(it, MAX_MANIFEST_BYTES) }
        }
    }.getOrNull()

    /** AndroidManifest.xml out of a zip stream (an .apk), or null; stops reading once it has it. */
    internal fun manifestOf(apk: java.io.InputStream): ByteArray? = runCatching {
        java.util.zip.ZipInputStream(apk).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "AndroidManifest.xml") return@use readAtMost(zip, MAX_MANIFEST_BYTES)
                entry = zip.nextEntry
            }
            null
        }
    }.getOrNull()

    /** Whether the .apk at [path] is an icon pack (as root when Glass has it: the Downloads folder needs storage access). */
    suspend fun isIconPack(path: String): Boolean {
        val manifest = if (Root.available()) Root.streamFile(path) { manifestOf(it) } else runCatching { File(path).inputStream().use { manifestOf(it) } }.getOrNull()
        return manifest != null && declaresIconPack(manifest)
    }

    /** The downloaded .apk files that really are icon packs: nothing else is ever offered for install here. */
    suspend fun downloadedIconPacks(limit: Int = 8): List<String> = downloadedApks(limit * 3).filter { isIconPack(it) }.take(limit)

    /**
     * Installs the icon pack at [path] as root. The file is copied once into Glass's cache (the path is a validated
     * name, single-quoted for `cat`), that copy is checked to be an icon pack, and those same bytes are streamed to
     * `pm install -S size -r` on its standard input: no name is part of the install command, and what was checked
     * is what is installed. Returns whether it worked and what the installer said.
     */
    suspend fun install(context: Context, path: String): Pair<Boolean, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!validDownloadPath(path)) return@withContext false to "That file is not in the Downloads folder."
        if (!Root.available()) return@withContext false to "Installing needs root: open the file with Downloader or ES File Explorer instead."
        val copy = File(context.cacheDir, "icon-pack-install.apk")
        try {
            val copied = Root.streamFile(path) { input -> copy.outputStream().use { out -> input.copyTo(out) } }
            if (copied == null || copy.length() == 0L) return@withContext false to "Couldn't read that file (it needs root access to Downloads)."
            val manifest = manifestOf(copy)
            if (manifest == null || !declaresIconPack(manifest)) return@withContext false to "That file is not an icon pack."
            val r = Root.runWithInput("pm install -S ${copy.length()} -r", copy)
            Root.log(context, "Install icon pack ${File(path).name}: ${if (r.ok) "ok" else "failed (${r.code}) ${r.out.take(200)}"}")
            return@withContext (r.ok && r.out.contains("Success")) to r.out.lines().lastOrNull { it.isNotBlank() }.orEmpty()
        } finally {
            copy.delete()
        }
    }

    /** The system installer for an icon pack's .apk the user picked (a content URI the installer may read). */
    fun installIntent(apk: Uri): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(apk, APK_MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Whether the device has a document picker to import from (Fire OS has none). */
    fun canPickFiles(context: Context): Boolean =
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .resolveActivity(context.packageManager) != null
}
