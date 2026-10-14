package dev.glasslauncher.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.glasslauncher.ui.Shapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An installed app as a Control Center control (P152, Glass's own: neither tvOS nor iOS has one). The control id is `app:<package>`;
 * it is a 1x1 (the app's icon) or a 2x1 (icon and name), and Select launches the app.
 */
internal object CcApps {
    const val PREFIX = "app:"
    fun id(pkg: String) = PREFIX + pkg
    fun isApp(id: String) = id.startsWith(PREFIX)
    fun pkg(id: String) = id.removePrefix(PREFIX)

    /** The app's launch intent: the TV one when it has one, else the phone one; null when it is not installed or has no launcher entry. */
    fun launchIntent(pm: PackageManager, pkg: String): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installed(context: Context, id: String): Boolean = isApp(id) && launchIntent(context.packageManager, pkg(id)) != null

    /** The app's icon, drawn off the main thread; null if it cannot be read. */
    suspend fun icon(pm: PackageManager, pkg: String): ImageBitmap? =
        withContext(Dispatchers.IO) { runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }

    fun label(pm: PackageManager, pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
}

@Composable
internal fun AppShortcutTile(
    id: String, size: CcSize, w: Dp, h: Dp, modifier: Modifier, wiggle: Boolean,
    tap: (() -> Unit) -> () -> Unit, playOr: (() -> Unit) -> () -> Unit, onLabel: (String?) -> Unit,
    closeAll: () -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val pkg = CcApps.pkg(id)
    val label = remember(pkg) { CcApps.label(pm, pkg) }
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = CcApps.icon(pm, pkg)
    }
    val sz = LocalCcSizes.current
    val go = tap {
        CcApps.launchIntent(pm, pkg)?.let { closeAll(); runCatching { context.startActivity(it) } }
    }
    if (size == CcSize.S1x1) {
        CcTile(label, null, CircleShape, sz.round, sz.round, onClick = go, modifier = modifier, onPlay = playOr { go() }, wiggle = wiggle, on = false,
            onFocusChange = { f -> onLabel(if (f) label else null) }) {
            icon?.let { Image(it, label, Modifier.size(sz.roundGlyph)) }
        }
    } else {
        CcTile(label, null, Shapes.pill, w, h, onClick = go, modifier = modifier, onPlay = playOr { go() }, wiggle = wiggle, on = false) { fg ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.width(sz.leadColumn).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    icon?.let { Image(it, null, Modifier.size(sz.pillGlyph)) }
                }
                Column(Modifier.weight(1f).padding(end = 1.dp)) { PillText(label, CcPillTitle, fg, lines = 2) }
            }
        }
    }
}
