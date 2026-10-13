package dev.glasslauncher.system

import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkInstallTest {
    private fun set(vararg s: String) = SignerSet(s.toSet())

    @Test fun signerComparison() {
        assertEquals(SignerCheck.NotInstalled, ApkInfoReader.compare(set("aa"), null, isInstalled = false))
        assertEquals(SignerCheck.Same, ApkInfoReader.compare(set("aa"), set("aa")))
        assertEquals(SignerCheck.Changed, ApkInfoReader.compare(set("aa"), set("bb")))
        assertEquals(SignerCheck.Unknown, ApkInfoReader.compare(set("aa"), null, isInstalled = true))
        assertEquals(SignerCheck.Unknown, ApkInfoReader.compare(SignerSet(emptySet()), set("bb")))
    }

    @Test fun everySignerMustMatch() {
        assertEquals(SignerCheck.Same, ApkInfoReader.compare(set("a", "b"), set("a", "b")))
        assertEquals(SignerCheck.Changed, ApkInfoReader.compare(set("a", "c"), set("a", "b")))
        assertEquals(SignerCheck.Changed, ApkInfoReader.compare(set("a"), set("a", "b")))
    }

    @Test fun rotatedKeyKeepsTheInstalledOneInItsLineage() {
        assertEquals(SignerCheck.Same, ApkInfoReader.compare(SignerSet(setOf("new"), setOf("old", "new")), set("old")))
        assertEquals(SignerCheck.Changed, ApkInfoReader.compare(set("old"), SignerSet(setOf("new"), setOf("old", "new"))))
    }

    @Test fun versionComparison() {
        assertEquals(VersionChange.NewApp, ApkInfoReader.versionChange(5, null))
        assertEquals(VersionChange.Upgrade, ApkInfoReader.versionChange(6, 5))
        assertEquals(VersionChange.Same, ApkInfoReader.versionChange(5, 5))
        assertEquals(VersionChange.Downgrade, ApkInfoReader.versionChange(4, 5))
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
    }

    @Test fun pendingStatusLeavesTheWaiterOpenUntilTheFinalOne() {
        val done = CompletableDeferred<InstallResult>()
        ApkInstall.waitingForTest[7] = done
        ApkInstall.onStatus(7, PackageInstaller.STATUS_PENDING_USER_ACTION, null)
        assertTrue(!done.isCompleted)
        ApkInstall.onStatus(7, PackageInstaller.STATUS_SUCCESS, null)
        assertEquals(InstallResult.Success, runBlocking { done.await() })
    }

    @Test fun rootMapping() {
        assertEquals(ApkInstall.RootOutcome.Done(InstallResult.Success), ApkInstall.fromRoot(0, "Success"))
        assertEquals(ApkInstall.RootOutcome.Done(InstallResult.Failed("[INSTALL_FAILED_VERSION_DOWNGRADE]")), ApkInstall.fromRoot(1, "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"))
        assertEquals(ApkInstall.RootOutcome.NoSu, ApkInstall.fromRoot(127, "su failed"))
        assertEquals(ApkInstall.RootOutcome.Unknown, ApkInstall.fromRoot(124, "partial"))
        assertEquals(ApkInstall.RootOutcome.Unknown, ApkInstall.fromRoot(0, ""))
    }

    @Test fun timeoutGivesNoResult() {
        assertNull(runBlocking { ApkInstall.awaitResult(CompletableDeferred(), 30) })
    }

    @Test fun cardWarnsOnSignerChangeAndDowngrade() {
        val info = ApkInfo("com.x", "X", "1.0", 4, 5L shl 20, listOf("abcdef0123456789ff"), SignerCheck.Changed, installedVersionCode = 5, installedSigner = "1234567890abcdefaa")
        val l = installCardLines(info)
        assertEquals("Install X?", l.title)
        assertEquals("Package com.x, 5.0 MB, version 1.0", l.body)
        assertEquals(2, l.alerts.size)
        assertTrue(l.notes.any { it.contains("1234567890abcdef") })
        val ok = installCardLines(info.copy(check = SignerCheck.NotInstalled, installedVersionCode = null, installedSigner = null))
        assertTrue(ok.alerts.isEmpty())
        assertTrue(ok.notes.first().startsWith("New app"))
        assertEquals(1, installCardLines(info.copy(check = SignerCheck.Unknown, installedVersionCode = 3)).alerts.size)
    }

    // --- install() orchestration with fakes -------------------------------------------------------------------------

    private class Fake(
        val mode: InstallMode, val canInstall: Boolean = true, val root: Root.Result = Root.Result(0, "Success"),
        var version: Long? = null, val commitResult: InstallResult = InstallResult.Success, val hashes: MutableList<String> = mutableListOf(),
    ) : ApkInstall.Edges {
        val calls = mutableListOf<String>()
        override suspend fun mode() = mode
        override fun canInstall() = canInstall
        override fun unknownSources() = Intent()
        override suspend fun rootInstall(file: File): Root.Result { calls += "root"; return root }
        override fun installedVersion(pkg: String) = version
        override suspend fun commit(file: File, userAction: Boolean): InstallResult { calls += "commit:$userAction"; return commitResult }
        override fun hash(file: File) = if (hashes.isEmpty()) "h" else hashes.removeAt(0)
        override fun log(line: String) {}
    }

    private val staged = StagedApk(File("x.apk"), "h", ApkInfo("com.x", "X", "2", 9, 1, listOf("aa"), SignerCheck.Same, 8, "aa"))
    private fun run(f: Fake) = runBlocking { ApkInstall.install(f, staged) }

    @Test fun apkModeNeedsUnknownSourcesThenUsesTheCard() {
        assertTrue(run(Fake(InstallMode.Apk, canInstall = false)) is InstallResult.NeedsUserAction)
        val f = Fake(InstallMode.Apk)
        assertEquals(InstallResult.Success, run(f))
        assertEquals(listOf("commit:true"), f.calls)
    }

    @Test fun osModeCommitsSilently() {
        val f = Fake(InstallMode.Os)
        run(f)
        assertEquals(listOf("commit:false"), f.calls)
    }

    @Test fun proModeUsesRootAndDoesNotFallBackAfterAFailure() {
        val f = Fake(InstallMode.Pro, root = Root.Result(1, "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"))
        assertTrue(run(f) is InstallResult.Failed)
        assertEquals(listOf("root"), f.calls)
    }

    @Test fun proModeFallsBackToTheCardOnlyWhenThereIsNoSu() {
        val f = Fake(InstallMode.Pro, root = Root.Result(127, "no su"))
        assertEquals(InstallResult.Success, run(f))
        assertEquals(listOf("root", "commit:true"), f.calls)
    }

    @Test fun proTimeoutIsVerifiedNeverReinstalled() {
        val f = Fake(InstallMode.Pro, root = Root.Result(124, ""), version = 9)
        assertEquals(InstallResult.Success, run(f))
        assertEquals(listOf("root"), f.calls)
        val g = Fake(InstallMode.Pro, root = Root.Result(0, ""), version = 8)
        assertTrue(run(g) is InstallResult.Failed)
        assertEquals(listOf("root"), g.calls)
    }

    @Test fun aChangedFileIsRefusedBeforeAndAfterTheModeSwitch() {
        assertEquals(InstallResult.Failed("The file changed after it was checked"), run(Fake(InstallMode.Os, hashes = mutableListOf("other"))))
        val f = Fake(InstallMode.Pro, root = Root.Result(127, ""), hashes = mutableListOf("h", "other"))
        assertTrue(run(f) is InstallResult.Failed)
        assertEquals(listOf("root"), f.calls)
    }
}
