package dev.glasslauncher.downloads

import dev.glasslauncher.system.InstallResult

/** What the Downloads page does after an install: the notice, and whether the downloaded file and its row go. */
internal data class InstallOutcome(val notice: String, val removeDownload: Boolean, val openSettings: Boolean = false)

internal fun installOutcome(result: InstallResult, label: String, keepApk: Boolean) = when (result) {
    InstallResult.Success -> InstallOutcome("Installed $label", removeDownload = !keepApk)
    is InstallResult.NeedsUserAction -> InstallOutcome("Allow VITRE to install apps, then press Select again", removeDownload = false, openSettings = true)
    is InstallResult.Failed -> InstallOutcome(result.reason.ifBlank { "Install failed" }, removeDownload = false)
}
