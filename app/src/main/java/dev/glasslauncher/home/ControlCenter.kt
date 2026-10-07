package dev.glasslauncher.home

import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.glasslauncher.R
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.ThemeMode
import dev.glasslauncher.dream.AerialActivity
import dev.glasslauncher.system.SystemControls
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.glass
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalMetrics
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import dev.glasslauncher.widgets.rememberClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Gap = 8.dp
private val Pill = 47.dp
private val PillWidth = 128.dp
private val Big = Pill * 2 + Gap
// The big tile matches a pill's width so the second row of pills lines up under it.
private val BigWidth = PillWidth
private val ColumnWidth = PillWidth * 2 + Gap
// Round buttons sit on the same four-across rhythm as tvOS, even when fewer are shown.
private val RoundGap = (ColumnWidth - Pill * 4) / 3
private val Blue = Color(0xFF0A84FF)

/**
 * SYS-01, laid out like tvOS 27 Control Center: the time over a right-hand column of glass tiles.
 * A large Settings tile for the TV's own settings (white when focused, focused first), two-line pills
 * for Wi-Fi, Bluetooth, the launcher's settings and Text Size, then round buttons for game
 * controllers, Light/Dark, the screen saver and the app switcher. Glass tiles
 * take their tint from the content behind them; a pill's icon sits in a white disc while it's on.
 */
@Composable
fun ControlCenter(model: HomeModel, cfg: LauncherConfig, active: Boolean, open: (Overlay) -> Unit, closeAll: () -> Unit) {
    val context = LocalContext.current
    val m = LocalMetrics.current
    val palette = LocalPalette.current
    val first = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) { withFrameNanos { }; runCatching { first.requestFocus() } } }

    val network by produceState("", active) { value = withContext(Dispatchers.IO) { SystemControls.network(context) } }
    val wifiOn = remember(active) { SystemControls.wifiConnected(context) }
    val bluetooth = remember(active) { SystemControls.bluetoothOn() }
    val clock = rememberClock(cfg.clock24h)
    val textSteps = listOf(1f to "Default", 1.15f to "Large", 1.3f to "Larger")
    val textIndex = textSteps.indexOfFirst { it.first >= cfg.textScale - 0.01f }.coerceAtLeast(0)
    val dark = cfg.theme != ThemeMode.Light

    fun system(go: () -> Boolean) { closeAll(); go() }

    Box(Modifier.fillMaxSize().background(Scrim.copy(alpha = 0.18f))) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Gap),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = m.chromeInset, end = m.chromeInset + 14.dp)
                .width(ColumnWidth)
                .trapFocus(active)
                .testTag("control-center"),
        ) {
            Text(clock, style = Type.heading.copy(fontWeight = FontWeight.Medium), color = palette.primary.copy(alpha = 0.9f), modifier = Modifier.padding(start = 4.dp, bottom = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
                // The TV's own settings (network, display, accounts…), like the Settings tile on tvOS.
                CcTile("Settings", "Fire TV", RoundedCornerShape(26.dp), BigWidth, Big, modifier = Modifier.focusRequester(first), onClick = { system { SystemControls.openSystemSettings(context) } }) { fg ->
                    BigIcon(R.drawable.ic_settings, "Settings", fg)
                }
                Column(verticalArrangement = Arrangement.spacedBy(Gap)) {
                    CcTile("Wi-Fi", network, Shapes.pill, PillWidth, Pill, onClick = { system { SystemControls.open(context, Settings.ACTION_WIFI_SETTINGS) } }) { fg ->
                        PillContent(if (wifiOn) R.drawable.ic_wifi else R.drawable.ic_wifi_off, "Wi-Fi", network, fg, on = wifiOn, accent = Blue)
                    }
                    val btValue = when (bluetooth) { true -> "On"; false -> "Off"; null -> "Devices" }
                    CcTile("Bluetooth", btValue, Shapes.pill, PillWidth, Pill, onClick = { system { SystemControls.openBluetooth(context) } }) { fg ->
                        PillContent(if (bluetooth == false) R.drawable.ic_bluetooth_disabled else R.drawable.ic_bluetooth, "Bluetooth", btValue, fg, on = bluetooth == true, accent = Blue)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
                CcTile("Launcher Settings", null, Shapes.pill, PillWidth, Pill, onClick = { closeAll(); open(Overlay.Settings) }) { fg ->
                    PillContent(R.drawable.ic_tune, "Launcher", "Settings", fg, on = false, accent = fg)
                }
                CcTile("Text Size", textSteps[textIndex].second, Shapes.pill, PillWidth, Pill, onClick = {
                    model.edit { it.copy(textScale = textSteps[(textIndex + 1) % textSteps.size].first) }
                }) { fg -> PillContent(R.drawable.ic_format_size, "Text Size", textSteps[textIndex].second, fg, on = false, accent = fg) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(RoundGap)) {
                Round(R.drawable.ic_sports_esports, "Game Controllers") { system { SystemControls.openGameControllers(context) } }
                Round(if (dark) R.drawable.ic_dark_mode else R.drawable.ic_light_mode, if (dark) "Appearance, Dark" else "Appearance, Light") {
                    model.edit { it.copy(theme = if (dark) ThemeMode.Light else ThemeMode.Dark) }
                }
                Round(R.drawable.ic_landscape, "Screen Saver") {
                    closeAll(); context.startActivity(Intent(context, AerialActivity::class.java))
                }
                Round(R.drawable.ic_apps, "App Switcher") { closeAll(); open(Overlay.AppSwitcher) }
            }
        }
    }
}

@Composable
private fun Glyph(@DrawableRes icon: Int, tint: Color, modifier: Modifier = Modifier) {
    Image(painterResource(icon), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = modifier)
}

@Composable
private fun BigIcon(@DrawableRes icon: Int, title: String, fg: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Glyph(icon, fg, Modifier.size(46.dp))
        Text(title, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = fg)
    }
}

/** Icon disc on the left, one or two lines of text on the right, as in tvOS Control Center pills. */
@Composable
private fun PillContent(
    @DrawableRes icon: Int,
    title: String,
    value: String?,
    fg: Color,
    on: Boolean,
    accent: Color,
    disc: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(start = 9.dp, end = 12.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .then(if (disc) Modifier.background(if (on) Color.White else fg.copy(alpha = 0.16f), CircleShape) else Modifier),
        ) { Glyph(icon, if (on) accent else fg, Modifier.size(17.dp)) }
        Column(Modifier.padding(start = 7.dp)) {
            Text(title, style = Type.caption.copy(fontWeight = FontWeight.SemiBold, lineHeight = 15.sp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            value?.let {
                Text(it, style = Type.caption.copy(lineHeight = 15.sp), color = fg.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Round(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    CcTile(label, null, CircleShape, Pill, Pill, onClick = onClick) { fg ->
        Glyph(icon, fg, Modifier.size(23.dp))
    }
}

@Composable
private fun CcTile(
    title: String,
    value: String?,
    shape: Shape,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Color) -> Unit,
) {
    val palette = LocalPalette.current
    FocusTile(
        label = if (value != null) "$title, $value" else title,
        onClick = onClick,
        shape = shape,
        focusedScale = 1.06f,
        shadow = false,
        modifier = modifier.size(width, height),
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
        ) { content(fg) }
    }
}

