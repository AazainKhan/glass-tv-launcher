package dev.glasslauncher.system

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallTest {
    private val aa = listOf("aa")

    @Test fun signerComparison() {
        assertEquals(SignerCheck.NotInstalled, ApkInfoReader.compare(aa, emptyList()))
        assertEquals(SignerCheck.Same, ApkInfoReader.compare(aa, listOf("aa")))
        assertEquals(SignerCheck.Changed, ApkInfoReader.compare(aa, listOf("bb")))
        assertEquals(SignerCheck.Unknown, ApkInfoReader.compare(aa, null))
        assertEquals(SignerCheck.Unknown, ApkInfoReader.compare(emptyList(), listOf("bb")))
    }

    @Test fun rotatedKeySharesALineageCertificate() {
        assertEquals(SignerCheck.Same, ApkInfoReader.compare(listOf("new", "old"), listOf("old")))
    }

    @Test fun modeFollowsWhatIsAvailable() {
        assertEquals(InstallMode.Os, ApkInstall.chooseMode(hasInstallPackages = true, root = true))
        assertEquals(InstallMode.Pro, ApkInstall.chooseMode(hasInstallPackages = false, root = true))
        assertEquals(InstallMode.Apk, ApkInstall.chooseMode(hasInstallPackages = false, root = false))
    }

    @Test fun statusMapping() {
        assertEquals(InstallResult.Success, ApkInstall.fromStatus(PackageInstaller.STATUS_SUCCESS, null))
        assertNull(ApkInstall.fromStatus(PackageInstaller.STATUS_PENDING_USER_ACTION, null))
        assertEquals(InstallResult.Failed("Cancelled"), ApkInstall.fromStatus(PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertEquals(InstallResult.Failed("boom"), ApkInstall.fromStatus(PackageInstaller.STATUS_FAILURE, "boom"))
        assertTrue(ApkInstall.fromStatus(PackageInstaller.STATUS_FAILURE_STORAGE, null) is InstallResult.Failed)
    }

    @Test fun rootMapping() {
        assertEquals(InstallResult.Success, ApkInstall.fromRoot(0, "Success"))
        assertEquals(InstallResult.Failed("[INSTALL_FAILED_VERSION_DOWNGRADE]"), ApkInstall.fromRoot(1, "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"))
        assertNull(ApkInstall.fromRoot(127, "su failed"))
        assertNull(ApkInstall.fromRoot(124, "partial"))
        assertNull(ApkInstall.fromRoot(1, ""))
    }

    @Test fun cardWarnsOnlyOnSignerChange() {
        val info = ApkInfo("com.x", "X", "1.0", 1, 5L shl 20, listOf("abcdef0123456789ff"), SignerCheck.Changed)
        val (title, body, warn) = installCardLines(info)
        assertEquals("Install X?", title)
        assertEquals("Package com.x, 5.0 MB, version 1.0", body)
        assertTrue(warn!!.contains("abcdef0123456789"))
        assertNull(installCardLines(info.copy(check = SignerCheck.Same)).third)
    }
}
