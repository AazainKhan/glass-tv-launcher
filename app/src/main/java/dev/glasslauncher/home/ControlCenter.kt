package dev.glasslauncher.home

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.currentValueOf
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
 * Tile sizes grow with the text size (Settings › Display & Text), so labels keep fitting instead of
 * being cut off: at Larger the Wi-Fi network name was truncated in a fixed 128 dp pill.
 */
internal class CcSizes(k: Float) {
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

private val LocalCcPageIconSize = androidx.compose.runtime.staticCompositionLocalOf { 40.dp }

private val LocalCcSizes = androidx.compose.runtime.staticCompositionLocalOf { CcSizes(1f) }

/** The close's progress below which the capsule is the pill again and Home's own pill is shown under it. */
private const val PILL_BACK_AT = 0.02f
/** Home's pill's focused scale (StatusPill's focus tile), so a focused pill's copy matches it. */
private const val PILL_FOCUS_SCALE = 1.08f

/**
 * Control Center's open/close (P49): Home's status pill splits into two liquid drops that become the page icons,
 * and every control grows out of the nearer drop; closing runs it back into the pill. One progress value p
 * (0 = the pill, 1 = open) drives everything, from a spring without bounce, and every stage is a pure function of
 * it, so Back mid-way reverses from wherever it is. Pure, so it's unit-tested.
 *
 * This departs from measured tvOS on purpose (its controls never split or merge, liquid-glass-motion §9): the user
 * asked for it, and it is built the way that reference allows, one baked fill per shape, no metaball shader.
 */
object CcMorph {
    /** Opening: crisp, no bounce (tvOS overlays are ζ ≈ 0.9), about 350–400 ms with the tiles' stagger. */
    val openSpring = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.9f, stiffness = 260f, visibilityThreshold = 0.001f)
    /** Closing: critically damped and quicker, about 250 ms to the pill. */
    val closeSpring = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 450f, visibilityThreshold = 0.001f)
    /**
     * When the close has visibly landed (the spring is within 1% of the pill; the rest is an invisible tail).
     * The overlay window / in-launcher overlay is removed then, so it stops taking input (a test ties it to the spring).
     */
    const val CLOSE_MS = 320

    /** The pill has split into its two drops, and they have reached the page icons' spots, by this much of p. */
    const val SPLIT_END = 0.35f
    /** The neck between the two drops has thinned to nothing by this much of the split. */
    private const val NECK_END = 0.7f
    /** The pill has faded out by this much of the open (tvOS: by about 30%). */
    private const val PILL_GONE = 0.3f
    /** The page icons' glyphs come in over the end of the split, from here to its end (quick: at most two frames half-drawn). */
    private const val GLYPH_FROM = 0.75f
    /** The first controls start to grow at this p; the farthest from their drop start [TILE_SPREAD] later. */
    private const val TILE_FROM = 0.2f
    private const val TILE_SPREAD = 0.25f
    /** The header (time, date, weather) grows from the pill's clock over this much of p, and shows by [HEADER_SHOWN]. */
    private const val HEADER_END = 0.6f
    private const val HEADER_SHOWN = 0.25f
    /** A lone drop (no page icons) is absorbed into the controls between these values of p. */
    private const val LONE_GONE_FROM = 0.35f
    private const val LONE_GONE_TO = 0.8f

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

    /**
     * The progress (0..1) for the spring's value [e]: the value itself, and closing runs the same path back, never
     * past [from] (where the close began, Back mid-open), so the two directions agree at the turn and nothing jumps.
     */
    fun bubble(e: Float, closing: Boolean, from: Float = 1f): Float =
        if (closing) minOf(from, e).coerceIn(0f, 1f) else e.coerceIn(0f, 1f)

    /** How far the pill has split into its drops (0..1) at progress [p]: eased out, so it moves from the first frame. */
    fun split(p: Float): Float { val x = (p / SPLIT_END).coerceIn(0f, 1f); return 1f - (1f - x) * (1f - x) }

    /** Home's pill (drawn by Control Center at its spot) at progress [p]: whole at 0, gone by [PILL_GONE]. */
    fun pillAlpha(p: Float) = 1f - (p / PILL_GONE).coerceIn(0f, 1f)

    /** The page icons' glyphs at progress [p]: in at the end of the split. */
    fun glyphAlpha(p: Float) = ((p / SPLIT_END - GLYPH_FROM) / (1f - GLYPH_FROM)).coerceIn(0f, 1f)

    /** The header's opacity at progress [p]: it appears early, while it is still small at the pill's clock. */
    fun headerAlpha(p: Float) = (p / HEADER_SHOWN).coerceIn(0f, 1f)

    /** How far the header has travelled from the pill's clock to its place at [p]. */
    fun header(p: Float) = smooth(p / HEADER_END)

    /**
     * The pill's two end circles, where the drops start: the left becomes the Controls icon, the right the Alexa
     * icon. Their centres and their diameter (the pill's height).
     */
    fun pillEnds(pill: androidx.compose.ui.geometry.Rect): Pair<androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset> {
        val r = pill.height / 2
        return androidx.compose.ui.geometry.Offset(pill.left + r, pill.center.y) to androidx.compose.ui.geometry.Offset(pill.right - r, pill.center.y)
    }

    /** A drop on its way from [from] (a circle [fromD] across) to the icon slot [to] at split [q]: its centre and diameter. */
    fun drop(q: Float, from: androidx.compose.ui.geometry.Offset, fromD: Float, to: androidx.compose.ui.geometry.Rect): Pair<androidx.compose.ui.geometry.Offset, Float> =
        androidx.compose.ui.geometry.Offset(lerp(from.x, to.center.x, q), lerp(from.y, to.center.y, q)) to lerp(fromD, minOf(to.width, to.height), q)

    /**
     * The neck between the two drops at split [q], as its half-heights where it meets the drops and at its waist
     * (both [r], the drops' radius, at the start: the pill's capsule). Null once it has parted.
     */
    fun neck(q: Float, r: Float): Pair<Float, Float>? {
        val k = (q / NECK_END).coerceIn(0f, 1f)
        if (k >= 1f) return null
        val ends = r * kotlin.math.sqrt(1f - k)
        val waist = r * (1f - k) * (1f - k)
        return ends to waist
    }

    /** When a control [distance] px from its drop starts to grow (p), with [farthest] the farthest any control is. */
    fun tileStart(distance: Float, farthest: Float) = TILE_FROM + TILE_SPREAD * (distance / farthest.coerceAtLeast(1f)).coerceIn(0f, 1f)

    /** A control's own progress (0..1) at [p], given when it starts. */
    fun tile(p: Float, start: Float) = ((p - start) / (1f - start)).coerceIn(0f, 1f)

    /** A lone drop's radius factor (1..0) as the controls absorb it. */
    fun loneDrop(p: Float) = 1f - smooth((p - LONE_GONE_FROM) / (LONE_GONE_TO - LONE_GONE_FROM))

    /**
     * Where a shape whose slot is [slot] is drawn at its own progress [e], growing out of a drop at [from] that is
     * [fromD] across: uniformly scaled about its centre (from the drop's size) and moved from the drop to the slot.
     */
    fun emerge(e: Float, slot: androidx.compose.ui.geometry.Rect, from: androidx.compose.ui.geometry.Offset, fromD: Float): CcEmergeFrame {
        val s0 = (fromD / minOf(slot.width, slot.height).coerceAtLeast(1f)).coerceAtMost(1f)
        val s = lerp(s0, 1f, e)
        return CcEmergeFrame(s, lerp(from.x - slot.center.x, 0f, e), lerp(from.y - slot.center.y, 0f, e), e)
    }
}

/**
 * Where one Control Center shape is drawn this frame: scaled by [scale] about its slot's centre, moved by
 * ([dx], [dy]) px, and [round] (0..1) of the way from a capsule to its own corners.
 */
data class CcEmergeFrame(val scale: Float, val dx: Float, val dy: Float, val round: Float) {
    val still: Boolean get() = scale == 1f && dx == 0f && dy == 0f && round >= 1f
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
    /** The same, in light appearance. */
    val fallbackLight = Color(0xFFD6D9E3)
    /** The rim every tile keeps so it has an edge: a single thin stroke, lit from the top-left. */
    fun rimBrush(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Brush = androidx.compose.ui.graphics.Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.42f),
        0.35f to Color.White.copy(alpha = 0.10f),
        0.7f to Color.White.copy(alpha = 0.05f),
        1f to Color.White.copy(alpha = 0.22f),
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
internal class CcSheet(val baked: GlassMatch.PanelSheet?, val root: androidx.compose.ui.unit.IntSize, val light: Boolean = false) {
    val bitmap: android.graphics.Bitmap? get() = baked?.bitmap
    /** The sheet's pixels (tests only: kept when [GlassMatch.keepPixels] is on). */
    val pixels: GlassMatch.Sheet? get() = baked?.pixels
    /** The one colour used when there is no baked sheet. */
    private val flat: Color get() = if (light) CcMaterial.fallbackLight else CcMaterial.fallback
    /** The window rectangle the sheet covers. */
    val panel: androidx.compose.ui.geometry.Rect = baked?.let {
        androidx.compose.ui.geometry.Rect(it.left * root.width, it.top * root.height, it.right * root.width, it.bottom * root.height)
    } ?: androidx.compose.ui.geometry.Rect.Zero

    private fun shader(tx: Float, ty: Float, then: (android.graphics.Matrix.() -> Unit)? = null): android.graphics.BitmapShader? = bitmap?.takeIf { !panel.isEmpty }?.let {
        android.graphics.BitmapShader(it, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setScale(panel.width / it.width, panel.height / it.height); postTranslate(panel.left + tx, panel.top + ty); then?.invoke(this) })
        }
    }
    /**
     * How light the sheet is (0..1, mean luminance of its small bitmap): over a light scene (a white app) a black
     * bevel reads as grey halos around the tiles (P38), so the shade follows it.
     */
    private val sheetLuma: Float get() = baked?.luma ?: 0f
    /** The sheet in window coordinates, for the bubble. */
    /** The sheet's one flat colour, for a software canvas, which can't draw the (hardware) sheet bitmap. */
    val flatBrush: androidx.compose.ui.graphics.Brush get() = androidx.compose.ui.graphics.SolidColor(flat)
    val brush: androidx.compose.ui.graphics.Brush by lazy { shader(0f, 0f)?.let { androidx.compose.ui.graphics.ShaderBrush(it) } ?: androidx.compose.ui.graphics.SolidColor(flat) }
    /** The sheet for a surface whose top-left is at [origin] in the window (its own coordinates start there). */
    fun brushAt(origin: androidx.compose.ui.geometry.Offset, height: Float, moved: CcEmergeFrame? = null, slotCentre: androidx.compose.ui.geometry.Offset? = null): androidx.compose.ui.graphics.Brush {
        // A shape drawn moved and scaled (the open's choreography, [CcEmerge]) maps the sheet back through that
        // transform, so its glass shows what is behind where it is now, not a picture it carries along.
        val sheet = (if (moved == null || slotCentre == null) shader(-origin.x, -origin.y) else shader(0f, 0f) {
            postTranslate(-(slotCentre.x + moved.dx), -(slotCentre.y + moved.dy))
            postScale(1f / moved.scale.coerceAtLeast(0.01f), 1f / moved.scale.coerceAtLeast(0.01f))
            postTranslate(slotCentre.x - origin.x, slotCentre.y - origin.y)
        }) ?: return androidx.compose.ui.graphics.SolidColor(flat)
        // The bevel rides in the same shader (still one draw, four stops: P71 showed the stop count is paid per
        // pixel): a sheen along the top edge and an inner shadow that starts at 70% and deepens to the bottom, so a
        // tile reads as a convex bubble. The sheen ends above 15% of the height on purpose: the tests (and the
        // one-material rule) read a tile's colour at 15-22% of its height, which stays "the sheet" there.
        val bevel = android.graphics.LinearGradient(
            0f, 0f, 0f, height,
            intArrayOf(Color.White.copy(alpha = 0.14f).toArgb(), 0, 0, bevelShade(sheetLuma).toArgb()),
            floatArrayOf(0f, 0.14f, 0.70f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        return androidx.compose.ui.graphics.ShaderBrush(android.graphics.ComposeShader(sheet, bevel, android.graphics.PorterDuff.Mode.SRC_OVER))
    }
}

/**
 * Whether this draw goes to the GPU. Control Center's glass is a hardware bitmap, which a software canvas (a picture
 * of the screen taken by drawing it in software) refuses with an exception; Glass crashed that way (P66).
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.hardwareCanvas(): Boolean =
    drawContext.canvas.nativeCanvas.isHardwareAccelerated

/**
 * The colour of the tiles' bottom bevel over a sheet of mean luminance [luma]: a faint black shade over a dark or
 * mid scene, and over a light one (above [LIGHT_SHEET]) a cool, lighter shade at a third of the strength, so it
 * never reads as a grey halo under the tile (P38).
 */
internal fun bevelShade(luma: Float): Color =
    if (luma > LIGHT_SHEET) Color(0xFF6B7391).copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.12f)

/** A sheet lighter than this (mean luminance) is a light scene for the bevel. */
internal const val LIGHT_SHEET = 0.62f

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

private class CcSurfaceNode(var shape: Shape, var ref: CcSheetRef?) : Modifier.Node(), androidx.compose.ui.node.DrawModifierNode, androidx.compose.ui.node.GlobalPositionAwareModifierNode,
    androidx.compose.ui.node.CompositionLocalConsumerModifierNode {
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
        val rest = outline ?: return drawContent()
        // Read while drawing: a new sheet redraws this surface (and only that).
        val sheet = ref?.sheet
        if (sheet !== brushFor) { brushFor = sheet; brush = null }
        // While the open's choreography moves this shape, its glass is mapped through the move every frame (and a
        // rounded tile starts as a capsule); at rest the cached brush and outline are used.
        val emerge = currentValueOf(LocalCcEmerge)
        val moved = emerge?.now()
        val o = if (moved != null && moved.round < 1f) ccRounding(rest, size, moved.round) else rest
        val b = if (moved != null && sheet != null) sheet.brushAt(origin ?: androidx.compose.ui.geometry.Offset.Zero, size.height, moved, emerge.slot?.center)
            else brush ?: (sheet?.brushAt(origin ?: androidx.compose.ui.geometry.Offset.Zero, size.height) ?: androidx.compose.ui.graphics.SolidColor(CcMaterial.fallback)).also { brush = it }
        // A software canvas (a snapshot of the screen) can't draw the sheet, a hardware bitmap: the flat colour then.
        drawOutline(o, if (hardwareCanvas()) b else sheet?.flatBrush ?: androidx.compose.ui.graphics.SolidColor(CcMaterial.fallback))
        // Liquid Glass's edge, one stroke: bright at the top-left, faint at the bottom-right, almost nothing between.
        val r = rim ?: CcMaterial.rimBrush(size).also { rim = it }
        drawOutline(o, r, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
        drawContent()
    }

    private companion object { const val MOVE_PX = 4f }
}

/**
 * [rest] (a shape's own outline) part-way from a capsule: [round] 0 is a capsule (half the short side), 1 its own
 * corners. Only rounded rectangles change; circles and capsules already are one.
 */
internal fun ccRounding(rest: androidx.compose.ui.graphics.Outline, size: androidx.compose.ui.geometry.Size, round: Float): androidx.compose.ui.graphics.Outline {
    val rr = (rest as? androidx.compose.ui.graphics.Outline.Rounded)?.roundRect ?: return rest
    val capsule = minOf(size.width, size.height) / 2f
    val own = rr.topLeftCornerRadius.x
    if (own >= capsule) return rest
    val k = round.coerceIn(0f, 1f)
    val r = capsule + (own - capsule) * k * k
    return androidx.compose.ui.graphics.Outline.Rounded(androidx.compose.ui.geometry.RoundRect(rr.left, rr.top, rr.right, rr.bottom, androidx.compose.ui.geometry.CornerRadius(r)))
}

/** Set when the open lands: a focus fill that appears while Control Center is still opening snaps instead of fading (a tile is never half-white). */
internal class CcPhase { var landed = false }
internal val LocalCcPhase = androidx.compose.runtime.staticCompositionLocalOf { CcPhase() }

/** How much Control Center mutes the screen behind it (tvOS 27 dims rather than blurs); its sheet is baked muted by the same amount. */
internal const val CC_DIM_ALPHA = GlassMatch.CC_DIM
/** The time, date and weather share one opacity, slightly muted, as tvOS's header. */
private const val HEADER_ALPHA = 0.85f
private val CC_DIM_COLOR = Color.Black.copy(alpha = CC_DIM_ALPHA)

/** Control Center tiles that can be turned off in Settings › Control Center (id to label). */
val CONTROL_CENTER_TILES = listOf(
    "alexa" to "Alexa Page", "wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "launcher" to "Launcher Settings", "airplay" to "AirPlay",
    "controllers" to "Game Controllers", "appearance" to "Theme", "screensaver" to "Screen Saver",
    "switcher" to "App Switcher", "performance" to "Performance", "sleep_timer" to "Sleep Timer", "vpn" to "VPN",
    "cleanup" to "Clean Up", "restart" to "Restart",
)

/**
 * SYS-01, laid out like tvOS 27 Control Center: the time over a right-hand column of glass tiles.
 * A large Settings tile for the TV's own settings (white when focused, focused first), two-line pills
 * for Wi-Fi, Bluetooth and the launcher's settings, then round buttons for game controllers, the theme,
 * the screen saver, the app switcher and AirPlay (when PhairPlay is installed). Glass tiles
 * take their tint from the content behind them; a pill's icon sits in a white disc while it's on.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ControlCenter(edit: ((LauncherConfig) -> LauncherConfig) -> Unit, cfg: LauncherConfig, active: Boolean, open: (Overlay) -> Unit, closeAll: () -> Unit) {
    // One look in either theme, like the pill and tray: white text and glyphs on clear glass over the
    // dimmed screen (tvOS 27 Control Center isn't themed light or dark).
    val outer = LocalPalette.current
    val palette = remember(outer.highContrast) { dev.glasslauncher.ui.Palette(light = false, highContrast = outer.highContrast) }
    // Scrolling moves by whole rows (a row is a cell plus a gap), so a half-cut row never sits at the panel's edge.
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { val k = CcSizes(cfg.textScale); (k.round + k.gap).toPx() }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPalette provides palette,
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(rowPx) { CcRowSnap(rowPx) },
    ) {
        ControlCenterBody(edit, cfg, active, open, closeAll)
    }
}

/** Brings a focused tile into view by a whole number of rows, so the tiles always sit on the grid's rows. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private class CcRowSnap(private val row: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset < 0f -> -kotlin.math.ceil(-offset / row) * row
            end > containerSize -> kotlin.math.ceil((end - containerSize) / row) * row
            else -> 0f
        }
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
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // The layout (P83): the stored one, or while edit mode is on the draft being changed (saved when it ends).
    val storedLayout = remember(cfg.ccLayout, cfg.ccHidden) { CcLayout.resolve(cfg.ccLayout, cfg.ccHidden) }
    var draft by remember { androidx.compose.runtime.mutableStateOf<CcLayout?>(null) }
    val layout = draft ?: storedLayout
    val editing = draft != null
    fun shown(id: String) = layout.shown(id)
    // Pages (tvOS 27's icons along the top): 0 = Controls, 1 = Alexa. Opening always shows Controls.
    var pageId by remember(active) { androidx.compose.runtime.mutableStateOf("controls") }
    // While a lifted tile is held over the menu bar (Up from the top row): the page it will drop onto.
    var hoverPage by remember(active) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // The Add a Control gallery (null = closed, "" = the groups, else a group key): the + page icon opens it too.
    var gallery by remember(active) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // The tile lifted in edit mode (the hint bar, outside the scrolling tiles, reads it).
    var lifted by remember(active) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Showcase add-on tiles (debug flag): the Agents card, in place of the grid like the gallery.
    CcShowcase.enabled = cfg.ccDemoTiles || remember(active) { CcShowcase.flag(context) }
    val agents = remember { CcAgentState() }
    var agentCard by remember(active) { androidx.compose.runtime.mutableStateOf(false) }
    // Restart's confirm is drawn here, over the grid (an overlay opened after Control Center closes was lost).
    var restartAsk by remember(active) { androidx.compose.runtime.mutableStateOf(false) }
    // A control that should take focus once it is on screen (a new tile, the one a card or confirm came from).
    var pendingFocus by remember(active) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Page icons show with more than one page, or while editing (the + icon makes a page).
    val alexaPage = layout.pages.size > 1 || editing
    // The focused round button's name, shown under the round buttons (they're icons only).
    var roundLabel by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val dark = cfg.theme != ThemeMode.Light
    val airPlay = dev.glasslauncher.system.AirPlay.rememberState(active)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rooted by produceState(dev.glasslauncher.system.Root.known) { value = dev.glasslauncher.system.Root.available() }
    var fast by androidx.compose.runtime.remember(active) { androidx.compose.runtime.mutableStateOf(dev.glasslauncher.system.RootFeatures.fast(context)) }
    var freed by androidx.compose.runtime.remember(active) { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    // Clean Up's result line ("cleaning…", then what it freed, for a few seconds).
    var cleaned by androidx.compose.runtime.remember(active) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Sleep Timer: the end is stored; the pill re-reads it twice a minute (never per frame).
    var sleepLeft by androidx.compose.runtime.remember(active) { androidx.compose.runtime.mutableStateOf(dev.glasslauncher.system.SleepTimer.remainingMs(context)) }
    LaunchedEffect(active) { while (active) { sleepLeft = dev.glasslauncher.system.SleepTimer.remainingMs(context); kotlinx.coroutines.delay(30_000) } }
    val vpnConnected = dev.glasslauncher.system.VpnControl.rememberConnected(active)
    val vpnApps = remember(active) { dev.glasslauncher.system.VpnControl.apps(context) }

    fun system(go: () -> Boolean) { closeAll(); go() }

    // Grows out of the status pill (P49, [CcMorph]): the pill splits into two drops that become the page icons, and
    // every control grows out of the nearer drop, at full opacity. Closing runs the same path back. One Animatable
    // drives every shape and the dim in both directions, so Back mid-open reverses from wherever it is. The tiles'
    // layout never changes: each is drawn moved and scaled ([ccEmerge]), with nothing clipping the panel.
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ControlCenterWindow.pillCovered = false } }
    val exiting = LocalOverlayExiting.current
    val reduceMotion = dev.glasslauncher.ui.LocalUiPrefs.current.reduceMotion
    // Edit mode saves its draft when Control Center closes, and if the window goes away some other way. (Here, not
    // in a page: a page switch disposes the page's content.)
    fun saveDraft() {
        draft?.let { d -> val l = d.withoutEmptyPages(); edit { c -> c.copy(ccLayout = l.toJson(), ccHidden = emptySet()) } }
        draft = null
    }
    LaunchedEffect(exiting) { if (exiting && draft != null) saveDraft() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { saveDraft() } }
    var liftBase by remember(active) { androidx.compose.runtime.mutableStateOf<CcLayout?>(null) }
    fun commitDraft() { saveDraft(); lifted = null; liftBase = null }
    fun cancelLift() { liftBase?.let { draft = it }; lifted = null; liftBase = null }
    // Back: closes the gallery (a level at a time), drops a lift, else saves and leaves edit mode (Home and
    // closing Control Center save too).
    androidx.compose.runtime.DisposableEffect(editing, lifted, gallery, agentCard, restartAsk) {
        val mine: (() -> Boolean)? = if (editing || agentCard || restartAsk) ({
            when {
                restartAsk -> { restartAsk = false; pendingFocus = "restart" }
                // Back on the permission card is Deny.
                agentCard -> { agents.waiting = 0; agents.result = "Denied"; agentCard = false; pendingFocus = "addon:claude/agents" }
                gallery == "" -> gallery = null
                gallery != null -> gallery = ""
                lifted != null -> cancelLift()
                else -> commitDraft()
            }
            true
        }) else null
        ControlCenterWindow.editBack = mine
        // A page switch disposes the old page's effect after the new one set its own: only clear what is ours.
        onDispose { if (ControlCenterWindow.editBack === mine) ControlCenterWindow.editBack = null }
    }
    val exitingNowEarly = androidx.compose.runtime.rememberUpdatedState(exiting)
    val exitingNow = exitingNowEarly
    // Where the close began (read once, here, so it isn't a per-frame dependency): the bubble turns from there.
    val closeFrom = remember(exiting) { if (exiting) androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { enter.value }.coerceIn(0.01f, 1f) else 1f }
    val phase = remember { CcPhase() }
    val closeFromNow = androidx.compose.runtime.rememberUpdatedState(closeFrom)
    val reduceMotionNow = androidx.compose.runtime.rememberUpdatedState(reduceMotion)
    val motion = remember {
        CcMotion({ CcMorph.bubble(enter.value, exitingNowEarly.value, closeFromNow.value) }, { !reduceMotionNow.value })
    }
    val glassState = LocalBackdrop.current
    var panel by remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // The material of this open: the backdrop's own sheet, baked with the backdrop (it covers the right half of
    // the screen, so every page and text size), taken as the open starts and never re-keyed by a later backdrop
    // swap: a tile never changes colour once it has appeared. Nothing is baked or waited for here.
    val sheetRef = remember { CcSheetRef() }
    val pill = ControlCenterWindow.pillBounds
    fun takeSheet() {
        val root = glassState.rootSize
        sheetRef.sheet = if (root == androidx.compose.ui.unit.IntSize.Zero) null else CcSheet(glassState.backdrop?.ccSheet, root, glassState.backdrop?.isLight == true)
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
        if (exiting) ControlCenterWindow.pillCovered = false
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
    androidx.compose.runtime.CompositionLocalProvider(LocalCcSizes provides sz, LocalCcSheet provides sheetRef, LocalCcPhase provides phase, LocalCcMotion provides motion) {
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            // tvOS 27 mutes what's behind with a dark wash rather than blurring it: one translucent rect whose alpha
            // follows the spring (drawn, not a layer with alpha, which would be a full-screen offscreen pass), and
            // over another app (an overlay window) the system composites it without redrawing anything.
            val e = enter.value.coerceIn(0f, 1f)
            if (e > 0f) drawRect(CC_DIM_COLOR, alpha = e)
            // The open's material from its very first frame: the backdrop is in place before Control Center
            // attaches, but the effect that takes the sheet only runs after a frame has drawn (bare icons, P29).
            if (!exiting && sheetRef.sheet == null) takeSheet()
            // Home's pill goes once this capsule covers it, not before (a frame of neither read as a blink).
            if (!exiting && (reduceMotion || sheetRef.sheet != null)) ControlCenterWindow.pillCovered = true
            // Closing: back at the pill's size (well before the window goes), Home's pill returns under the capsule.
            if (exiting && enter.value < PILL_BACK_AT) ControlCenterWindow.pillCovered = false
            // The drops start as the pill's two ends; over an app there is no pill.
            motion.pill = pill?.takeIf { ControlCenterWindow.homeStarted }
            if (reduceMotion) return@Canvas
            val material = sheetRef.sheet ?: return@Canvas
            drawCcDrops(motion, if (hardwareCanvas()) material.brush else material.flatBrush, alexaPage)
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .onGloballyPositioned {
                    val b = it.boundsInWindow()
                    panel = b
                    motion.panel = b
                    // Where a lone drop settles when there are no page icons: where they would be.
                    val right = b.right - m.chromeInset.value * density - 14 * density
                    val top = b.top + m.chromeInset.value * density
                    motion.loneSpot = androidx.compose.ui.geometry.Rect(right - 40 * density, top, right, top + 40 * density)
                }
                // Reduce Motion: a plain fade of the whole panel. Never the tiles without their material (they would
                // show bare on the open's first frame).
                .graphicsLayer {
                    if (reduceMotion) { alpha = enter.value.coerceIn(0f, 1f); compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; return@graphicsLayer }
                    alpha = if (sheetRef.sheet == null) 0f else 1f
                }
                // Never over the dock: the panel ends where the tray begins, and its tiles scroll inside that.
                .heightIn(max = m.trayTop(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp))
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
                // The time, date and weather grow out of the pill's clock (tvOS slides the time out of the pill).
                val headerEmerge = remember(motion) { CcEmerge(motion, CcRole.Header) }
                Column(Modifier.weight(1f).ccEmerge(headerEmerge).graphicsLayer { alpha = if (motion.enabled) CcMorph.headerAlpha(motion.p) else 1f }) {
                    Text(clock, style = headerStyle.copy(fontFeatureSettings = "tnum"), color = headerColor.copy(alpha = HEADER_ALPHA), maxLines = 1, softWrap = false, modifier = Modifier.testTag("cc-clock"))
                    Text(dev.glasslauncher.widgets.rememberDate(), style = Type.secondary, color = headerColor.copy(alpha = HEADER_ALPHA), maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 6.dp).testTag("cc-date"))
                    // Equal air between the three lines as drawn: the clock's own leading already sits under it,
                    // so the weather line gets the matching gap above (e2e measures the ink).
                    cfg.weather?.let { Box(Modifier.padding(top = 11.dp)) { dev.glasslauncher.widgets.WeatherLabel(it, headerColor.copy(alpha = HEADER_ALPHA), Type.secondary) } }
                }
                // The page icons are the two drops the pill splits into; their glyphs come in as they land.
                // Three or more icons (a page of your own, or the + while editing) are a little smaller so the time keeps its room.
                val iconCount = layout.pages.size + if (editing) 1 else 0
                val iconSize = if (iconCount > 2) 34.dp else 40.dp
                androidx.compose.runtime.CompositionLocalProvider(LocalCcPageIconSize provides iconSize) {
                if (alexaPage) Row(horizontalArrangement = Arrangement.spacedBy(if (iconCount > 2) 6.dp else 10.dp), modifier = Modifier.padding(start = 16.dp)) {
                    // The Alexa app's own icon (loaded once per open); the mic if it isn't installed.
                    val alexaIcon = remember(active) { AlexaIcon.load(context.packageManager, 52)?.asImageBitmap() }
                    // Not editable, so out of the strip (and the walk to +) while editing.
                    layout.pages.filter { !(editing && it.id == "alexa") }.forEachIndexed { i, pg ->
                        val lit = pg.id == (hoverPage ?: pageId)
                        val show = { if (hoverPage == null) pageId = pg.id }
                        // Play/Pause on a page icon in edit mode deletes the page; its tiles go to Controls (nothing is lost).
                        val delete: (() -> Unit)? = if (editing && i > 0 && pg.id != "alexa") ({
                            draft = draft?.let { d -> d.page(pg.id)?.tiles.orEmpty().fold(d) { acc, t -> acc.moveToPage(pg.id, t.id, "controls") }.copy(pages = d.pages.filter { it.id != pg.id }) }
                            if (pageId == pg.id) pageId = "controls"
                        }) else null
                        if (pg.id == "alexa" && alexaIcon != null) PageIcon(ccPageLabel(pg), lit, i, onShow = show, onPlay = delete) {
                            Image(alexaIcon, null, modifier = Modifier.size(26.dp).clip(CircleShape))
                        } else PageIcon(ccPageIcon(pg), ccPageLabel(pg), selected = lit, index = i, onShow = show, onPlay = delete)
                    }
                    if (editing) PageIcon(R.drawable.ic_add, "New page", selected = false, index = minOf(layout.pages.size - 1, 7), onShow = {}, onPlay = null, onSelect = {
                        draft?.let { d ->
                            val id = "page${d.pages.size}-${System.currentTimeMillis() % 100000}"
                            draft = d.copy(pages = d.pages + CcPage(id, "auto", emptyList())); pageId = id
                            gallery = ""
                        }
                    })
                }
                }
            }
            val np by context.app.nowPlaying.state.collectAsStateWithLifecycle()
            if (editing && gallery == null && !agentCard) Box(
                Modifier.padding(bottom = 4.dp).width(sz.column).ccSurface(RoundedCornerShape(22.dp), LocalCcSheet.current)
                    .padding(horizontal = 14.dp, vertical = 8.dp).testTag("cc-edit-hints"),
            ) {
                Text(
                    if (lifted != null) "Move · Select Drop\nBack Cancel" else "Select Move · ≡ Size · ⏯ Remove\nBack Done",
                    style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 2,
                )
            }
            if (gallery != null && !restartAsk && !agentCard) Text(
                when (gallery) { "" -> "Add a Control"; "glass" -> "Glass"; "addons" -> "Add-ons"; else -> "By App" },
                style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
                modifier = Modifier.offset(x = -CC_BLEED).width(sz.column).padding(start = 4.dp, bottom = 6.dp),
            )
            // Confirms and the permission card are laid out in the panel itself, under the header, never inside the
            // scrolling tiles: a scrolled offset hid the card's top once, and a security prompt must be whole.
            if (restartAsk || agentCard) Box(Modifier.offset(x = -CC_BLEED).padding(top = 2.dp, bottom = 4.dp)) {
            if (restartAsk) {
                val canReboot = rooted || dev.glasslauncher.system.RootFeatures.systemAppState(context) == dev.glasslauncher.system.RootFeatures.SystemApp.On
                CcConfirmCard(
                    sz,
                    title = if (canReboot) "Restart Fire TV?" else "Restart from Settings",
                    body = if (canReboot) "Apps that are open will close." else "Fire TV can restart from Settings › My Fire TV.",
                    confirm = if (canReboot) "Restart" else "Open Fire Settings",
                    destructive = canReboot,
                    onCancel = { restartAsk = false; pendingFocus = "restart" },
                ) {
                    restartAsk = false
                    closeAll()
                    if (canReboot) scope.launch { if (!dev.glasslauncher.system.RootFeatures.reboot(context)) SystemControls.open(context, Settings.ACTION_SETTINGS) }
                    else SystemControls.open(context, Settings.ACTION_SETTINGS)
                }
            } else if (agentCard) CcAgentCard(sz) { allowed ->
                agents.waiting = 0; agents.result = if (allowed) "Allowed" else "Denied"; agentCard = false
                pendingFocus = "addon:claude/agents"
            }
            }
            // The tiles scroll when they're taller than the screen (Now Playing, large text), as tvOS's do;
            // CC_BLEED of room on each side keeps a focused tile's growth and shadow from being clipped.
            if (!restartAsk && !agentCard) Column(
                verticalArrangement = Arrangement.spacedBy(sz.gap),
                modifier = Modifier
                    .weight(1f, fill = false)
                    // The viewport starts 14 dp below the header's bottom while the tiles keep their place (content
                    // starts 2 dp inside it), so a scrolled row is cut under the header, not over its text.
                    .padding(top = CC_BLEED - 2.dp)
                    // Whole rows: the viewport ends at the last row that fits, never through one.
                    .layout { measurable, c ->
                        val pitch = (sz.round + sz.gap).roundToPx(); val gapPx = sz.gap.roundToPx()
                        val top = 2.dp.roundToPx(); val tail = 0
                        val rowsFit = ((c.maxHeight - top + gapPx - tail) / pitch).coerceAtLeast(1)
                        val h = (top + rowsFit * pitch - gapPx + tail).coerceAtMost(c.maxHeight)
                        val p = measurable.measure(c.copy(minHeight = 0, maxHeight = h))
                        layout(p.width, p.height) { p.placeRelative(0, 0) }
                    }
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(start = CC_BLEED, end = CC_BLEED, top = 2.dp, bottom = CC_BLEED)
                    .padding(bottom = m.chromeInset)
                    .width(sz.column),
            ) {
            androidx.compose.animation.Crossfade(pageId, animationSpec = androidx.compose.animation.core.tween(140), label = "cc-page") { shownPage ->
            Column(verticalArrangement = Arrangement.spacedBy(sz.gap)) {
            if (shownPage == "alexa") {
                Box(Modifier) { AlexaPage(sz, closeAll) }
            } else {
            // The Controls page (P83): the layout's ordered {id, size} list, packed into the 4-column grid by
            // CcPacking (next-fit, so it reproduces the fixed layout this was). Edit mode changes a draft of it.
            val u = sz.round
            val pid = layout.page(shownPage)?.id ?: layout.pages.first().id
            val avail: (String) -> Boolean = { id -> when (id) { "airplay" -> airPlay != null; "performance" -> rooted; "vpn" -> vpnApps.isNotEmpty(); else -> true } }
            val tilesNow = layout.page(pid)?.tiles.orEmpty().filter { avail(it.id) }
            val placed = remember(tilesNow) { CcPacking.pack(tilesNow) }
            val rows = placed.maxOfOrNull { it.row + it.tile.size.h } ?: 0
            var focusedId by remember(active) { androidx.compose.runtime.mutableStateOf<String?>(null) }
            fun enterEdit(refocus: Boolean = false) {
                if (draft == null) draft = storedLayout
                // The Alexa page isn't editable: edit mode starts on Controls.
                if (pageId == "alexa") pageId = "controls"
                // From the Edit pill (which goes away): focus lands on the first tile.
                if (refocus) scope.launch { withFrameNanos { }; runCatching { first.requestFocus() } }
            }
            fun liftToggle(id: String) {
                if (lifted == null) { lifted = id; liftBase = draft; return }
                // Dropped over a page icon: the tile goes to the end of that page (and that page shows).
                val target = hoverPage
                if (target != null && target != pid) { draft = draft?.moveToPage(pid, id, target); pageId = target }
                hoverPage = null; lifted = null; liftBase = null
            }
            fun moveLifted(dx: Int, dy: Int) {
                val d = draft ?: return; val id = lifted ?: return
                val all = d.page(pid)?.tiles ?: return
                val from = all.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
                val to = if (dy != 0) d.neighbour(pid, from, dy, avail) else {
                    val vis = all.filter { avail(it.id) }
                    vis.getOrNull(vis.indexOfFirst { it.id == id } + dx)?.let { n -> all.indexOfFirst { it.id == n.id } }
                } ?: return
                draft = d.move(pid, from, to)
            }
            fun removeTile(id: String) {
                val d = draft ?: return
                if (!CcControls.removable(id)) return
                draft = d.remove(pid, id); if (lifted == id) { lifted = null; liftBase = null }
                scope.launch { withFrameNanos { }; runCatching { first.requestFocus() } }
            }
            // The Add a Control gallery, in place of the grid (it's a window of its own, so the draft stays here):
            // null = closed, "" = the three groups, else a group key ("glass", "addons", "byapp").
            val requesters = remember { mutableMapOf<String, FocusRequester>() }
            LaunchedEffect(pendingFocus, placed) {
                val id = pendingFocus ?: return@LaunchedEffect
                if (placed.none { it.tile.id == id }) return@LaunchedEffect
                withFrameNanos { }
                runCatching { (if (id == "fire_settings") first else requesters[id])?.requestFocus() }
                pendingFocus = null
            }
            fun addControl(id: String) {
                val d = draft ?: return
                draft = d.add(pid, id)
                gallery = null
                pendingFocus = id
            }

            // Edit mode's wobble: ONE infinite transition for the whole grid, rotationZ only (+/-0.8 degrees, 190 ms half
            // period, each tile out of phase), read in the layer phase: nothing recomposes or re-layouts per frame.
            val wobbleOn = false
            val wobbling = editing && !reduceMotion
            // Only while editing is there a clock at all: outside edit mode nothing here ticks (the open/close is
            // GPU-bound on this stick, and an idle infinite transition kept frames coming).
            val wobbleClock: androidx.compose.runtime.State<Float> = if (wobbling) androidx.compose.animation.core.rememberInfiniteTransition(label = "cc-wobble").animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.LinearEasing)),
                label = "cc-wobble-t",
            ) else remember { androidx.compose.runtime.mutableStateOf(0f) }
            LaunchedEffect(agents.result) { if (agents.result != null) { kotlinx.coroutines.delay(2_000); agents.result = null } }
            if (gallery != null) CcGallery(gallery!!, layout, sz, { gallery = it }, ::addControl) else {
            Box(
                Modifier
                    .width(sz.column)
                    .height(if (rows == 0) 0.dp else u * rows + sz.gap * (rows - 1))
                    .onPreviewKeyEvent { e ->
                        val k = e.nativeKeyEvent
                        if (!editing) {
                            // On the 2x2 Now Playing, Left and Right are previous and next (wide enough that focus
                            // already arrived from a neighbour); at 2x1 and 1x1 they move focus as usual.
                            val playing = np
                            val isBig = placed.firstOrNull { it.tile.id == "now_playing" }?.tile?.size == CcSize.S2x2
                            if (focusedId == "now_playing" && isBig && playing != null &&
                                (k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                                if (k.action == android.view.KeyEvent.ACTION_DOWN) {
                                    if (k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) playing.previous() else playing.next()
                                }
                                return@onPreviewKeyEvent true
                            }
                            return@onPreviewKeyEvent false
                        }
                        val down = k.action == android.view.KeyEvent.ACTION_DOWN
                        if (lifted != null) when (k.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val dx = if (k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                                if (down) {
                                    val h = hoverPage
                                    if (h != null) {
                                        val ids = layout.pages.map { it.id }.filter { it != "alexa" }
                                        hoverPage = ids.getOrNull((ids.indexOf(h) + dx).coerceIn(0, ids.lastIndex)) ?: h
                                    } else moveLifted(dx, 0)
                                }
                                true
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                if (down) {
                                    if (hoverPage == null) {
                                        // From the top row, Up goes to the menu bar: the page icons to drop onto.
                                        val before = draft
                                        moveLifted(0, -1)
                                        if (draft === before && layout.pages.size > 1) hoverPage = pid
                                    }
                                }
                                true
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) { if (hoverPage != null) hoverPage = null else moveLifted(0, 1) }; true }
                            else -> false
                        } else if (k.keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                            // Menu: the next size the focused control allows; neighbours repack.
                            if (down) focusedId?.let { id -> draft = draft?.resize(pid, id); pendingFocus = id }
                            true
                        } else false
                    },
            ) {
                for (pl in placed) androidx.compose.runtime.key(pl.tile.id) {
                    val tile = pl.tile; val id = tile.id
                    val w = u * tile.size.w + sz.gap * (tile.size.w - 1)
                    val h = u * tile.size.h + sz.gap * (tile.size.h - 1)
                    val pitchPx = with(androidx.compose.ui.platform.LocalDensity.current) { (u + sz.gap).roundToPx() }
                    val off by androidx.compose.animation.core.animateIntOffsetAsState(
                        androidx.compose.ui.unit.IntOffset(pitchPx * pl.col, pitchPx * pl.row),
                        if (reduceMotion) androidx.compose.animation.core.tween(120) else androidx.compose.animation.core.tween(160, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
                        label = "cc-slot",
                    )
                    val isLifted = lifted == id
                    val liftScale by androidx.compose.animation.core.animateFloatAsState(
                        if (isLifted) 1.28f else 1f,
                        if (reduceMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(0.65f, 850f), label = "cc-lift",
                    )
                    val label: (String?) -> Unit = { roundLabel = it }
                    // Select acts, or in edit mode lifts and drops; hold Select enters edit mode; Play/Pause removes there.
                    fun tap(go: () -> Unit): () -> Unit = { if (editing) liftToggle(id) else go() }
                    val hold: (() -> Unit)? = if (editing) null else ({ enterEdit(); focusedId = id })
                    fun playOr(go: () -> Unit): () -> Unit = { if (editing) removeTile(id) else go() }
                    val requester = if (id == "fire_settings") first else remember(id) { requesters.getOrPut(id) { FocusRequester() } }
                    val focusMod = Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) focusedId = id }
                    Box(
                        Modifier
                            .offset { off }
                            .size(w, h)
                            .zIndex(if (isLifted) 2f else 0f)
                            // No layer of its own at rest: only while editing, lifting or settling a lift.
                            .then(if (editing || liftScale != 1f) Modifier.graphicsLayer {
                                scaleX = liftScale; scaleY = liftScale
                                if (wobbling) rotationZ = 0.8f * kotlin.math.sin(((wobbleClock.value + pl.row * 0.13f + pl.col * 0.29f) * 2f * Math.PI).toFloat())
                            } else Modifier)
                            .testTag("cc-tile:$id"),
                    ) {
                        val one = tile.size == CcSize.S1x1
                        when (id) {
                            "fire_settings" -> CcTile("Settings", "Fire TV", RoundedCornerShape(26.dp), w, h, modifier = focusMod,
                                onClick = tap { closeAll(); open(Overlay.TvSettings) }, onLongClick = hold, onPlay = playOr { closeAll(); open(Overlay.TvSettings) }, wiggle = wobbleOn) { fg ->
                                BigIcon(R.drawable.ic_settings, "Settings", fg)
                            }
                            "wifi" -> {
                                val go = tap { system { SystemControls.open(context, Settings.ACTION_WIFI_SETTINGS) } }
                                val icon = if (wifiOn) R.drawable.ic_wifi else R.drawable.ic_wifi_off
                                if (one) Round(icon, "Wi-Fi, $network", on = wifiOn, onLabel = label, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, onClick = go)
                                else CcTile("Wi-Fi", network, Shapes.pill, w, h, onClick = go, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn) { fg ->
                                    PillContent(icon, "Wi-Fi", network, fg, on = wifiOn, accent = Blue)
                                }
                            }
                            "bluetooth" -> {
                                val btValue = when (bluetooth) { true -> "On"; false -> "Off"; null -> "Devices" }
                                val go = tap { system { SystemControls.openBluetooth(context) } }
                                val icon = if (bluetooth == false) R.drawable.ic_bluetooth_disabled else R.drawable.ic_bluetooth
                                if (one) Round(icon, "Bluetooth, $btValue", on = bluetooth == true, onLabel = label, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, onClick = go)
                                else CcTile("Bluetooth", btValue, Shapes.pill, w, h, onClick = go, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn) { fg ->
                                    PillContent(icon, "Bluetooth", btValue, fg, on = bluetooth == true, accent = Blue)
                                }
                            }
                            "launcher" -> {
                                val go = tap { closeAll(); open(Overlay.Settings) }
                                // 1x1 (the default): the sliders glyph.
                                if (one) Round(R.drawable.ic_tune, "Launcher Settings", onLabel = label, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, onClick = go)
                                else CcTile("Launcher Settings", null, Shapes.pill, w, h, onClick = go, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn) { fg ->
                                    PillContent(R.drawable.ic_tune, "Launcher Settings", null, fg, on = false, accent = fg)
                                }
                            }
                            "controllers" -> Round(R.drawable.ic_sports_esports, "Game Controllers", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { system { SystemControls.openGameControllers(context) } }) { tap { system { SystemControls.openGameControllers(context) } }() }
                            "appearance" -> Round(if (dark) R.drawable.ic_dark_mode else R.drawable.ic_light_mode, if (dark) "Theme, Dark" else "Theme, Light", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn,
                                onPlay = playOr { screen.dissolve { edit { it.copy(theme = if (dark) ThemeMode.Light else ThemeMode.Dark) } } }) {
                                tap { screen.dissolve { edit { it.copy(theme = if (dark) ThemeMode.Light else ThemeMode.Dark) } } }()
                            }
                            "screensaver" -> Round(R.drawable.ic_landscape, "Screen Saver", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { closeAll(); AerialActivity.start(context) }) { tap { closeAll(); AerialActivity.start(context) }() }
                            "switcher" -> Round(R.drawable.ic_apps, "App Switcher", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { closeAll(); open(Overlay.AppSwitcher) }) { tap { closeAll(); open(Overlay.AppSwitcher) }() }
                            "airplay" -> airPlay?.let { (state, set) ->
                                val value = when { state.sender != null -> "Connected to ${state.sender}"; state.on -> "On"; else -> "Off" }
                                Round(R.drawable.ic_airplay, "AirPlay, $value", on = state.on, onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { set(!state.on) }) { tap { set(!state.on) }() }
                            }
                            "performance" -> Round(R.drawable.ic_speed, if (fast) "Performance, Fast" else "Performance, Balanced", on = fast, onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn,
                                onPlay = playOr { }) {
                                tap { val next = !fast; fast = next; scope.launch { dev.glasslauncher.system.RootFeatures.setFast(context, next) } }()
                            }
                            "now_playing" -> NowPlayingTile(np, tile.size, w, h, focusMod, hold, wobbleOn, ::tap, ::playOr, label)
                            "sleep_timer" -> {
                                val value = dev.glasslauncher.system.SleepTimer.label(sleepLeft)
                                val go = tap {
                                    val next = dev.glasslauncher.system.SleepTimer.next(dev.glasslauncher.system.SleepTimer.choice(context))
                                    dev.glasslauncher.system.SleepTimer.set(context, next)
                                    sleepLeft = dev.glasslauncher.system.SleepTimer.remainingMs(context)
                                }
                                val on = sleepLeft > 0
                                // 1x1: while it runs, the minutes left ("28") replace the glyph (spec: a short value may).
                                if (one && on) CcTile("Sleep Timer, $value", null, androidx.compose.foundation.shape.CircleShape, w, h, onClick = go, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, on = true) { fg ->
                                    androidx.compose.foundation.text.BasicText("${(sleepLeft + 59_999) / 60_000}", style = Type.heading.copy(color = fg))
                                }
                                else if (one) Round(R.drawable.ic_do_not_disturb_on, "Sleep Timer, $value", on = on, onLabel = label, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, onClick = go)
                                else CcTile("Sleep Timer", value, Shapes.pill, w, h, onClick = go, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, on = on) { fg ->
                                    // "Sleep Timer" doesn't fit a 2x1 pill at the 13 sp floor: the pill says "Sleep", the tile's description keeps the full name.
                                    PillContent(R.drawable.ic_do_not_disturb_on, "Sleep", value, fg, on = on, accent = Blue)
                                }
                            }
                            "vpn" -> {
                                val value = if (vpnConnected) "Connected" else "Off"
                                val go = tap { vpnApps.firstOrNull()?.let { (pkg, _) -> system { dev.glasslauncher.system.VpnControl.open(context, pkg) } } }
                                if (one) Round(R.drawable.ic_vpn, "VPN, $value", on = vpnConnected, onLabel = label, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, onClick = go)
                                else CcTile("VPN", value, Shapes.pill, w, h, onClick = go, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, on = vpnConnected) { fg ->
                                    PillContent(R.drawable.ic_vpn, "VPN", value, fg, on = vpnConnected, accent = Blue)
                                }
                            }
                            "restart" -> {
                                val ask = { restartAsk = true; pendingFocus = null }
                                Round(R.drawable.ic_restart, "Restart", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { }) { tap { ask() }() }
                            }
                            "cleanup" -> Round(R.drawable.ic_cleaning_services, cleaned?.let { "Clean Up, $it" } ?: "Clean Up", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { }) {
                                tap {
                                    if (cleaned != "cleaning…") scope.launch {
                                        cleaned = "cleaning…"
                                        val r = dev.glasslauncher.system.RootFeatures.cleanUp(context)
                                        freed = r.memoryMb
                                        cleaned = when {
                                            r.cachesMb >= 1024 -> "%.1f GB".format(r.cachesMb / 1024f)
                                            r.cachesMb > 0 -> "${r.cachesMb} MB"
                                            r.bulkCaches -> "${r.memoryMb} MB memory"
                                            else -> "memory only"
                                        }
                                        kotlinx.coroutines.delay(4_000)
                                        cleaned = null
                                    }
                                }()
                            }
                        }
                        if (CcShowcase.isShowcase(id)) ShowcaseTile(
                            id, tile.size, w, h, agents, { agentCard = true }, focusMod, hold, wobbleOn, ::tap, ::playOr, label,
                        )
                        // Edit mode's remove legend (the action is Play/Pause on the focused tile); Fire TV Settings has none.
                        if (editing && CcControls.removable(id)) Box(
                            Modifier.align(Alignment.TopStart).offset(x = (-3).dp, y = (-3).dp).size(18.dp)
                                .background(Color(0xE61C1C1E), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(width = 8.dp, height = 2.dp).background(Color.White, CircleShape)) }
                    }
                }
            }
            if (editing && lifted == null) CcTile("Add a Control", null, Shapes.pill, sz.column, sz.pill, onClick = { gallery = "" }, modifier = Modifier.padding(top = sz.gap).testTag("cc-add")) { fg ->
                PillContent(R.drawable.ic_add, "Add a Control", null, fg, on = false, accent = fg)
            }
            if (!editing) {
                // The Edit pill: discoverability for hold-Select.
                CcTile("Edit", null, Shapes.pill, sz.pillWidth, sz.pill, onClick = { enterEdit(refocus = true) }, modifier = Modifier.testTag("cc-edit")) { fg ->
                    PillContent(R.drawable.ic_edit_square, "Edit", null, fg, on = false, accent = fg)
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
            }
            }
            // What's playing, with controls (any app with a media session: Spotify, Amazon Music, YouTube…).
            np?.let {
                val e = remember(motion) { CcEmerge(motion, CcRole.Control) }
                Box(Modifier.ccEmerge(e)) { androidx.compose.runtime.CompositionLocalProvider(LocalCcEmerge provides e) { dev.glasslauncher.widgets.NowPlayingCard(it, sz.column, cfg.textScale) } }
            }
            }
        }
        // Home's pill itself on the first frame (same glass, clock and gear, at its spot), fading as the drops form
        // and back as they merge, so neither end cuts from the pill to Control Center (tvOS: the pill fades out by
        // about 30% of the open). Drawn over the panel, so the drops stay under it until it has gone. Only over
        // Home: over an app there is no pill to match.
        val pillAt = pill
        if (pillAt != null && ControlCenterWindow.homeStarted && !reduceMotion) {
            val onLight = glassState.backdrop?.artLight(0.86f, 0.03f, 0.98f, 0.09f) == true
            val focusedPill = remember(exiting) { ControlCenterWindow.pillFocused }
            dev.glasslauncher.widgets.PillFace(
                cfg, focused = focusedPill, onLight = onLight, copy = true,
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(pillAt.left.roundToInt(), pillAt.top.roundToInt()) }
                    .size(with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.unit.DpSize(pillAt.width.toDp(), pillAt.height.toDp()) })
                    .graphicsLayer {
                        alpha = CcMorph.pillAlpha(CcMorph.bubble(enter.value, exiting, closeFrom))
                        if (focusedPill) { scaleX = PILL_FOCUS_SCALE; scaleY = PILL_FOCUS_SCALE }
                    },
            )
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
private fun PageIcon(
    @DrawableRes icon: Int, label: String, selected: Boolean, index: Int, onShow: () -> Unit,
    onPlay: (() -> Unit)? = null, onSelect: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    PageIcon(label, selected, index, onShow, onPlay, onSelect) { lit ->
        Image(painterResource(icon), null, colorFilter = ColorFilter.tint(if (lit) palette.onFocusFill else palette.primary), modifier = Modifier.size(20.dp))
    }
}

/** The same disc around any [content] (given whether the disc is lit), for art that keeps its own colours. */
@Composable
private fun PageIcon(
    label: String, selected: Boolean, index: Int, onShow: () -> Unit, onPlay: (() -> Unit)? = null, onSelect: (() -> Unit)? = null,
    content: @Composable (lit: Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    val motion = LocalCcMotion.current
    val emerge = remember(motion, index) { motion?.let { CcEmerge(it, CcRole.Icon(index)) } }
    androidx.compose.runtime.CompositionLocalProvider(LocalCcEmerge provides emerge) {
        FocusTile(
            label = label, onClick = onSelect ?: onShow, shape = CircleShape, focusedScale = 1.08f, shadow = false,
            onPlay = onPlay,
            // Focusing an icon shows its page (the + icon makes one on Select instead).
            onFocusChange = { if (it && onSelect == null) onShow() },
            modifier = Modifier.size(LocalCcPageIconSize.current).ccEmerge(emerge),
        ) { focused ->
            val lit = focused || selected
            Box(
                Modifier.fillMaxSize().ccSurface(CircleShape, LocalCcSheet.current)
                    // A drop is plain glass on its way; the selected page's white comes with the glyph as it lands.
                    .then(if (lit) Modifier.drawBehind { drawCircle(palette.focusFill, alpha = if (motion?.enabled == true) CcMorph.glyphAlpha(motion.p) else 1f) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                // The glyph comes in as the drop lands (a drop is plain glass on its way).
                Box(Modifier.graphicsLayer { alpha = if (motion?.enabled == true) CcMorph.glyphAlpha(motion.p) else 1f }) { content(lit) }
            }
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
internal fun PillContent(
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
internal fun Round(
    @DrawableRes icon: Int, label: String, on: Boolean = false, onLabel: (String?) -> Unit = {},
    modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onPlay: (() -> Unit)? = null, wiggle: Boolean = false,
    onClick: () -> Unit,
) {
    val sz = LocalCcSizes.current
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    // The caption follows the button while focused, including its state (Theme · Light → Dark).
    LaunchedEffect(focused, label) { if (focused) onLabel(label) }
    CcTile(label, null, CircleShape, sz.round, sz.round, onClick = onClick, modifier = modifier, onLongClick = onLongClick, onPlay = onPlay ?: onClick, wiggle = wiggle, on = on, onFocusChange = { f ->
        focused = f
        if (!f) onLabel(null)
    }) { fg ->
        Glyph(icon, fg, Modifier.size(sz.roundGlyph))
    }
}

@Composable
internal fun CcTile(
    title: String,
    value: String?,
    shape: Shape,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = onClick,
    wiggle: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    val palette = LocalPalette.current
    val motion = LocalCcMotion.current
    val emerge = remember(motion) { motion?.let { CcEmerge(it, CcRole.Control) } }
    androidx.compose.runtime.CompositionLocalProvider(LocalCcEmerge provides emerge) {
    FocusTile(
        label = if (value != null) "$title, $value" else title,
        onClick = onClick,
        onLongClick = onLongClick,
        onPlay = onPlay,
        wiggle = wiggle,
        onFocusChange = onFocusChange,
        shape = shape,
        // A touch of lift only: at 1.06 the focused Settings tile spilled past the pills' grid.
        focusedScale = 1.02f,
        shadow = false,
        // Grows out of its drop as Control Center opens ([CcEmerge]).
        modifier = modifier.size(width, height).ccEmerge(emerge),
    ) { focused ->
        // A toggle that's on sits white with a blue glyph, like tvOS's Control Center toggles.
        // Focus and "on" fills fade (never pop), so a focus move cross-fades the white from one tile to the next.
        val fade = androidx.compose.animation.core.tween<Float>(FILL_FADE_MS)
        // The first focus lands while Control Center is still opening: the white appears at once, so the tile
        // is never revealed half-grey (later focus moves cross-fade).
        val phase = LocalCcPhase.current
        val focusA by androidx.compose.animation.core.animateFloatAsState(if (focused) 1f else 0f, if (phase.landed) fade else androidx.compose.animation.core.snap(), label = "focus")
        val onA by androidx.compose.animation.core.animateFloatAsState(if (on && !focused) 1f else 0f, fade, label = "on")
        // The text and glyph colour follows the FILL, not the focus: it used to flip the moment focus moved while the
        // white fill took 120 ms to fade, so a tile showed white content on a half-white fill (a washed-out cream,
        // P88). It switches where the content has the same contrast on either side (CcFocusPhase), read through
        // derived state so it recomposes once per flip, not per frame.
        val litByFocus by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { focusA >= CcFocusPhase.FLIP } }
        val litByOn by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { onA >= CcFocusPhase.FLIP } }
        val fg = if (litByFocus) palette.onFocusFill else if (litByOn) Blue else palette.primary
        Box(
            Modifier
                .fillMaxSize()
                // The shared fill stays put and the focus or "on" fill draws over it (swapping modifiers made a
                // fresh node on every focus change, which could draw a frame before it knew where it was).
                .ccSurface(shape, LocalCcSheet.current)
                .drawWithCache {
                    // The fills follow the tile's corners while it grows from a capsule (read per frame then).
                    val rest = shape.createOutline(size, layoutDirection, this)
                    val round = emerge?.now()?.round ?: 1f
                    val outline = if (round < 1f) ccRounding(rest, size, round) else rest
                    onDrawBehind {
                        if (onA > 0f) drawOutline(outline, Color.White, alpha = onA)
                        if (focusA > 0f) drawOutline(outline, palette.focusFill, alpha = focusA)
                    }
                },
            contentAlignment = Alignment.Center,
        ) { content(fg) }
    }
    }
}


/**
 * The "Add a Control" gallery (P83 M3), drawn in place of the grid while edit mode is on: three groups (Glass,
 * Add-ons, By App), then the controls of one, each a pill with its icon, name and a line (what it does, or "Added").
 * Select adds the control at the end of the page and returns to the grid with focus on it; one already on the page
 * (and not parameterized) just returns.
 */
@Composable
private fun CcGallery(group: String, layout: CcLayout, sz: CcSizes, setGroup: (String?) -> Unit, add: (String) -> Unit) {
    val first = remember(group) { FocusRequester() }
    LaunchedEffect(group) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(sz.gap), modifier = Modifier.width(sz.column).testTag("cc-gallery")) {
        if (group == "") {
            val groups = listOf(
                Triple("glass", "Glass", R.drawable.ic_tune),
                Triple("addons", "Add-ons", R.drawable.ic_widgets),
                Triple("byapp", "By App", R.drawable.ic_apps),
            )
            groups.forEachIndexed { i, (key, name, icon) ->
                CcTile(name, null, Shapes.pill, sz.column, sz.pill, onClick = { setGroup(key) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { fg ->
                    PillContent(icon, name, null, fg, on = false, accent = fg)
                }
            }
        } else {
            val entries: List<Pair<String?, CcCatalogEntry>> = when (group) {
                "glass" -> CcCatalog.glass.map { null to it }
                "addons" -> CcCatalog.addons.map { null to it }
                else -> CcCatalog.byApp().flatMap { (app, list) -> list.map { app to it } }
            }
            if (entries.isEmpty()) Text("No add-ons installed", style = Type.caption, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(start = 4.dp))
            entries.forEachIndexed { i, (app, e) ->
                val added = layout.shown(e.id) && '#' !in e.id
                // Rows are only pills (no label rows between them), so they stay on the grid's row pitch and the
                // whole-row scrolling holds; By App names the app in the line instead.
                val line = when {
                    added -> "Added"
                    e.tier.tag != null -> "${e.tier.tag} · ${e.description}"
                    app != null -> "$app · ${e.description}"
                    else -> e.description
                }
                CcTile(e.name, line, Shapes.pill, sz.column, sz.pill, onClick = { if (added) setGroup(null) else add(e.id) },
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).testTag("cc-gallery:${e.id}")) { fg ->
                    PillContent(e.icon, e.name, line, fg, on = added, accent = Blue)
                }
            }
        }
    }
}

/** A page's menu-bar icon: Alexa and the named ones, else the glyph of its first control (spec 4), else the tune glyph. */
@DrawableRes
private fun ccPageIcon(page: CcPage): Int = when (page.icon) {
    "music" -> R.drawable.ic_music_note
    "home" -> R.drawable.ic_home
    "alexa" -> R.drawable.ic_mic
    "more" -> R.drawable.ic_apps
    "grid" -> R.drawable.ic_tune
    // A page of your own: its first control's glyph; a neutral one while it is empty (so it isn't mistaken for Controls).
    else -> page.tiles.firstNotNullOfOrNull { CcCatalog.entry(it.id)?.icon } ?: R.drawable.ic_widgets
}

/** The page's name for the icon's description: Controls and Alexa as before, the rest by their first control or "Page". */
private fun ccPageLabel(page: CcPage): String = when (page.id) {
    "controls" -> "Controls"
    "more" -> "More"
    "alexa" -> "Alexa"
    else -> page.tiles.firstNotNullOfOrNull { CcCatalog.entry(it.id)?.name }?.let { "$it page" } ?: "New page"
}

/**
 * Where a tile's content flips between its resting colour (light) and the focus colour (dark) while the white fill
 * fades in or out (P88). The fill is white at alpha a over dark glass of luminance Lg: content has equal contrast on
 * both sides where the blended fill's luminance is ~0.22, which for tvOS-dark glass (Lg 0.05-0.2) is a ~ 0.13-0.2.
 */
internal object CcFocusPhase {
    const val FLIP = 0.15f

    /** WCAG contrast of [dark] (true) or light content on the white fill at [alpha] over glass of luminance [glass]. */
    fun contrast(alpha: Float, glass: Float, dark: Boolean): Float {
        val fill = glass + alpha * (1f - glass)
        val ink = if (dark) 0.02f else 1f
        val hi = maxOf(fill, ink) + 0.05f; val lo = minOf(fill, ink) + 0.05f
        return hi / lo
    }

    /** The contrast the content actually has at [alpha] with the flip at [FLIP]. */
    fun chosen(alpha: Float, glass: Float): Float = contrast(alpha, glass, dark = alpha >= FLIP)
}
