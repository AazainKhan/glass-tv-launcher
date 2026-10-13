package dev.glasslauncher.sonos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RECORD_AUDIO is asked at runtime, every session, in APK, Pro and OS alike. No script, Magisk module, default-
 * permissions file or source may grant it (appops, `pm grant`, runtime-permission grants).
 */
class SonosConsentPolicyTest {
    private val grants = listOf("pm grant", "appops", "grant-runtime", "GRANT_RUNTIME_PERMISSIONS", "grantRuntimePermission", "default-permissions", "set-permission-flags")

    @Test fun nothingPreGrantsRecordAudio() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() || File(it, "settings.gradle").exists() }
        val bad = ArrayList<String>()
        for (dir in listOf("scripts", "tools", "app/src/main", "e2e")) {
            File(root, dir).walkTopDown().filter { it.isFile && it.length() < 2_000_000 && !it.path.contains("/build/") && it.extension !in setOf("png", "jpg", "webp", "so", "jar", "apk", "mp4", "ttf", "otf") }.forEach { f ->
                val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
                lines.forEachIndexed { i, l ->
                    // The window around a RECORD_AUDIO mention: a grant on the same or the adjacent lines.
                    if (l.contains("RECORD_AUDIO")) {
                        val near = lines.subList(maxOf(0, i - 2), minOf(lines.size, i + 3)).joinToString("\n")
                        if (grants.any { near.contains(it) }) bad += "${f.relativeTo(root)}:${i + 1}"
                    }
                }
            }
        }
        assertTrue("RECORD_AUDIO must never be pre-granted: $bad", bad.isEmpty())
    }

    @Test fun manifestDeclaresItAsAPlainRuntimePermission() {
        val m = File("src/main/AndroidManifest.xml").readText()
        val line = m.lines().first { it.contains("android.permission.RECORD_AUDIO") }
        assertTrue(!line.contains("maxSdkVersion") && !line.contains("protectionLevel"))
    }
}
