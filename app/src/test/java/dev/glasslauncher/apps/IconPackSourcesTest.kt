package dev.glasslauncher.apps

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Get an Icon Pack" offers only what opens on this stick: the Appstore's own activity, Downloader, ES File Explorer. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class IconPackSourcesTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val pm = shadowOf(context.packageManager)

    /** Installs [pkg] with [activity]; [launcher] gives it a MAIN/LAUNCHER entry. */
    private fun install(pkg: String, activity: String, launcher: Boolean, enabled: Boolean = true) {
        val info = ApplicationInfo().apply { packageName = pkg; this.enabled = enabled; flags = ApplicationInfo.FLAG_INSTALLED }
        pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = info })
        pm.addOrUpdateActivity(ActivityInfo().apply { packageName = pkg; name = activity; applicationInfo = info; exported = true })
        if (launcher) pm.addIntentFilterForActivity(ComponentName(pkg, activity), IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    @Test fun nothingIsOfferedWhenNothingIsInstalled() {
        assertTrue(IconPackSources.available(context).isEmpty())
    }

    @Test fun downloaderAndEsFileExplorerAreOfferedWhenInstalled() {
        install("com.esaba.downloader", "com.esaba.downloader.Main", launcher = true)
        assertEquals(listOf("Open Downloader"), IconPackSources.available(context).map { it.label })
        install("com.estrongs.android.pop", "com.estrongs.android.pop.Main", launcher = true)
        assertEquals(listOf("Open Downloader", "Open ES File Explorer"), IconPackSources.available(context).map { it.label })
    }

    @Test fun theAppstoreIsOpenedThroughItsOwnActivityWhichOneResolves() {
        // A debloated stick: no leanback entry, but the grid activity is there.
        install("com.amazon.venezia", "com.amazon.venezia.grid.AppsGridLauncherActivity", launcher = false)
        val store = IconPackSources.available(context).single()
        assertEquals("Open the Amazon Appstore", store.label)
        assertEquals("com.amazon.venezia.grid.AppsGridLauncherActivity", store.intent.component!!.className)
    }

    @Test fun anActivityEarlierInTheListWinsWhenItResolves() {
        install("com.amazon.venezia", "com.amazon.venezia.pdi.AppLaunchActivity", launcher = false)
        pm.addOrUpdateActivity(ActivityInfo().apply {
            packageName = "com.amazon.venezia"; name = "com.amazon.venezia.ade.ADEHomeActivity"
            applicationInfo = ApplicationInfo().apply { packageName = "com.amazon.venezia"; enabled = true }; exported = true
        })
        assertEquals("com.amazon.venezia.ade.ADEHomeActivity", IconPackSources.available(context).single().intent.component!!.className)
    }

    @Test fun aDisabledAppstoreIsNotOffered() {
        install("com.amazon.venezia", "com.amazon.venezia.grid.AppsGridLauncherActivity", launcher = false, enabled = false)
        assertTrue(IconPackSources.available(context).isEmpty())
    }

    @Test fun aSourceThatCannotOpenSaysSoInsteadOfDoingNothing() {
        shadowOf(context).checkActivities(true)
        val missing = IconPackSource("Open Nothing", Intent().setComponent(ComponentName("com.nope", "com.nope.Main")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(!IconPackSources.open(context, missing))
    }

    @Test fun theListingKeepsOnlyApkPathsInOrder() {
        val out = "/sdcard/Download/b.apk\nls: /sdcard/Downloads/*.apk: No such file or directory\n\n/sdcard/Download/a.APK\n/sdcard/Download/notes.txt\n/sdcard/Download/b.apk\n"
        assertEquals(listOf("/sdcard/Download/b.apk", "/sdcard/Download/a.APK"), IconPackSources.parseListing(out, 8))
        assertEquals(listOf("/sdcard/Download/b.apk"), IconPackSources.parseListing(out, 1))
    }

    @Test fun thePathIsQuotedAsOneShellWord() {
        assertEquals("'/sdcard/Download/My Pack.apk'", IconPackSources.shellQuote("/sdcard/Download/My Pack.apk"))
        assertEquals("'/sdcard/Download/it'\\''s.apk'", IconPackSources.shellQuote("/sdcard/Download/it's.apk"))
    }

    @Test fun onlyPlainNamesDirectlyInDownloadsAreEverUsed() {
        val ok = listOf("/sdcard/Download/Pack (2).apk", "/sdcard/Downloads/my-icons_v1.2+beta.apk")
        ok.forEach { assertTrue(it, IconPackSources.validDownloadPath(it)) }
        val hostile = listOf(
            "/sdcard/Download/a.apk;rm -rf /data.apk", "/sdcard/Download/\$(reboot).apk", "/sdcard/Download/`id`.apk",
            "/sdcard/Download/it's.apk", "/sdcard/Download/x.apk\n/data/local/tmp/evil.apk", "/sdcard/Download/../evil.apk",
            "/sdcard/Download/sub/dir.apk", "/data/local/tmp/evil.apk", "/sdcard/Download/.hidden.apk", "/sdcard/Download/a|b.apk",
            "/sdcard/Download/a&b.apk", "/sdcard/Download/x.apk.sh", "/sdcard/Download//x.apk", "/sdcard/Download/..apk",
        )
        hostile.forEach { assertTrue("must be refused: $it", !IconPackSources.validDownloadPath(it)) }
        // And a listing carrying such lines keeps only the plain ones.
        val listing = (hostile + ok).joinToString("\n")
        // (The newline-bearing name splits into two lines; its first line "x.apk" is itself a plain name in Downloads,
        // which at worst names a file that isn't there: it can never name a path outside the folders.)
        val kept = IconPackSources.parseListing(listing, 10)
        assertEquals(setOf("/sdcard/Download/x.apk") + ok, kept.toSet())
        assertTrue(kept.all { IconPackSources.validDownloadPath(it) })
    }

    @Test fun aPathStillQuotesAsOneWordForTheCopy() {
        assertEquals("'/sdcard/Download/x.apk'", IconPackSources.shellQuote("/sdcard/Download/x.apk"))
        assertEquals("'a'\\''b'", IconPackSources.shellQuote("a'b"))
    }

    @Test fun theInstallerIntentReadsTheApkFromThePickedUri() {
        val uri = Uri.parse("content://com.example.documents/document/pack.apk")
        val intent = IconPackSources.installIntent(uri)
        assertEquals(uri, intent.data)
        assertEquals(IconPackSources.APK_MIME, intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    private fun zipWith(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun anApkWhoseManifestDeclaresALauncherThemeIsAnIconPack() {
        val adw = "\u0001\u0000org.adw.launcher.THEMES\u0000".toByteArray(Charsets.UTF_8)
        val nova16 = "com.novalauncher.THEME".toByteArray(Charsets.UTF_16LE)
        val plain = "android.intent.action.MAIN\u0000android.intent.category.LAUNCHER".toByteArray()
        assertTrue(IconPackSources.declaresIconPack(adw))
        assertTrue("UTF-16 pools too", IconPackSources.declaresIconPack(nova16))
        assertTrue("an ordinary app is not one", !IconPackSources.declaresIconPack(plain))
    }

    @Test fun theManifestIsReadFromTheArchiveAndNothingElseCounts() {
        val pack = zipWith("res/a.png" to byteArrayOf(1, 2), "AndroidManifest.xml" to "org.adw.launcher.THEMES".toByteArray())
        val app = zipWith("AndroidManifest.xml" to "android.intent.action.MAIN".toByteArray(), "assets/org.adw.launcher.THEMES" to byteArrayOf(1))
        assertTrue(IconPackSources.declaresIconPack(IconPackSources.manifestOf(pack.inputStream())!!))
        // A file inside the archive that merely has the name does not make an app a pack: only the manifest is read.
        assertTrue(!IconPackSources.declaresIconPack(IconPackSources.manifestOf(app.inputStream())!!))
        assertEquals(null, IconPackSources.manifestOf(byteArrayOf(1, 2, 3).inputStream()))
    }

    @Test fun aHugeOrMissingManifestIsNotRead() {
        val big = zipWith("AndroidManifest.xml" to ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() })
        assertEquals(null, IconPackSources.manifestOf(big.inputStream()))
        val file = java.io.File.createTempFile("pack", ".apk").apply { writeBytes(zipWith("res/x" to byteArrayOf(1))) }
        assertEquals(null, IconPackSources.manifestOf(file))
        file.writeBytes(zipWith("AndroidManifest.xml" to "org.adw.launcher.THEMES".toByteArray()))
        assertTrue(IconPackSources.declaresIconPack(IconPackSources.manifestOf(file)!!))
        file.delete()
    }
}
