package dev.glasslauncher.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.glasslauncher.R
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import dev.glasslauncher.addons.AddonHost
import dev.glasslauncher.ui.Shapes
import kotlinx.coroutines.launch

/**
 * A real add-on's tile (`addon:<pkg>/<tile>`), drawn in Control Center's own material. The add-on supplies label,
 * glyph key and state; Select and long-press go to it. A muted or not-yet-read add-on shows as such.
 */
@Composable
internal fun AddonTile(
    id: String, size: CcSize, w: Dp, h: Dp, modifier: Modifier, wiggle: Boolean,
    tap: (() -> Unit) -> () -> Unit, playOr: (() -> Unit) -> () -> Unit, onLabel: (String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shown = AddonHost.shown[id]
    val s = shown?.state
    val label = s?.label ?: id.substringAfter('/')
    val live = shown != null && s != null && !shown.muted && s.enabled
    val on = live && s?.on == true
    val value = when {
        shown == null -> "Inactive"
        shown.muted -> "Paused"
        else -> s?.value ?: if (on) "On" else "Off"
    }
    val icon = AddonHost.glyph(s?.icon ?: "")
    // A tile's only action is Select; hold-Select is Control Center's edit mode.
    val go = tap { if (live) scope.launch { AddonHost.tap(context, id) } }
    if (size == CcSize.S1x1) Box {
        Round(icon, "$label, $value, add-on", on = on, onLabel = onLabel, modifier = modifier, onPlay = playOr { go() }, wiggle = wiggle, onClick = go)
        FromAddonMark(Color.White, Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 2.dp))
    } else CcTile(label, value, Shapes.pill, w, h, onClick = go, modifier = modifier, onPlay = playOr { go() }, wiggle = wiggle, on = on) { fg ->
        Box(Modifier.fillMaxSize()) {
            PillContent(icon, label, value, fg, on = on, accent = CcShowcase.Green)
            FromAddonMark(fg, Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 12.dp))
        }
    }
}

/** A small puzzle piece: this tile is drawn from an add-on's data, not one of Glass's own controls. */
@Composable
private fun FromAddonMark(color: Color, modifier: Modifier) {
    Image(painterResource(R.drawable.ic_widgets), null, colorFilter = ColorFilter.tint(color), alpha = 0.6f, modifier = modifier.size(11.dp))
}
