package dev.glasslauncher.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.glasslauncher.R
import dev.glasslauncher.apps.InstallState
import dev.glasslauncher.ui.LocalPalette

/** The corner badge of a tile whose app is being installed or updated (P168): a download arrow, with a ring that fills as the install progresses. */
@Composable
fun InstallBadge(state: InstallState, modifier: Modifier = Modifier) {
    val accent = LocalPalette.current.accent
    Box(modifier.size(22.dp).background(Color(0xE61C1C1E), CircleShape), contentAlignment = Alignment.Center) {
        if (state.progress > 0f) Canvas(Modifier.size(22.dp)) {
            val w = 2.dp.toPx()
            drawArc(accent.copy(alpha = 0.3f), 0f, 360f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
            drawArc(accent, -90f, 360f * state.progress, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        androidx.compose.foundation.Image(painterResource(R.drawable.ic_download), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(11.dp))
    }
}

/** What a screen reader says for a tile being installed or updated. */
fun installSpoken(state: InstallState): String =
    if (state.progress > 0f) "updating, ${(state.progress * 100).toInt()} percent" else "updating"
