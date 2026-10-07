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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Type
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusBar(
    cfg: LauncherConfig,
    idle: IdleState,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    fade: () -> Float = { 1f },
) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier
            .padding(top = 26.dp, end = 44.dp)
            .graphicsLayer { alpha = idle.chromeAlpha * fade() },
    ) {
        if (cfg.showNowPlaying) NowPlayingPill()
        cfg.weather?.let { WeatherLabel(it) }
        Text(rememberClock(cfg.clock24h), style = Type.heading.copy(shadow = TextShadow), color = palette.primary, modifier = Modifier.testTag("clock"))
        FocusTile(
            label = "Settings",
            onClick = onSettings,
            shape = CircleShape,
            focusedScale = 1.15f,
            modifier = Modifier.size(38.dp).testTag("settings-button"),
        ) { focused ->
            Box(
                Modifier
                    .size(38.dp)
                    .background(if (focused) palette.focusFill else palette.primary.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { GearIcon(if (focused) palette.onFocusFill else palette.primary) }
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
fun GearIcon(color: Color) {
    Canvas(Modifier.size(20.dp).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
        val c = Offset(size.width / 2, size.height / 2)
        val outer = size.minDimension / 2
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
