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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
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
    /**
     * tvOS 27's Control Center is one grid of square cells: u is a cell, gap the one space between cells.
     * Circles are 1×1, pills 2×1 (as tall as a circle), the big tile 2×2 and square, wide rows 4×1, and the
     * panel 4u + 3·gap. Everything below derives from these two.
     */
    // 56 dp: the smallest cell whose pills fit "Bluetooth" and "Connected" at the 13 sp floor (JVM test).
    val u = (56 * k).dp
    val gap = (9 * k).dp
    val round = u
    val pill = u
    val pillWidth = u * 2 + gap
    val big = u * 2 + gap
    val bigWidth = big
    val column = u * 4 + gap * 3
    val disc = u * 0.5f
    val discGlyph = u * 0.33f
    val roundGlyph = u * 0.44f
    val bigGlyph = u * 0.8f
}

private val LocalCcSizes = androidx.compose.runtime.staticCompositionLocalOf { CcSizes(1f) }

/**
 * Control Center's open/close: the status pill's capsule swells into the panel like a drop of liquid, and
 * shrinks back into the pill. One progress value (0 = the pill, 1 = the panel) drives it, from a spring
 * that bounces a little past 1 on open. Pure, so it's unit-tested.
 */
object CcMorph {
    /** Opening: a lively spring with a slight settle bounce (tuned by eye). */
    val openSpring = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.76f, stiffness = 140f, visibilityThreshold = 0.001f)
    /** Closing: stiffer and almost critically damped, so it lands in the pill without wobbling. */
    val closeSpring = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.92f, stiffness = 225f, visibilityThreshold = 0.001f)
    /**
     * When the close has visibly landed (the spring is within 1% of the pill; the rest is an invisible tail).
     * The overlay window / in-launcher overlay is removed then, so it stops taking input (a test ties it to the spring).
     */
    const val CLOSE_MS = 370

    /** The bubble's corner stays capsule-round until this much of the open, then tightens to the tiles' radius. */
    private const val ROUND_UNTIL = 0.55f
    /** The tiles appear only once the bubble is this open. */
    private const val TILES_AFTER = 0.6f
    /** Closing: the tiles are gone by this progress, and only then does the bubble collapse. */
    private const val CLOSE_HOLD = 0.85f
    /** How far the bubble's height squeezes while collapsing into the pill (to 96%). */
    private const val SQUEEZE = 0.04f

    /** Rows arrive one after another, a little apart. */
    private const val ROW_STEP = 0.03f
    private const val ROWS = 6

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * The bubble's progress (0..1, a little over 1 in the bounce) for the spring's value [e]. Opening it is
     * the value itself; closing it holds full size while the tiles leave, then collapses. [from] is where
     * the close began (Back mid-open), so the two directions agree at the turn and nothing jumps.
     */
    fun bubble(e: Float, closing: Boolean, from: Float = 1f): Float =
        if (closing) minOf(from, e / CLOSE_HOLD).coerceAtLeast(0f) else e.coerceAtLeast(0f)

    /**
     * The bubble's bounds at progress [t]: the centre travels from the pill's to the panel's while the size
     * grows (past 1 it overshoots a little; it never goes negative). [squeeze] scales the height about the centre.
     */
    fun rect(t: Float, pill: androidx.compose.ui.geometry.Rect, panel: androidx.compose.ui.geometry.Rect, squeeze: Float = 1f): androidx.compose.ui.geometry.Rect {
        val p = t.coerceAtLeast(0f)
        val cx = lerp(pill.center.x, panel.center.x, p); val cy = lerp(pill.center.y, panel.center.y, p)
        val w = lerp(pill.width, panel.width, p).coerceAtLeast(0f); val h = (lerp(pill.height, panel.height, p) * squeeze).coerceAtLeast(0f)
        return androidx.compose.ui.geometry.Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    /**
     * Closing squeezes the bubble to ~96% of its height mid-collapse (1 at both ends); opening doesn't. It is
     * measured against [from] (where the close began), so a Back mid-open starts from 1 and doesn't step.
     */
    fun squeeze(t: Float, closing: Boolean, from: Float = 1f): Float =
        if (!closing) 1f else 1f - SQUEEZE * kotlin.math.sin(Math.PI * Math.pow((t / from.coerceAtLeast(0.01f)).coerceIn(0f, 1f).toDouble(), 0.6)).toFloat()

    /**
     * The corner radius of [bounds] at progress [t]: a full capsule (half the short side) until late, reaching
     * the tiles' [tileRadius] only at the end. From the pill's capsule it never passes a capsule's half.
     */
    fun radius(t: Float, bounds: androidx.compose.ui.geometry.Rect, tileRadius: Float): Float {
        val capsule = minOf(bounds.width, bounds.height) / 2f
        val k = ((t - ROUND_UNTIL) / (1f - ROUND_UNTIL)).coerceIn(0f, 1f)
        return lerp(capsule, minOf(tileRadius, capsule), k * k)
    }

    /**
     * A row's arrival (0..1). Opening: nothing until the bubble is ~60% open, then top row first, every row
     * landed by the end. Closing: the rows leave first, in the stretch before the bubble starts to collapse.
     */
    fun tiles(e: Float, row: Int = 0, closing: Boolean = false, from: Float = 1f): Float {
        val start = TILES_AFTER + row.coerceIn(0, ROWS - 1) * ROW_STEP
        val arrival = ((e - start) / (1f - TILES_AFTER - (ROWS - 1) * ROW_STEP)).coerceIn(0f, 1f)
        if (!closing) return arrival
        val f = from.coerceIn(0.01f, 1f)
        return tiles(f, row) * ((e - CLOSE_HOLD * f) / (f * (1f - CLOSE_HOLD))).coerceIn(0f, 1f)
    }

    /** Tiles grow a touch as they arrive. */
    fun scale(arrival: Float) = 0.96f + 0.04f * arrival

    /** A row's look at progress [t]: with Reduce Motion it only fades (no rise, no grow). */
    data class RowMotion(val alpha: Float, val rise: Float, val scale: Float)

    fun row(t: Float, row: Int = 0, reduceMotion: Boolean, closing: Boolean = false, from: Float = 1f): RowMotion =
        if (reduceMotion) RowMotion(alpha = t.coerceIn(0f, 1f), rise = 0f, scale = 1f)
        else tiles(t, row, closing, from).let { a -> RowMotion(alpha = a, rise = 1f - a, scale = scale(a)) }
}

/** One outline: the bubble at progress [b] between the pill and the panel (see [CcMorph]). */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCcBubble(
    b: Float, squeeze: Float, pill: androidx.compose.ui.geometry.Rect, panel: androidx.compose.ui.geometry.Rect,
    color: Color, alpha: Float, tileRadius: Float,
) {
    val r = CcMorph.rect(b, pill, panel, squeeze)
    drawRoundRect(color, topLeft = r.topLeft, size = r.size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(CcMorph.radius(b, r, tileRadius)), alpha = alpha)
}

/** Room around the scrolling tiles so a focused tile's growth and shadow aren't clipped. */
private val CC_BLEED = 16.dp

/** The tray's clear glass (its text-safe texture, and no refracted edge per tile), for every Control Center surface. */
// The tray's clear glass, the same in either theme (its text-safe copy washed milky in light theme), with
// a faint darkening for the labels; no edge band on a dozen small tiles (frame cost).
// A faint highlight only: the tray's sheen, repeated on a dozen small tiles, read as glossy and shiny.
private val CC_GLASS = GlassStyle.shelf(false).copy(tint = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.06f), highlight = 0.03f, matchedFlat = true)

/** The dark, muted wash behind Control Center (tvOS 27 dims rather than blurs). */
/** How much Control Center mutes the screen behind it; its glass samples the scene muted by the same amount. */
internal const val CC_DIM_ALPHA = 0.42f
/** The time, date and weather share one opacity, slightly muted, as tvOS's header. */
private const val HEADER_ALPHA = 0.85f
private val CC_DIM = Color.Black.copy(alpha = CC_DIM_ALPHA)

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

    // Grows out of the status pill: its capsule swells into the panel from the pill's centre like a drop of
    // liquid, the tiles fade up once it is ~60% open, and closing runs the tiles out first, then collapses
    // the bubble back into the pill. One Animatable drives both directions, so Back mid-open reverses from
    // wherever it is. Transforms and one outline per frame; the tiles' layout never changes.
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    val exiting = LocalOverlayExiting.current
    val reduceMotion = dev.glasslauncher.ui.LocalUiPrefs.current.reduceMotion
    // Where the close began (read once, here, so it isn't a per-frame dependency): the bubble and tiles turn from there.
    val closeFrom = remember(exiting) { if (exiting) androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { enter.value }.coerceIn(0.01f, 1f) else 1f }
    LaunchedEffect(exiting) {
        // Reduce Motion: no growing bubble, a plain fade (a bounce would pulse the opacity).
        if (reduceMotion) enter.animateTo(if (exiting) 0f else 1f, dev.glasslauncher.ui.Motion.overlay())
        else if (exiting) enter.animateTo(0f, CcMorph.closeSpring)
        else enter.animateTo(1f, CcMorph.openSpring)
    }
    // Each row of tiles arrives on its own beat: a fade, a small rise and a slight grow, top to bottom.
    fun Modifier.ccRow(row: Int) = graphicsLayer {
        val r = CcMorph.row(enter.value, row, reduceMotion, exiting, closeFrom)
        alpha = r.alpha
        translationY = r.rise * 14.dp.toPx()
        scaleX = r.scale; scaleY = r.scale
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
    }
    // While the tiles move they draw a flat fill matched to their glass (GlassMatch: the scene under them,
    // tinted and dimmed), and the textured glass fades in once they land; on close it drops back to that
    // fill. Sampling the texture through the motion cost a third of the frames, and because the fill is the
    // glass's own colour, the swap no longer flashes white, grey, then smoky (the user's video, plan §11).
    val glassState = LocalBackdrop.current
    LaunchedEffect(exiting) {
        glassState.textureIn.snapTo(0f)
        if (exiting) return@LaunchedEffect
        // Landed, not merely reached: the spring bounces past 1, and the texture must not fade in mid-bounce.
        androidx.compose.runtime.snapshotFlow { enter.value >= 1f && !enter.isRunning }.first { it }
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
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = enter.value.coerceIn(0f, 1f) }.background(CC_DIM))
        // The bubble: it is the pill on the first frame and the panel's outline by the end, fading out as
        // the tiles (each with its own glass) arrive.
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            // From the very first frame (before the panel is measured, it's simply the pill), so there's
            // never a frame with neither the pill nor the capsule.
            val from = pill ?: panel?.let { androidx.compose.ui.geometry.Rect(it.right - 107.dp.toPx(), it.top, it.right, it.top + 32.dp.toPx()) } ?: return@Canvas
            val target = panel ?: from
            val e = enter.value
            // Reduce Motion: no growing bubble, Control Center simply fades in.
            if (reduceMotion) return@Canvas
            val a = 1f - CcMorph.tiles(e, 0, exiting, closeFrom)
            if (a <= 0f) return@Canvas
            val b = CcMorph.bubble(e, exiting, closeFrom)
            drawCcBubble(b, CcMorph.squeeze(b, exiting, closeFrom), from, target, capsule, a, 26.dp.toPx())
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .onGloballyPositioned { panel = it.boundsInWindow() }
                .padding(top = m.chromeInset, end = m.chromeInset + 14.dp - CC_BLEED)
                .trapFocus(active)
                .testTag("control-center"),
        ) {
            // One band above the tiles (tvOS 27): the time with seconds, the date and the weather on the
            // left, lined up with the tiles; the page icons on the right (focusing one shows its page).
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.ccRow(0).width(sz.column + CC_BLEED * 2).padding(start = CC_BLEED + 4.dp, end = CC_BLEED, bottom = 16.dp - CC_BLEED),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(clock, style = headerStyle.copy(fontFeatureSettings = "tnum"), color = headerColor.copy(alpha = HEADER_ALPHA), maxLines = 1, softWrap = false, modifier = Modifier.testTag("cc-clock"))
                    Text(dev.glasslauncher.widgets.rememberDate(), style = Type.secondary, color = headerColor.copy(alpha = HEADER_ALPHA), maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 6.dp).testTag("cc-date"))
                    // Equal air between the three lines as drawn: the clock's own leading already sits under it,
                    // so the weather line gets the matching gap above (e2e measures the ink).
                    cfg.weather?.let { Box(Modifier.padding(top = 11.dp)) { dev.glasslauncher.widgets.WeatherLabel(it, headerColor.copy(alpha = HEADER_ALPHA), Type.secondary) } }
                }
                if (alexaPage) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 16.dp)) {
                    PageIcon(R.drawable.ic_tune, "Controls", selected = page == 0) { page = 0 }
                    // The Alexa app's own icon (loaded once per open); the mic if it isn't installed.
                    val alexaIcon = remember(active) { AlexaIcon.load(context.packageManager, 52)?.asImageBitmap() }
                    if (alexaIcon != null) PageIcon("Alexa", selected = page == 1, onShow = { page = 1 }) {
                        Image(alexaIcon, null, modifier = Modifier.size(26.dp).clip(CircleShape))
                    } else PageIcon(R.drawable.ic_mic, "Alexa", selected = page == 1) { page = 1 }
                }
            }
            val np by context.app.nowPlaying.state.collectAsStateWithLifecycle()
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
                Box(Modifier.ccRow(1)) { AlexaPage(sz, closeAll) }
            } else {
            Row(horizontalArrangement = Arrangement.spacedBy(sz.gap), modifier = Modifier.ccRow(1)) {
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
            if (shown("launcher")) CcTile("Launcher Settings", null, Shapes.pill, sz.column, sz.pill, modifier = Modifier.ccRow(2), onClick = { closeAll(); open(Overlay.Settings) }) { fg ->
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
            // Centred under the tiles as one block, rows sharing a left edge.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.width(sz.round * 4 + sz.gap * 3), verticalArrangement = Arrangement.spacedBy(sz.gap)) {
                    rounds.chunked(4).forEachIndexed { i, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(sz.gap), modifier = Modifier.ccRow(3 + i)) { row.forEach { it() } }
                    }
                }
            }
            // The focused round button's name. It takes its line (so the column doesn't jump as focus moves),
            // except while music plays: then the music card follows the buttons on the grid's gap instead.
            if (np == null) Text(
                roundLabel?.replace(", ", " · ") ?: freed?.let { "$it MB freed" } ?: "",
                style = Type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = headerColor,
                maxLines = 1,
                modifier = Modifier.ccRow(5).padding(start = 4.dp).testTag("cc-caption"),
            )
            }
            }
            }
            // What's playing, with controls (any app with a media session: Spotify, Amazon Music, YouTube…).
            np?.let { Box(Modifier.ccRow(5)) { dev.glasslauncher.widgets.NowPlayingCard(it, sz.column, cfg.textScale) } }
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
    PageIcon(label, selected, onShow) { lit ->
        Image(painterResource(icon), null, colorFilter = ColorFilter.tint(if (lit) palette.onFocusFill else palette.primary), modifier = Modifier.size(20.dp))
    }
}

/** The same disc around any [content] (given whether the disc is lit), for art that keeps its own colours. */
@Composable
private fun PageIcon(label: String, selected: Boolean, onShow: () -> Unit, content: @Composable (lit: Boolean) -> Unit) {
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
        ) { content(lit) }
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
            // Alexa's settings screen is permission-guarded; without root there's no way in.
            if (dev.glasslauncher.system.Root.known) CcTile("Alexa Settings", null, Shapes.pill, sz.pillWidth, sz.pill, onClick = { go(SystemControls::openAlexaSettings) }) { fg ->
                // Two lines, like Wi-Fi's: "Alexa Settings" didn't fit a pill.
                PillContent(R.drawable.ic_settings, "Alexa", "Settings", fg, on = false, accent = fg)
            }
        }
    }
}

private const val FILL_FADE_MS = 120

/** A pill's icon disc: white when its toggle is on, fading between the two. */
@Composable
private fun discColor(on: Boolean, fg: Color): Color {
    val a by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, androidx.compose.animation.core.tween(FILL_FADE_MS), label = "disc")
    return androidx.compose.ui.graphics.lerp(fg.copy(alpha = 0.16f), Color.White, a)
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
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(start = 9.dp, end = 8.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(sz.disc)
                .then(if (disc) Modifier.background(discColor(on, fg), CircleShape) else Modifier),
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
    CcTile(label, null, CircleShape, sz.round, sz.round, onClick = onClick, on = on, onFocusChange = { f ->
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
        // Focus and "on" fills fade (never pop), so a focus move cross-fades the white from one tile to the next.
        val fade = androidx.compose.animation.core.tween<Float>(FILL_FADE_MS)
        val focusA by androidx.compose.animation.core.animateFloatAsState(if (focused) 1f else 0f, fade, label = "focus")
        val onA by androidx.compose.animation.core.animateFloatAsState(if (on && !focused) 1f else 0f, fade, label = "on")
        // The text and glyph colour switches outright (animating it recomposed every tile's content each
        // frame); under the fading fill the switch isn't visible.
        val fg = if (focused) palette.onFocusFill else if (on) Blue else palette.primary
        Box(
            Modifier
                .fillMaxSize()
                // The glass stays put and the focus or "on" fill draws over it: swapping modifiers made a
                // fresh glass node on every focus change, which could draw a frame before knowing where it
                // was (a tile that had just lost focus showed up see-through).
                .glass(LocalBackdrop.current, shape, CC_GLASS)
                .drawWithCache {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    onDrawBehind {
                        if (onA > 0f) drawOutline(outline, Color.White, alpha = onA)
                        if (focusA > 0f) drawOutline(outline, palette.focusFill, alpha = focusA)
                    }
                },
            contentAlignment = Alignment.Center,
        ) { content(fg) }
    }
}

