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

/** The text lines of the card, kept apart from Compose so they can be tested. */
internal fun installCardLines(info: ApkInfo): Triple<String, String, String?> = Triple(
    "Install ${info.label}?",
    "Package ${info.pkg}, ${ApkInfoReader.formatSize(info.sizeBytes)}" + info.versionName.takeIf { it.isNotEmpty() }?.let { ", version $it" }.orEmpty(),
    if (info.signerChanged) "Signed by a different key than the installed app (${info.signerShort}). Only continue if you trust this file." else null,
)

/**
 * Confirm before every install. When the signer changed, the warning is red, the button is destructive and Cancel
 * has focus first (as in the Restart confirm); otherwise Install has focus. Back cancels via [onCancel].
 */
@Composable
fun InstallConfirmCard(info: ApkInfo, width: Dp, onCancel: () -> Unit, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    val (title, body, warning) = installCardLines(info)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val destructive = info.signerChanged
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.width(width).ccSurface(RoundedCornerShape(26.dp), null).padding(horizontal = 14.dp, vertical = 8.dp).testTag("install-confirm"),
    ) {
        BasicText(title, style = Type.heading.copy(color = Color.White))
        BasicText(body, style = Type.caption.copy(color = Color.White.copy(alpha = 0.8f)))
        if (warning != null) BasicText(warning, style = Type.caption.copy(color = LocalPalette.current.danger), modifier = Modifier.testTag("install-warning"))
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
