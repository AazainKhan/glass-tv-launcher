package dev.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** P271: the notices shipped inside the APK (assets/licenses) are the repo's own LICENSE, THIRD_PARTY.md and Inter's OFL, byte for byte. */
class LicenseAssetsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "THIRD_PARTY.md").exists() }

    private fun same(repo: String, asset: String) =
        assertEquals("$asset differs from $repo: copy it again", File(root, repo).readText(), File(root, "app/src/main/assets/licenses/$asset").readText())

    @Test fun theShippedNoticesAreTheRepos() {
        same("LICENSE", "LICENSE.txt")
        same("THIRD_PARTY.md", "THIRD_PARTY.md")
        same("licenses/Inter-OFL.txt", "Inter-OFL.txt")
    }
}
