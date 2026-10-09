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
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.GlassMatch
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.invalidateDraw
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
    /** The bubble's bottom edge follows progress to this power: low in the first frames, so the capsule widens before it drops. */
    private const val DROP = 2.5f
    /** The bubble (the gaps between the tiles) is fully there until the panel is this open, then fades to leave the gaps clear. */
    private const val BUBBLE_HOLD = 0.8f
    /** How far the bubble's height squeezes while collapsing into the pill (to 96%). */
    private const val SQUEEZE = 0.04f

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * The bubble's progress (0..1, a little over 1 in the bounce) for the spring's value [e]: the value itself,
     * and closing runs the same path back, never past [from] (where the close began, Back mid-open), so the
     * two directions agree at the turn and nothing jumps. The tiles are clipped to this bubble.
     */
    fun bubble(e: Float, closing: Boolean, from: Float = 1f): Float =
        if (closing) minOf(from, e).coerceAtLeast(0f) else e.coerceAtLeast(0f)

    /**
     * The bubble's bounds at progress [t]: the pill's capsule stretches into the panel (past 1 it overshoots a
     * little; it never goes negative). Its sides and top travel linearly, but its bottom edge lags ([DROP]):
     * widening first keeps the first frames the pill's capsule, never a round disc. [squeeze] scales the
     * height about the centre.
     */
    fun rect(t: Float, pill: androidx.compose.ui.geometry.Rect, panel: androidx.compose.ui.geometry.Rect, squeeze: Float = 1f): androidx.compose.ui.geometry.Rect {
        val p = t.coerceAtLeast(0f)
        val left = lerp(pill.left, panel.left, p); val right = lerp(pill.right, panel.right, p)
        val top = lerp(pill.top, panel.top, p)
        val bottom = lerp(pill.bottom, panel.bottom, Math.pow(p.toDouble(), DROP.toDouble()).toFloat())
        val cy = (top + bottom) / 2; val h = ((bottom - top) * squeeze).coerceAtLeast(0f)
        return androidx.compose.ui.geometry.Rect(left, cy - h / 2, right, cy + h / 2)
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
     * The bubble's own opacity at progress [b]. The tiles are clipped to the bubble, so the bubble is what fills
     * the gaps between them: solid until the panel is nearly open, then it fades to leave the gaps clear.
     */
    fun bubbleAlpha(b: Float) = 1f - ((b - BUBBLE_HOLD) / (1f - BUBBLE_HOLD)).coerceIn(0f, 1f)
}

/** One outline: the bubble at progress [b] between the pill and the panel (see [CcMorph]). */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCcBubble(
    b: Float, squeeze: Float, pill: androidx.compose.ui.geometry.Rect, panel: androidx.compose.ui.geometry.Rect,
    brush: androidx.compose.ui.graphics.Brush, alpha: Float, tileRadius: Float,
) {
    val r = CcMorph.rect(b, pill, panel, squeeze)
    drawRoundRect(brush, topLeft = r.topLeft, size = r.size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(CcMorph.radius(b, r, tileRadius)), alpha = alpha)
}

/** Room around the scrolling tiles so a focused tile's growth and shadow aren't clipped. */
private val CC_BLEED = 16.dp

/**
 * Control Center's one material: a small bitmap of the scene behind the panel (the backdrop's clear texture,
 * blurred, with a faint tint and the wash's dim baked in), baked with the backdrop itself ([GlassMatch.ccSheet],
 * off the main thread), so an open only picks it up. Every tile, the page discs, the Now Playing card and the
 * bubble draw their part of it: one draw each, mapped by window position, so they read as one sheet of glass and
 * never sample anything per frame.
 */
internal object CcMaterial {
    /** With no baked sheet (no backdrop yet): one fixed smoky colour. */
    val fallback = Color(0xFF23262D)
    /** The rim every tile keeps so it has an edge: a single thin stroke, lit from the top-left. */
    fun rimBrush(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Brush = androidx.compose.ui.graphics.Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.55f),
        0.3f to Color.White.copy(alpha = 0.12f),
        0.7f to Color.White.copy(alpha = 0.06f),
        1f to Color.White.copy(alpha = 0.28f),
        start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(size.width, size.height),
    )

    /**
     * Tests only: the sheet of the latest open (so they can compare the tiles with it). Never set unless a test
     * turns [recordLast] on, so release builds keep no extra reference to a sheet; cleared when the open closes.
     */
    @androidx.annotation.VisibleForTesting @Volatile var last: CcSheet? = null
    @androidx.annotation.VisibleForTesting @Volatile var recordLast = false
}

/**
 * The material for one open: the backdrop's baked sheet ([baked], owned by the backdrop) placed on a root of
 * [root] pixels, or, with none, the one [flat] colour. Making one does no pixel work.
 */
@androidx.compose.runtime.Stable
internal class CcSheet(val baked: GlassMatch.PanelSheet?, val root: androidx.compose.ui.unit.IntSize) {
    val bitmap: android.graphics.Bitmap? get() = baked?.bitmap
    /** The sheet's pixels (tests only: kept when [GlassMatch.keepPixels] is on). */
    val pixels: GlassMatch.Sheet? get() = baked?.pixels
    val flat: Color get() = CcMaterial.fallback
    /** The window rectangle the sheet covers. */
    val panel: androidx.compose.ui.geometry.Rect = baked?.let {
        androidx.compose.ui.geometry.Rect(it.left * root.width, it.top * root.height, it.right * root.width, it.bottom * root.height)
    } ?: androidx.compose.ui.geometry.Rect.Zero

    private fun shader(tx: Float, ty: Float): android.graphics.BitmapShader? = bitmap?.takeIf { !panel.isEmpty }?.let {
        android.graphics.BitmapShader(it, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setScale(panel.width / it.width, panel.height / it.height); postTranslate(panel.left + tx, panel.top + ty) })
        }
    }
    /** The sheet in window coordinates, for the bubble. */
    val brush: androidx.compose.ui.graphics.Brush by lazy { shader(0f, 0f)?.let { androidx.compose.ui.graphics.ShaderBrush(it) } ?: androidx.compose.ui.graphics.SolidColor(flat) }
    /** The sheet for a surface whose top-left is at [origin] in the window (its own coordinates start there). */
    fun brushAt(origin: androidx.compose.ui.geometry.Offset, height: Float): androidx.compose.ui.graphics.Brush {
        val sheet = shader(-origin.x, -origin.y) ?: return androidx.compose.ui.graphics.SolidColor(flat)
        // The bevel rides in the same shader (still one draw): a soft sheen along the top, a faint inner shadow along
        // the bottom; the middle is the sheet untouched, so labels and the one-material colour are not lit up.
        val bevel = android.graphics.LinearGradient(
            0f, 0f, 0f, height,
            intArrayOf(Color.White.copy(alpha = 0.10f).toArgb(), 0, 0, Color.Black.copy(alpha = 0.12f).toArgb()),
            floatArrayOf(0f, 0.12f, 0.85f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        return androidx.compose.ui.graphics.ShaderBrush(android.graphics.ComposeShader(sheet, bevel, android.graphics.PorterDuff.Mode.SRC_OVER))
    }
}

/**
 * Holds the open's [CcSheet]. Provided once (it never changes identity), and read only while drawing, so taking or
 * dropping a sheet redraws the surfaces instead of recomposing the whole panel.
 */
@androidx.compose.runtime.Stable
internal class CcSheetRef {
    var sheet by androidx.compose.runtime.mutableStateOf<CcSheet?>(null)
}

internal val LocalCcSheet = androidx.compose.runtime.staticCompositionLocalOf<CcSheetRef?> { null }

/** A Control Center surface: the open's sheet in [shape] with the one thin rim; one draw, no texture of its own. */
internal fun Modifier.ccSurface(shape: Shape, sheet: CcSheetRef?): Modifier = this then CcSurfaceElement(shape, sheet)

private data class CcSurfaceElement(val shape: Shape, val sheet: CcSheetRef?) : androidx.compose.ui.node.ModifierNodeElement<CcSurfaceNode>() {
    override fun create() = CcSurfaceNode(shape, sheet)
    override fun update(node: CcSurfaceNode) { node.shape = shape; node.ref = sheet; node.reset(); node.invalidateDraw() }
}

private class CcSurfaceNode(var shape: Shape, var ref: CcSheetRef?) : Modifier.Node(), androidx.compose.ui.node.DrawModifierNode, androidx.compose.ui.node.GlobalPositionAwareModifierNode {
    private var origin: androidx.compose.ui.geometry.Offset? = null
    private var cachedSize = androidx.compose.ui.geometry.Size.Unspecified
    private var outline: androidx.compose.ui.graphics.Outline? = null
    private var brush: androidx.compose.ui.graphics.Brush? = null
    private var brushFor: CcSheet? = null
    private var rim: androidx.compose.ui.graphics.Brush? = null

    fun reset() { brush = null; outline = null; rim = null }

    override fun onGloballyPositioned(coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
        val p = coordinates.positionInWindow()
        // A focused tile's 1.02 lift moves its corner a couple of pixels each frame of the scale; the sheet's cells
        // are ~12 px and blurred, so that is not worth new shaders every frame. Only a real move re-maps it.
        val o = origin
        if (o == null || kotlin.math.abs(p.x - o.x) > MOVE_PX || kotlin.math.abs(p.y - o.y) > MOVE_PX) { origin = p; brush = null; invalidateDraw() }
    }

    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        if (cachedSize != size || outline == null) { cachedSize = size; outline = shape.createOutline(size, layoutDirection, this); brush = null; rim = null }
        val o = outline ?: return drawContent()
        // Read while drawing: a new sheet redraws this surface (and only that).
        val sheet = ref?.sheet
        if (sheet !== brushFor) { brushFor = sheet; brush = null }
        val b = brush ?: (sheet?.brushAt(origin ?: androidx.compose.ui.geometry.Offset.Zero, size.height) ?: androidx.compose.ui.graphics.SolidColor(CcMaterial.fallback)).also { brush = it }
        drawOutline(o, b)
        // Liquid Glass's edge, one stroke: bright at the top-left, faint at the bottom-right, almost nothing between.
        val r = rim ?: CcMaterial.rimBrush(size).also { rim = it }
        drawOutline(o, r, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
        drawContent()
    }

    private companion object { const val MOVE_PX = 4f }
}

/** The bubble as a clip for the panel: the tiles show through it as it grows (nothing fades in). */
private data class CcBubbleShape(val rect: androidx.compose.ui.geometry.Rect, val radius: Float) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density) =
        androidx.compose.ui.graphics.Outline.Rounded(androidx.compose.ui.geometry.RoundRect(rect, androidx.compose.ui.geometry.CornerRadius(radius)))
}

/** Where the bubble is now (window pixels), its progress and its corner radius. */
private class CcBubble(val rect: androidx.compose.ui.geometry.Rect, val b: Float, val radius: Float)

/** Set when the open lands: a focus fill that appears while Control Center is still opening snaps instead of fading (a tile is never half-white). */
internal class CcPhase { var landed = false }
internal val LocalCcPhase = androidx.compose.runtime.staticCompositionLocalOf { CcPhase() }

/** How much Control Center mutes the screen behind it (tvOS 27 dims rather than blurs); its sheet is baked muted by the same amount. */
internal const val CC_DIM_ALPHA = GlassMatch.CC_DIM
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

    // Grows out of the status pill: its capsule stretches into the panel like a drop of liquid, and the tiles,
    // at full opacity, are revealed by clipping to it. Closing runs the same path back. One Animatable drives
    // the bubble, its clip and the dim in both directions, so Back mid-open reverses from wherever it is. The
    // tiles' layout never changes, and the panel and its tiles are one layer (no per-row translucent layers).
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    val exiting = LocalOverlayExiting.current
    val reduceMotion = dev.glasslauncher.ui.LocalUiPrefs.current.reduceMotion
    // Where the close began (read once, here, so it isn't a per-frame dependency): the bubble turns from there.
    val closeFrom = remember(exiting) { if (exiting) androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { enter.value }.coerceIn(0.01f, 1f) else 1f }
    val phase = remember { CcPhase() }
    val glassState = LocalBackdrop.current
    var panel by remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // The material of this open: the backdrop's own sheet, baked with the backdrop (it covers the right half of
    // the screen, so every page and text size), taken as the open starts and never re-keyed by a later backdrop
    // swap: a tile never changes colour once it has appeared. Nothing is baked or waited for here.
    val sheetRef = remember { CcSheetRef() }
    val pill = ControlCenterWindow.pillBounds
    val exitingNow = androidx.compose.runtime.rememberUpdatedState(exiting)
    fun takeSheet() {
        val root = glassState.rootSize
        sheetRef.sheet = if (root == androidx.compose.ui.unit.IntSize.Zero) null else CcSheet(glassState.backdrop?.ccSheet, root)
        if (CcMaterial.recordLast) CcMaterial.last = sheetRef.sheet
    }
    // The window keeps this composition across opens, so everything per open starts here, not in remember.
    LaunchedEffect(exiting) {
        if (!exiting) {
            phase.landed = false
            // Re-opening during a close that is still visible keeps the sheet it has; otherwise take the backdrop's.
            if (sheetRef.sheet == null || enter.value < 0.02f) takeSheet()
        }
        // Reduce Motion: no growing bubble, a plain fade (a bounce would pulse the opacity).
        if (reduceMotion) enter.animateTo(if (exiting) 0f else 1f, dev.glasslauncher.ui.Motion.overlay())
        else if (exiting) enter.animateTo(0f, CcMorph.closeSpring)
        else enter.animateTo(1f, CcMorph.openSpring)
        phase.landed = true
        // Closed: let go of this open's sheet (the cached composition would otherwise hold it until the next open).
        if (exiting && enter.value < 0.02f) { sheetRef.sheet = null; CcMaterial.last = null }
    }
    // A backdrop (or the window's size) that arrives before the open has visibly started is still taken; once the
    // bubble is under way the open keeps what it has.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { glassState.backdrop?.ccSheet to glassState.rootSize }.collect { (offered, root) ->
            val have = sheetRef.sheet
            if (!exitingNow.value && enter.value < 0.02f && offered != null && (have?.baked !== offered || have.root != root)) takeSheet()
        }
    }
    // The bubble's bounds now: from the very first frame (before the panel is measured, it's simply the pill).
    fun androidx.compose.ui.unit.Density.bubbleNow(): CcBubble? {
        val from = pill ?: panel?.let { androidx.compose.ui.geometry.Rect(it.right - 107.dp.toPx(), it.top, it.right, it.top + 32.dp.toPx()) } ?: return null
        val b = CcMorph.bubble(enter.value, exiting, closeFrom)
        val r = CcMorph.rect(b, from, panel ?: from, CcMorph.squeeze(b, exiting, closeFrom))
        return CcBubble(r, b, CcMorph.radius(b, r, 26.dp.toPx()))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalCcSizes provides sz, LocalCcSheet provides sheetRef, LocalCcPhase provides phase) {
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            // tvOS 27 mutes what's behind with a dark wash rather than blurring it: one translucent rect whose alpha
            // follows the spring (drawn, not a layer with alpha, which would be a full-screen offscreen pass), and
            // over another app (an overlay window) the system composites it without redrawing anything.
            val e = enter.value.coerceIn(0f, 1f)
            if (e > 0f) drawRect(CC_DIM, alpha = e)
            // The bubble: the pill's capsule on the first frame and the panel's outline by the end, drawn from the
            // same sheet as the tiles. It fills the gaps between the clipped-in tiles, then fades to clear them.
            // Reduce Motion: no growing bubble, Control Center simply fades in.
            if (reduceMotion) return@Canvas
            val material = sheetRef.sheet ?: return@Canvas
            val bubble = bubbleNow() ?: return@Canvas
            val a = CcMorph.bubbleAlpha(bubble.b)
            if (a <= 0f) return@Canvas
            val from = pill ?: panel?.let { androidx.compose.ui.geometry.Rect(it.right - 107.dp.toPx(), it.top, it.right, it.top + 32.dp.toPx()) } ?: return@Canvas
            drawCcBubble(bubble.b, CcMorph.squeeze(bubble.b, exiting, closeFrom), from, panel ?: from, material.brush, a, 26.dp.toPx())
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .onGloballyPositioned { panel = it.boundsInWindow() }
                // One layer for the panel and every tile: clipped to the growing bubble (nothing fades), or with
                // Reduce Motion a plain fade of the whole panel.
                .graphicsLayer {
                    val origin = panel
                    if (reduceMotion) { alpha = enter.value.coerceIn(0f, 1f); return@graphicsLayer }
                    val bubble = bubbleNow()
                    if (origin == null || bubble == null) { alpha = 0f; return@graphicsLayer }
                    if (bubble.b < 1f || exiting) {
                        clip = true
                        shape = CcBubbleShape(bubble.rect.translate(-origin.topLeft), bubble.radius)
                    }
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
                Box(Modifier) { AlexaPage(sz, closeAll) }
            } else {
            Row(horizontalArrangement = Arrangement.spacedBy(sz.gap), modifier = Modifier) {
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
            if (shown("launcher")) CcTile("Launcher Settings", null, Shapes.pill, sz.column, sz.pill, modifier = Modifier, onClick = { closeAll(); open(Overlay.Settings) }) { fg ->
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
                        Row(horizontalArrangement = Arrangement.spacedBy(sz.gap), modifier = Modifier) { row.forEach { it() } }
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
                modifier = Modifier.padding(start = 4.dp).testTag("cc-caption"),
            )
            }
            }
            }
            // What's playing, with controls (any app with a media session: Spotify, Amazon Music, YouTube…).
            np?.let { Box(Modifier) { dev.glasslauncher.widgets.NowPlayingCard(it, sz.column, cfg.textScale) } }
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
            Modifier.fillMaxSize().ccSurface(CircleShape, LocalCcSheet.current)
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
        // The first focus lands while Control Center is still opening: the white appears at once, so the tile
        // is never revealed half-grey (later focus moves cross-fade).
        val phase = LocalCcPhase.current
        val focusA by androidx.compose.animation.core.animateFloatAsState(if (focused) 1f else 0f, if (phase.landed) fade else androidx.compose.animation.core.snap(), label = "focus")
        val onA by androidx.compose.animation.core.animateFloatAsState(if (on && !focused) 1f else 0f, fade, label = "on")
        // The text and glyph colour switches outright (animating it recomposed every tile's content each
        // frame); under the fading fill the switch isn't visible.
        val fg = if (focused) palette.onFocusFill else if (on) Blue else palette.primary
        Box(
            Modifier
                .fillMaxSize()
                // The shared fill stays put and the focus or "on" fill draws over it (swapping modifiers made a
                // fresh node on every focus change, which could draw a frame before it knew where it was).
                .ccSurface(shape, LocalCcSheet.current)
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

