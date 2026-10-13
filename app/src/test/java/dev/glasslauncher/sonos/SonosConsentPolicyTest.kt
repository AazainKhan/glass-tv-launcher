package dev.glasslauncher.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P135: the plain APK asks RECORD_AUDIO and the projection dialog every session. Glass Pro (su) and Glass OS skip the
 * dialog with a RECORD_AUDIO grant plus `appops PROJECT_MEDIA allow`. That pre-grant may live only in the two Pro
 * paths below; any other script, module, default-permissions file or source that grants either one fails.
 */
class SonosConsentPolicyTest {
    private val grants = listOf("pm grant", "appops", "grant-runtime", "GRANT_RUNTIME_PERMISSIONS", "grantRuntimePermission", "default-permissions", "set-permission-flags")

    private val allowed = setOf("app/src/main/java/dev/glasslauncher/sonos/ProjectionPregrant.kt", "app/src/main/assets/glass-system-service.sh")

    @Test fun commandBuilderValidatesThePackageName() {
        assertEquals(listOf("appops set dev.glasslauncher PROJECT_MEDIA allow", "pm grant dev.glasslauncher android.permission.RECORD_AUDIO"), ProjectionPregrant.commands("dev.glasslauncher"))
        for (bad in listOf("", "dev", "dev.glass; reboot", "dev.glass launcher", "dev.glass\n id", "\$(id).x", "dev..x", "1dev.x", "dev.x&&id", "a.b|c"))
            assertNull(bad, ProjectionPregrant.commands(bad))
        assertNull(ProjectionPregrant.script("x; reboot"))
        assertTrue(ProjectionPregrant.script("dev.glasslauncher")!!.lines().last().endsWith("RECORD_AUDIO"))
    }

    @Test fun glassSystemModuleGrantsBothEveryBootAfterBootCompleted() {
        val svc = File("src/main/assets/glass-system-service.sh").readText()
        val boot = svc.indexOf("sys.boot_completed")
        val op = svc.indexOf("appops set dev.glasslauncher PROJECT_MEDIA allow")
        val mic = svc.indexOf("pm grant dev.glasslauncher android.permission.RECORD_AUDIO")
        assertTrue(boot in 0 until op && boot in 0 until mic)
        assertTrue("before the refresh-flag early exit", op < svc.indexOf("[ -f \"\$FLAG\" ] || exit 0") && mic < svc.indexOf("[ -f \"\$FLAG\" ] || exit 0"))
    }

    @Test fun nothingPreGrantsOutsideTheProPaths() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() || File(it, "settings.gradle").exists() }
        val bad = ArrayList<String>()
        for (dir in listOf("scripts", "tools", "app/src/main", "e2e")) {
            File(root, dir).walkTopDown().filter { it.isFile && it.length() < 2_000_000 && !it.path.contains("/build/") && it.extension !in setOf("png", "jpg", "webp", "so", "jar", "apk", "mp4", "ttf", "otf") }.forEach { f ->
                val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
                lines.forEachIndexed { i, l ->
                    // The window around a RECORD_AUDIO mention: a grant on the same or the adjacent lines.
                    if ((l.contains("RECORD_AUDIO") || l.contains("PROJECT_MEDIA")) && f.relativeTo(root).path !in allowed) {
                        val near = lines.subList(maxOf(0, i - 2), minOf(lines.size, i + 3)).joinToString("\n")
                        if (grants.any { near.contains(it) }) bad += "${f.relativeTo(root)}:${i + 1}"
                    }
                }
            }
        }
        assertTrue("RECORD_AUDIO / PROJECT_MEDIA may be pre-granted only in $allowed: $bad", bad.isEmpty())
    }

    @Test fun manifestDeclaresItAsAPlainRuntimePermission() {
        val m = File("src/main/AndroidManifest.xml").readText()
        val line = m.lines().first { it.contains("android.permission.RECORD_AUDIO") }
        assertTrue(!line.contains("maxSdkVersion") && !line.contains("protectionLevel"))
    }

    @Test fun consentRunsInItsOwnTransientTask() {
        val m = File("src/main/AndroidManifest.xml").readText()
        val a = m.substringAfter(".sonos.CaptureConsentActivity").substringBefore("/>")
        assertTrue(a.contains("android:taskAffinity=\"\"") && a.contains("android:excludeFromRecents=\"true\"") && a.contains("Translucent"))
        val f = SonosRoutes.CONSENT_FLAGS
        for (flag in listOf(android.content.Intent.FLAG_ACTIVITY_NEW_TASK, android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK, android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)) assertTrue(f and flag == flag)
        assertTrue(File("src/main/java/dev/glasslauncher/sonos/CaptureConsentActivity.kt").readText().contains("finishAndRemoveTask()"))
    }
}
