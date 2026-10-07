package dev.glasslauncher.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.glass
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalMetrics
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HOME-03: the only persistent chrome is one small glass capsule in the top right (time, plus the
 * weather when set), with a Now Playing pill beside it while media plays. It becomes focusable when
 * nothing else is above the tray, and opens Control Center.
 */
@Composable
fun StatusPill(
    cfg: LauncherConfig,
    idle: IdleState,
    focusable: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    fade: () -> Float = { 1f },
) {
    val palette = LocalPalette.current
    val m = LocalMetrics.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .padding(top = m.chromeInset, end = m.chromeInset)
            .graphicsLayer { alpha = idle.chromeAlpha * fade() },
    ) {
        if (cfg.showNowPlaying) NowPlayingPill()
        FocusTile(
            label = "Status and Control Center",
            onClick = onSelect,
            onLongClick = onSelect,
            shape = Shapes.pill,
            focusedScale = 1.08f,
            shadow = false,
            modifier = Modifier.focusProperties { canFocus = focusable }.testTag("status-pill"),
        ) { focused ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier
                    .then(
                        if (focused) Modifier.background(palette.focusFill, Shapes.pill)
                        else Modifier.glass(LocalBackdrop.current, Shapes.pill, GlassStyle.panel(palette.light)),
                    )
                    .padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            ) {
                val text = if (focused) palette.onFocusFill else palette.primary
                cfg.weather?.let { WeatherLabel(it, text) }
                Text(rememberClock(cfg.clock24h), style = Type.body.copy(fontSize = Type.body.fontSize * 0.86f), color = text, modifier = Modifier.testTag("clock"))
                Box(
                    Modifier
                        .size(22.dp)
                        .background(if (focused) palette.onFocusFill.copy(alpha = 0.12f) else palette.primary.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { GearIcon(text, size = 13.dp) }
            }
        }
    }
}

val TextShadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), 10f)

@Composable
fun rememberClock(h24: Boolean): String {
    val pattern = if (h24) "HH:mm" else "h:mm"
    val text by produceState(format(pattern), pattern) {
        while (true) {
            value = format(pattern)
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50)
        }
    }
    return text
}

private fun format(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

@Composable
fun GearIcon(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val outer = this.size.minDimension / 2
        repeat(8) { i ->
            rotate(i * 45f, c) {
                drawRoundRect(
                    color,
                    topLeft = Offset(c.x - outer * 0.16f, c.y - outer),
                    size = androidx.compose.ui.geometry.Size(outer * 0.32f, outer * 0.42f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(outer * 0.08f),
                )
            }
        }
        drawCircle(color, radius = outer * 0.72f, center = c)
        drawCircle(Color.Black, radius = outer * 0.28f, center = c, blendMode = androidx.compose.ui.graphics.BlendMode.DstOut)
    }
}
