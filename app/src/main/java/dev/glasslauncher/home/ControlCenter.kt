package dev.glasslauncher.home

import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glasslauncher.app
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.flow.first
import dev.glasslauncher.glass.glass
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalMetrics
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import dev.glasslauncher.ui.dissolve
import dev.glasslauncher.widgets.rememberClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Blue = Color(0xFF0A84FF)

/**
 * Tile sizes grow with the text size (Settings › Display & Text Size), so labels keep fitting instead of
 * being cut off: at Larger the Wi-Fi network name was truncated in a fixed 128 dp pill.
 */
private class CcSizes(k: Float) {
    val gap = 8.dp
    val pill = (47 * k).dp
    val pillWidth = (128 * k).dp
    val big = pill * 2 + gap
    // The big tile matches a pill's width so the second row of pills lines up under it.
    val bigWidth = pillWidth
    val column = pillWidth * 2 + gap
    // Round buttons sit on the same four-across rhythm as tvOS, even when fewer are shown.
    val roundGap = (column - pill * 4) / 3
    val disc = (28 * k).dp
    val discGlyph = (17 * k).dp
    val roundGlyph = (23 * k).dp
    val bigGlyph = (46 * k).dp
}

private val LocalCcSizes = androidx.compose.runtime.staticCompositionLocalOf { CcSizes(1f) }

/** Control Center's open/close: from the status pill's capsule to the panel. Pure, so it's unit-tested. */
object CcMorph {
    const val MS = 240

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** The capsule's bounds at progress [t] (0 = the pill, 1 = the panel), anchored at the top-right. */
    fun rect(t: Float, pill: androidx.compose.ui.geometry.Rect, panel: androidx.compose.ui.geometry.Rect) =
        androidx.compose.ui.geometry.Rect(lerp(pill.left, panel.left, t), lerp(pill.top, panel.top, t), lerp(pill.right, panel.right, t), lerp(pill.bottom, panel.bottom, t))

    /** From a capsule (half the pill's height) to the tiles' corner radius. */
    fun radius(t: Float, pill: androidx.compose.ui.geometry.Rect, tileRadius: Float) = lerp(pill.height / 2f, tileRadius, t)

    /** The tiles' opacity: nothing for the first 40%, then up to full. */
    fun tiles(t: Float) = ((t - 0.4f) / 0.6f).coerceIn(0f, 1f)
}

/** Room around the scrolling tiles so a focused tile's growth and shadow aren't clipped. */
private val CC_BLEED = 16.dp

/** The tray's clear glass (its text-safe texture, and no refracted edge per tile), for every Control Center surface. */
// The tray's clear glass, the same in either theme (its text-safe copy washed milky in light theme), with
// a faint darkening for the labels; no edge band on a dozen small tiles (frame cost).
private val CC_GLASS = GlassStyle.shelf(false).copy(tint = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.10f), edge = false)

/** The dark, muted wash behind Control Center (tvOS 27 dims rather than blurs). */
private val CC_DIM = Color.Black.copy(alpha = 0.42f)

/** Control Center tiles that can be turned off in Settings › Control Center (id to label). */
val CONTROL_CENTER_TILES = listOf(
    "alexa" to "Alexa Page", "wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "launcher" to "Launcher Settings", "airplay" to "AirPlay",
    "controllers" to "Game Controllers", "appearance" to "Theme", "screensaver" to "Screen Saver",
    "switcher" to "App Switcher", "performance" to "Performance", "memory" to "Free Memory",
)

/**
 * SYS-01, laid out like tvOS 27 Control Center: the time over a right-hand column of glass tiles.
 * A large Settings tile for the TV's own settings (white when focused, focused first), two-line pills
 * for Wi-Fi, Bluetooth and the launcher's settings, then round buttons for game controllers, the theme,
 * the screen saver, the app switcher and AirPlay (when PhairPlay is installed). Glass tiles
 * take their tint from the content behind them; a pill's icon sits in a white disc while it's on.
 */
@Composable
fun ControlCenter(edit: ((LauncherConfig) -> LauncherConfig) -> Unit, cfg: LauncherConfig, active: Boolean, open: (Overlay) -> Unit, closeAll: () -> Unit) {
    // One look in either theme, like the pill and tray: white text and glyphs on clear glass over the
    // dimmed screen (tvOS 27 Control Center isn't themed light or dark).
    val outer = LocalPalette.current
    val palette = remember(outer.highContrast) { dev.glasslauncher.ui.Palette(light = false, highContrast = outer.highContrast) }
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides palette) {
        ControlCenterBody(edit, cfg, active, open, closeAll)
    }
}

@Composable
private fun ControlCenterBody(edit: ((LauncherConfig) -> LauncherConfig) -> Unit, cfg: LauncherConfig, active: Boolean, open: (Overlay) -> Unit, closeAll: () -> Unit) {
    val context = LocalContext.current
    val m = LocalMetrics.current
    val palette = LocalPalette.current
    val first = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) { withFrameNanos { }; runCatching { first.requestFocus() } } }
    val screen = dev.glasslauncher.ui.LocalScreenDissolve.current

    val network by produceState("", active) { value = withContext(Dispatchers.IO) { SystemControls.network(context) } }
    val wifiOn = remember(active) { SystemControls.wifiConnected(context) }
    val bluetooth = remember(active) { SystemControls.bluetoothOn() }
    val clock = rememberClock(cfg.clock24h, seconds = true)
    // The time sits on the dark wash, so it's white whatever is behind (no shadow: tvOS text is flat).
    val headerOnLight = false
    val headerColor = Color.White
    val headerStyle = Type.heading.copy(fontWeight = FontWeight.Medium)
    val sz = remember(cfg.textScale) { CcSizes(cfg.textScale) }
    fun shown(id: String) = id !in cfg.ccHidden
    // Pages (tvOS 27's icons along the top): 0 = Controls, 1 = Alexa. Opening always shows Controls.
    var page by remember(active) { androidx.compose.runtime.mutableIntStateOf(0) }
    val alexaPage = shown("alexa")
    // The focused round button's name, shown under the round buttons (they're icons only).
    var roundLabel by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val dark = cfg.theme != ThemeMode.Light
    val airPlay = dev.glasslauncher.system.AirPlay.rememberState(active)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rooted by produceState(dev.glasslauncher.system.Root.known) { value = dev.glasslauncher.system.Root.available() }
    var fast by androidx.compose.runtime.remember(active) { androidx.compose.runtime.mutableStateOf(dev.glasslauncher.system.RootFeatures.fast(context)) }
    var freed by androidx.compose.runtime.remember(active) { androidx.compose.runtime.mutableStateOf<Int?>(null) }

    fun system(go: () -> Boolean) { closeAll(); go() }

    // Grows out of the status pill (top right) and shrinks back into it.
    // Grows out of the status pill: a capsule at the pill's place stretches into the panel, and the tiles
    // fade up over the last 60%. Closing runs it backwards into the pill. Transforms and one outline per
    // frame; the tiles' layout never changes.
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    val exiting = LocalOverlayExiting.current
    LaunchedEffect(exiting) { enter.animateTo(if (exiting) 0f else 1f, androidx.compose.animation.core.tween(CcMorph.MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    // The tiles' glass (a capture of the screen behind, or Home's own) fades in once they have landed:
    // sampled through the fade and rise it cost a fifth of the frames, and the moving tiles hide it anyway.
    // Until then, and while closing, they draw the overlay's smoky fill.
    val glassState = LocalBackdrop.current
    LaunchedEffect(exiting) {
        glassState.textureIn.snapTo(0f)
        if (exiting) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { enter.value >= 1f }.first { it }
        androidx.compose.runtime.snapshotFlow { glassState.backdrop != null }.first { it }
        glassState.textureIn.animateTo(1f, androidx.compose.animation.core.tween(180))
    }
    var panel by remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val pill = ControlCenterWindow.pillBounds
    val capsule = if (palette.light) Color.White.copy(alpha = 0.24f) else Color(0x7A2A2E37)
    androidx.compose.runtime.CompositionLocalProvider(LocalCcSizes provides sz) {
    Box(Modifier.fillMaxSize()) {
        // tvOS 27 mutes what's behind with a dark wash rather than blurring it: one translucent layer,
        // and over another app (an overlay window) the system composites it without redrawing anything.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = enter.value }.background(CC_DIM))
        // The capsule: it is the pill on the first frame and the panel's outline by the end, fading out as
        // the tiles (each with its own glass) arrive.
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            // From the very first frame (before the panel is measured, it's simply the pill), so there's
            // never a frame with neither the pill nor the capsule.
            val from = pill ?: panel?.let { androidx.compose.ui.geometry.Rect(it.right - 107.dp.toPx(), it.top, it.right, it.top + 32.dp.toPx()) } ?: return@Canvas
            val target = panel ?: from
            val e = enter.value
            val a = 1f - CcMorph.tiles(e)
            if (a <= 0f) return@Canvas
            val r = CcMorph.rect(e, from, target)
            val radius = CcMorph.radius(e, from, 26.dp.toPx())
            drawRoundRect(capsule, topLeft = r.topLeft, size = r.size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius), alpha = a)
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .onGloballyPositioned { panel = it.boundsInWindow() }
                .graphicsLayer {
                    val t = CcMorph.tiles(enter.value)
                    alpha = t
                    translationY = (1f - t) * 14.dp.toPx()
                }
                .padding(top = m.chromeInset, end = m.chromeInset + 14.dp - CC_BLEED)
                .trapFocus(active)
                .testTag("control-center"),
        ) {
            // One band above the tiles (tvOS 27): the time with seconds, the date and the weather on the
            // left, lined up with the tiles; the page icons on the right (focusing one shows its page).
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.width(sz.column + CC_BLEED * 2).padding(start = CC_BLEED + 4.dp, end = CC_BLEED, bottom = 16.dp - CC_BLEED),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(clock, style = headerStyle.copy(fontFeatureSettings = "tnum"), color = headerColor, maxLines = 1, softWrap = false, modifier = Modifier.testTag("cc-clock"))
                    Text(dev.glasslauncher.widgets.rememberDate(), style = Type.secondary, color = headerColor.copy(alpha = 0.7f), maxLines = 1, softWrap = false, modifier = Modifier.testTag("cc-date"))
                    // Equal air between the three lines as drawn: the clock's own leading already sits under it,
                    // so the weather line gets the matching gap above (e2e measures the ink).
                    cfg.weather?.let { Box(Modifier.padding(top = 5.dp)) { dev.glasslauncher.widgets.WeatherLabel(it, headerColor.copy(alpha = 0.7f), Type.secondary) } }
                }
                if (alexaPage) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 16.dp)) {
                    PageIcon(R.drawable.ic_tune, "Controls", selected = page == 0) { page = 0 }
                    PageIcon(R.drawable.ic_home, "Alexa", selected = page == 1) { page = 1 }
                }
            }
            // The tiles scroll when they're taller than the screen (Now Playing, large text), as tvOS's do;
            // CC_BLEED of room on each side keeps a focused tile's growth and shadow from being clipped.
            Column(
                verticalArrangement = Arrangement.spacedBy(sz.gap),
                modifier = Modifier
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(CC_BLEED)
                    .padding(bottom = m.chromeInset)
                    .width(sz.column),
            ) {
            androidx.compose.animation.Crossfade(page, animationSpec = androidx.compose.animation.core.tween(140), label = "cc-page") { shownPage ->
            Column(verticalArrangement = Arrangement.spacedBy(sz.gap)) {
            if (shownPage == 1) {
                AlexaPage(sz, closeAll)
            } else {
            Row(horizontalArrangement = Arrangement.spacedBy(sz.gap)) {
                // The TV's own settings (network, display, accounts…), like the Settings tile on tvOS.
                CcTile("Settings", "Fire TV", RoundedCornerShape(26.dp), sz.bigWidth, sz.big, modifier = Modifier.focusRequester(first), onClick = { closeAll(); open(Overlay.TvSettings) }) { fg ->
                    BigIcon(R.drawable.ic_settings, "Settings", fg)
                }
                Column(verticalArrangement = Arrangement.spacedBy(sz.gap)) {
                    if (shown("wifi")) CcTile("Wi-Fi", network, Shapes.pill, sz.pillWidth, sz.pill, onClick = { system { SystemControls.open(context, Settings.ACTION_WIFI_SETTINGS) } }) { fg ->
                        PillContent(if (wifiOn) R.drawable.ic_wifi else R.drawable.ic_wifi_off, "Wi-Fi", network, fg, on = wifiOn, accent = Blue)
                    }
                    val btValue = when (bluetooth) { true -> "On"; false -> "Off"; null -> "Devices" }
                    if (shown("bluetooth")) CcTile("Bluetooth", btValue, Shapes.pill, sz.pillWidth, sz.pill, onClick = { system { SystemControls.openBluetooth(context) } }) { fg ->
                        PillContent(if (bluetooth == false) R.drawable.ic_bluetooth_disabled else R.drawable.ic_bluetooth, "Bluetooth", btValue, fg, on = bluetooth == true, accent = Blue)
                    }
                }
            }
            if (shown("launcher")) CcTile("Launcher Settings", null, Shapes.pill, sz.column, sz.pill, onClick = { closeAll(); open(Overlay.Settings) }) { fg ->
                PillContent(R.drawable.ic_tune, "Launcher Settings", null, fg, on = false, accent = fg)
            }
            // Round buttons, four to a row (tvOS), with the focused one's name in a caption underneath.
            val rounds = buildList<@Composable () -> Unit> {
                val label: (String?) -> Unit = { roundLabel = it }
                if (shown("controllers")) add { Round(R.drawable.ic_sports_esports, "Game Controllers", onLabel = label) { system { SystemControls.openGameControllers(context) } } }
                if (shown("appearance")) add {
                    Round(if (dark) R.drawable.ic_dark_mode else R.drawable.ic_light_mode, if (dark) "Theme, Dark" else "Theme, Light", onLabel = label) {
                        screen.dissolve { edit { it.copy(theme = if (dark) ThemeMode.Light else ThemeMode.Dark) } }
                    }
                }
                if (shown("screensaver")) add { Round(R.drawable.ic_landscape, "Screen Saver", onLabel = label) { closeAll(); AerialActivity.start(context) } }
                if (shown("switcher")) add { Round(R.drawable.ic_apps, "App Switcher", onLabel = label) { closeAll(); open(Overlay.AppSwitcher) } }
                // AirPlay receiving (PhairPlay, scripts/phairplay), once it's installed: a round toggle, white
                // while on, so Control Center stays short enough for the Now Playing card.
                if (shown("airplay")) airPlay?.let { (state, set) ->
                    val value = when {
                        state.sender != null -> "Connected to ${state.sender}"
                        state.on -> "On"
                        else -> "Off"
                    }
                    add { Round(R.drawable.ic_airplay, "AirPlay, $value", on = state.on, onLabel = label) { set(!state.on) } }
                }
                // Root only (Settings › Root has the details): Performance is a toggle, white while Fast is on;
                // Free Memory ends background apps and says how much it freed.
                if (rooted && shown("performance")) add {
                    Round(R.drawable.ic_speed, if (fast) "Performance, Fast" else "Performance, Balanced", on = fast, onLabel = label) {
                        val next = !fast
                        fast = next
                        scope.launch { dev.glasslauncher.system.RootFeatures.setFast(context, next) }
                    }
                }
                if (rooted && shown("memory")) add {
                    Round(R.drawable.ic_cleaning_services, freed?.let { "Free Memory, $it MB freed" } ?: "Free Memory", onLabel = label) {
                        scope.launch { freed = dev.glasslauncher.system.RootFeatures.freeMemory(context) }
                    }
                }
            }
            rounds.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(sz.roundGap)) { row.forEach { it() } }
            }
            // Always takes its line, so the column doesn't jump as focus moves on and off the round buttons.
            Text(
                roundLabel?.replace(", ", " · ") ?: freed?.let { "$it MB freed" } ?: "",
                style = Type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = headerColor,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp).testTag("cc-caption"),
            )
            }
            }
            }
            // What's playing, with controls (any app with a media session: Spotify, Amazon Music, YouTube…).
            val np by context.app.nowPlaying.state.collectAsStateWithLifecycle()
            np?.let { dev.glasslauncher.widgets.NowPlayingCard(it, sz.column, cfg.textScale) }
            }
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
        Glyph(icon, fg, Modifier.size(LocalCcSizes.current.bigGlyph))
        Text(title, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = fg)
    }
}

/** One of the page icons along the top: a small glass disc, white while its page shows or it's focused. */
@Composable
private fun PageIcon(@DrawableRes icon: Int, label: String, selected: Boolean, onShow: () -> Unit) {
    val palette = LocalPalette.current
    FocusTile(
        label = label, onClick = onShow, shape = CircleShape, focusedScale = 1.08f, shadow = false,
        onFocusChange = { if (it) onShow() },
        modifier = Modifier.size(40.dp),
    ) { focused ->
        val lit = focused || selected
        Box(
            Modifier.fillMaxSize().glass(LocalBackdrop.current, CircleShape, CC_GLASS)
                .then(if (lit) Modifier.background(palette.focusFill, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(icon), null, colorFilter = ColorFilter.tint(if (lit) palette.onFocusFill else palette.primary), modifier = Modifier.size(20.dp))
        }
    }
}

/** Alexa shortcuts, in the same tiles as the Controls page. Each opens Amazon's own screen and closes. */
@Composable
private fun AlexaPage(sz: CcSizes, closeAll: () -> Unit) {
    val context = LocalContext.current
    fun go(open: (android.content.Context) -> Boolean) { closeAll(); open(context) }
    Row(horizontalArrangement = Arrangement.spacedBy(sz.gap)) {
        CcTile("Smart Home", null, RoundedCornerShape(26.dp), sz.bigWidth, sz.big, onClick = { go(SystemControls::openSmartHome) }) { fg ->
            BigIcon(R.drawable.ic_home, "Smart Home", fg)
        }
        Column(verticalArrangement = Arrangement.spacedBy(sz.gap)) {
            CcTile("Ask Alexa", null, Shapes.pill, sz.pillWidth, sz.pill, onClick = { go(SystemControls::askAlexa) }) { fg ->
                PillContent(R.drawable.ic_mic, "Ask Alexa", null, fg, on = false, accent = fg)
            }
            CcTile("Cameras", null, Shapes.pill, sz.pillWidth, sz.pill, onClick = { go(SystemControls::openSmartHome) }) { fg ->
                PillContent(R.drawable.ic_videocam, "Cameras", null, fg, on = false, accent = fg)
            }
        }
    }
    CcTile("Alexa Settings", null, Shapes.pill, sz.column, sz.pill, onClick = { go(SystemControls::openAlexaSettings) }) { fg ->
        PillContent(R.drawable.ic_settings, "Alexa Settings", null, fg, on = false, accent = fg)
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
    val sz = LocalCcSizes.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(start = 9.dp, end = 12.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(sz.disc)
                .then(if (disc) Modifier.background(if (on) Color.White else fg.copy(alpha = 0.16f), CircleShape) else Modifier),
        ) { Glyph(icon, if (on) accent else fg, Modifier.size(sz.discGlyph)) }
        Column(Modifier.padding(start = 7.dp)) {
            Text(title, style = Type.caption.copy(fontWeight = FontWeight.SemiBold, lineHeight = 15.sp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            value?.let {
                Text(it, style = Type.caption.copy(lineHeight = 15.sp), color = fg.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Round(@DrawableRes icon: Int, label: String, on: Boolean = false, onLabel: (String?) -> Unit = {}, onClick: () -> Unit) {
    val sz = LocalCcSizes.current
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    // The caption follows the button while focused, including its state (Theme · Light → Dark).
    LaunchedEffect(focused, label) { if (focused) onLabel(label) }
    CcTile(label, null, CircleShape, sz.pill, sz.pill, onClick = onClick, on = on, onFocusChange = { f ->
        focused = f
        if (!f) onLabel(null)
    }) { fg ->
        Glyph(icon, fg, Modifier.size(sz.roundGlyph))
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
    on: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    content: @Composable (Color) -> Unit,
) {
    val palette = LocalPalette.current
    FocusTile(
        label = if (value != null) "$title, $value" else title,
        onClick = onClick,
        onFocusChange = onFocusChange,
        shape = shape,
        // A touch of lift only: at 1.06 the focused Settings tile spilled past the pills' grid.
        focusedScale = 1.02f,
        shadow = false,
        modifier = modifier.size(width, height),
    ) { focused ->
        // A toggle that's on sits white with a blue glyph, like tvOS's Control Center toggles.
        val fg = if (focused) palette.onFocusFill else if (on) Blue else palette.primary
        Box(
            Modifier
                .fillMaxSize()
                // The glass stays put and the focus or "on" fill draws over it: swapping modifiers made a
                // fresh glass node on every focus change, which could draw a frame before knowing where it
                // was (a tile that had just lost focus showed up see-through).
                .glass(LocalBackdrop.current, shape, CC_GLASS)
                .then(
                    if (focused) Modifier.background(palette.focusFill, shape)
                    else if (on) Modifier.background(Color.White, shape)
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) { content(fg) }
    }
}

