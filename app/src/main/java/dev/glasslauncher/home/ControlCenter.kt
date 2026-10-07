package dev.glasslauncher.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.glasslauncher.data.BackgroundMode
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.ThemeMode
import dev.glasslauncher.dream.AerialActivity
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.glass
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalMetrics
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import dev.glasslauncher.widgets.GearIcon

/**
 * SYS-01: a column of glass tiles on the right under the status pill, opening near-instantly. One
 * large primary tile, two-line pill tiles, then a row of round buttons. Focused tiles turn white.
 */
@Composable
fun ControlCenter(model: HomeModel, cfg: LauncherConfig, active: Boolean, open: (Overlay) -> Unit, closeAll: () -> Unit) {
    val context = LocalContext.current
    val m = LocalMetrics.current
    val first = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) { withFrameNanos { }; runCatching { first.requestFocus() } } }
    fun startSystem(action: String) {
        closeAll()
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    Box(Modifier.fillMaxSize().background(Scrim.copy(alpha = 0.2f))) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = m.chromeInset + 44.dp, end = m.chromeInset)
                .width(232.dp)
                .trapFocus(active)
                .testTag("control-center"),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CcTile("Settings", null, RoundedCornerShape(22.dp), size = 112.dp, height = 112.dp, modifier = Modifier.focusRequester(first), onClick = { closeAll(); open(Overlay.Settings) }) { color ->
                    GearIcon(color, size = 40.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CcTile("Appearance", cfg.theme.name, Shapes.pill, size = 112.dp, height = 52.dp, onClick = {
                        val next = if (cfg.theme == ThemeMode.Light) ThemeMode.Dark else ThemeMode.Light
                        model.edit { it.copy(theme = next) }
                    })
                    CcTile("Background", backgroundName(cfg.background), Shapes.pill, size = 112.dp, height = 52.dp, onClick = {
                        val next = BackgroundMode.entries[(cfg.background.ordinal + 1) % BackgroundMode.entries.size]
                        model.edit { it.copy(background = next) }
                    })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CcTile("Screen Saver", "Start", Shapes.pill, size = 112.dp, height = 52.dp, onClick = {
                    closeAll(); context.startActivity(Intent(context, AerialActivity::class.java))
                })
                CcTile("Wi-Fi", "Network", Shapes.pill, size = 112.dp, height = 52.dp, onClick = { startSystem(Settings.ACTION_WIFI_SETTINGS) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CcTile("Text Size", null, CircleShape, size = 52.dp, height = 52.dp, onClick = {
                    val steps = listOf(1f, 1.15f, 1.3f)
                    val next = steps[(steps.indexOfFirst { it >= cfg.textScale - 0.01f }.coerceAtLeast(0) + 1) % steps.size]
                    model.edit { it.copy(textScale = next) }
                }) { color -> Text("Aa", style = Type.body, color = color) }
                CcTile("Reduce Motion", null, CircleShape, size = 52.dp, height = 52.dp, onClick = {
                    model.edit { it.copy(reduceMotion = if (it.reduceMotion == dev.glasslauncher.data.Auto.On) dev.glasslauncher.data.Auto.Auto else dev.glasslauncher.data.Auto.On) }
                }) { color -> Text("◐", style = Type.heading, color = color) }
                CcTile("Phone Setup", null, CircleShape, size = 52.dp, height = 52.dp, onClick = { closeAll(); open(Overlay.Settings) }) { color ->
                    Text("⌘", style = Type.heading, color = color)
                }
                CcTile("Fire TV Settings", null, CircleShape, size = 52.dp, height = 52.dp, onClick = { startSystem(Settings.ACTION_SETTINGS) }) { color ->
                    Text("TV", style = Type.label, color = color)
                }
            }
        }
    }
}

private fun backgroundName(mode: BackgroundMode) = when (mode) {
    BackgroundMode.Featured -> "Featured"
    BackgroundMode.Wallpaper -> "Wallpaper"
    BackgroundMode.Motion -> "Motion"
}

@Composable
private fun CcTile(
    title: String,
    value: String?,
    shape: Shape,
    size: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (androidx.compose.ui.graphics.Color) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    FocusTile(
        label = if (value != null) "$title, $value" else title,
        onClick = onClick,
        shape = shape,
        focusedScale = 1.06f,
        shadow = false,
        modifier = modifier.size(size, height),
    ) { focused ->
        val fg = if (focused) palette.onFocusFill else palette.primary
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (focused) Modifier.background(palette.focusFill, shape)
                    else Modifier.glass(LocalBackdrop.current, shape, GlassStyle.overlay(palette.light)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                icon != null && value == null && size >= 100.dp -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    icon(fg)
                    Text(title, style = Type.caption, color = fg)
                }
                icon != null -> icon(fg)
                else -> Column(Modifier.padding(horizontal = 14.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text(title, style = Type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = fg, maxLines = 1)
                    value?.let { Text(it, style = Type.caption, color = fg.copy(alpha = 0.7f), maxLines = 1) }
                }
            }
        }
    }
}
