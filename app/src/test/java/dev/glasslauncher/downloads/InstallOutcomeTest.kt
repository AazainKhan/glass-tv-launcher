package dev.glasslauncher.downloads

import android.content.Intent
import dev.glasslauncher.system.InstallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallOutcomeTest {
    @Test fun successRemovesTheDownloadUnlessKept() {
        val gone = installOutcome(InstallResult.Success, "Kodi", keepApk = false)
        assertEquals("Installed Kodi", gone.notice)
        assertTrue(gone.removeDownload)
        assertFalse(installOutcome(InstallResult.Success, "Kodi", keepApk = true).removeDownload)
    }

    @Test fun needsUserActionKeepsTheFileAndOpensSettings() {
        val o = installOutcome(InstallResult.NeedsUserAction(Intent()), "Kodi", keepApk = false)
        assertFalse(o.removeDownload)
        assertTrue(o.openSettings)
        assertEquals("Allow VITRE to install apps, then press Select again", o.notice)
    }

    @Test fun failureShowsTheReasonAndKeepsTheFile() {
        val o = installOutcome(InstallResult.Failed("Not enough storage"), "Kodi", keepApk = false)
        assertEquals("Not enough storage", o.notice)
        assertFalse(o.removeDownload)
        assertFalse(o.openSettings)
        assertEquals("Install failed", installOutcome(InstallResult.Failed(" "), "Kodi", false).notice)
    }
}
