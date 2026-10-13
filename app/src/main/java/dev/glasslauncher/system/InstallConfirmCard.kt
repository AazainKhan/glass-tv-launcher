package dev.glasslauncher.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.glasslauncher.home.CcTile
import dev.glasslauncher.home.ccSurface
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type

/** Lines of the card, apart from Compose so they can be tested. [alerts] are drawn red. */
internal data class InstallCardLines(val title: String, val body: String, val notes: List<String>, val alerts: List<String>)

internal fun installCardLines(info: ApkInfo): InstallCardLines {
    val ver = info.versionName.takeIf { it.isNotEmpty() }?.let { ", version $it" }.orEmpty()
    val notes = mutableListOf<String>()
    val alerts = mutableListOf<String>()
    when (info.version) {
        VersionChange.NewApp -> notes += "New app: not installed yet."
        VersionChange.Upgrade -> notes += "Update from version code ${info.installedVersionCode}."
        VersionChange.Same -> notes += "Same version as the installed app."
        VersionChange.Downgrade -> alerts += "Older than the installed app (${info.installedVersionCode}). Android will refuse unless you uninstall first."
    }
    info.installedSigner?.let { notes += "Installed app's key: ${it.take(16)}" }
    if (info.signers.isNotEmpty()) notes += "This file's key: ${info.signerShort}"
    when (info.check) {
        SignerCheck.Changed -> alerts += "Signed by a different key than the installed app. Only continue if you trust this file."
        SignerCheck.Unknown -> alerts += "Couldn't verify who signed this file."
        else -> {}
    }
    return InstallCardLines("Install ${info.label}?", "Package ${info.pkg}, ${ApkInfoReader.formatSize(info.sizeBytes)}$ver", notes, alerts)
}

/**
 * Confirm before every install. When the signer changed, the warning is red, the button is destructive and Cancel
 * has focus first (as in the Restart confirm); otherwise Install has focus. Back cancels via [onCancel].
 */
@Composable
fun InstallConfirmCard(info: ApkInfo, width: Dp, onCancel: () -> Unit, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    val lines = installCardLines(info)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val destructive = info.risky
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.width(width).ccSurface(RoundedCornerShape(26.dp), null).padding(horizontal = 14.dp, vertical = 8.dp).testTag("install-confirm"),
    ) {
        BasicText(lines.title, style = Type.heading.copy(color = Color.White))
        BasicText(lines.body, style = Type.caption.copy(color = Color.White.copy(alpha = 0.8f)))
        lines.notes.forEach { BasicText(it, style = Type.caption.copy(color = Color.White.copy(alpha = 0.8f))) }
        lines.alerts.forEach { BasicText(it, style = Type.caption.copy(color = LocalPalette.current.danger), modifier = Modifier.testTag("install-warning")) }
        CcTile("Install", null, Shapes.pill, width - 28.dp, 42.dp, onClick = onConfirm,
            modifier = (if (destructive) Modifier else Modifier.focusRequester(first)).testTag("install-yes")) { fg ->
            BasicText("Install", style = Type.body.copy(color = if (destructive) LocalPalette.current.danger else fg))
        }
        CcTile("Cancel", null, Shapes.pill, width - 28.dp, 42.dp, onClick = onCancel,
            modifier = (if (destructive) Modifier.focusRequester(first) else Modifier).testTag("install-cancel")) { fg ->
            BasicText("Cancel", style = Type.body.copy(color = fg))
        }
    }
}
