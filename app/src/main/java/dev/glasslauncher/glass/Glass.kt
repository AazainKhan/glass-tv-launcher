package dev.glasslauncher.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawOutline
import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

private val EDGE_BAND = 10.dp
private val EDGE_SHIFT = 9.dp

/** Shared state for every glass surface: the current wallpaper and the size of the screen it covers. */
@Stable
class BackdropState {
    var backdrop by mutableStateOf<Backdrop?>(null)
        private set
    /** The backdrop being faded out after a scene change; only non-null for the duration of [fade]. */
    var previous by mutableStateOf<Backdrop?>(null)
        private set
    val fade = Animatable(1f)

    /** Swaps in a new scene. The old one fades out (two passes) only for the short cross-fade. */
    suspend fun swap(next: Backdrop, animate: Boolean) {
        val old = backdrop
        if (old == null || !animate) {
            previous = null
            backdrop = next
            fade.snapTo(1f)
            return
        }
        previous = old
        backdrop = next
        try {
            fade.snapTo(0f)
            fade.animateTo(1f, androidx.compose.animation.core.tween(550))
        } finally {
            // Also when a newer swap cancels this one mid-fade: a previous left behind made every glass
            // surface (and the backdrop) draw twice for good, and kept its bitmaps alive (perf-gate: 12%
            // janky frames, +26 MB).
            previous = null
        }
    }

    var rootSize by mutableStateOf(IntSize.Zero)
    /** 0 = sharp wallpaper, 1 = fully blurred (grid scrolled). Animated by the home screen. */
    val wallpaperBlur = Animatable(0f)
    /**
     * How much of the glass texture shows once the blur is back at 0: Home fades it in after a scroll
     * lands, so the tray and folders frost back instead of snapping (blending during the scroll itself
     * cost ~6% janky frames).
     */
    val textureIn = Animatable(1f)
    /** Optional frozen, blurred snapshot of the screen used behind overlays such as folders. */
    var overlay by mutableStateOf<ImageBitmap?>(null)
    /** Accessibility: glass becomes nearly opaque. */
    var reduceTransparency by mutableStateOf(false)
}

val LocalBackdrop = staticCompositionLocalOf { BackdropState() }

data class GlassStyle(
    val tint: Color,
    val highlight: Float,
    val rim: Float,
    val useOverlay: Boolean = false,
    /**
     * Clear glass (the dock tray): samples a lightly blurred copy so the art stays readable through
     * it, and bends the content along the edge. Frosted glass (panels) samples the heavy blur.
     */
    val clear: Boolean = false,
) {
    companion object {
        // tvOS 27: milky glass in light appearance, smoky in dark; tint comes from the blurred content.
        fun panel(light: Boolean) = if (light) GlassStyle(Color.White.copy(alpha = 0.30f), 0.22f, 0.45f)
        else GlassStyle(Color(0xFF1A1D24).copy(alpha = 0.14f), 0.10f, 0.30f)

        fun shelf(light: Boolean) = if (light) GlassStyle(Color.White.copy(alpha = 0.12f), 0.16f, 0.5f, clear = true)
        else GlassStyle(Color.White.copy(alpha = 0.03f), 0.08f, 0.32f, clear = true)

        fun overlay(light: Boolean) = panel(light).copy(useOverlay = true)

        /**
         * Control Center tiles: denser than panels, so a tile over a dark or busy patch of the art still
         * reads as a solid control, as tvOS's do (a plain panel tint looked see-through there).
         */
        fun control(light: Boolean) = if (light) GlassStyle(Color.White.copy(alpha = 0.58f), 0.22f, 0.5f, useOverlay = true)
        else GlassStyle(Color(0xFF3A3D45).copy(alpha = 0.55f), 0.12f, 0.34f, useOverlay = true)
    }
}

/**
 * Liquid-glass surface that works on any API level: draws the pre-blurred wallpaper (or overlay
 * snapshot) that sits behind this element, then a tint, a specular top highlight and a lit rim.
 */
fun Modifier.glass(state: BackdropState, shape: Shape, style: GlassStyle): Modifier =
    this then GlassElement(state, shape, style)

private data class GlassElement(
    val state: BackdropState,
    val shape: Shape,
    val style: GlassStyle,
) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(state, shape, style)
    override fun update(node: GlassNode) {
        node.state = state
        node.shape = shape
        node.style = style
        node.invalidateDraw()
    }
}

private class GlassNode(
    var state: BackdropState,
    var shape: Shape,
    var style: GlassStyle,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var origin = Offset.Zero
    private var cachedSize = Size.Unspecified
    private var cachedOutline: Outline? = null
    private var shaderSource: ImageBitmap? = null
    private var backdropShader: BitmapShader? = null
    private var prevShaderSource: ImageBitmap? = null
    private var prevShader: BitmapShader? = null
    private val prevMatrix = Matrix()
    private var edgeKey: Any? = null
    private var edgeBrush: ShaderBrush? = null
    private var edgeRing: Outline? = null
    private var overlayShader: Shader? = null
    private var overlayKey: Any? = null
    private val matrix = Matrix()

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        if (p != origin) {
            origin = p
            invalidateDraw()
        }
    }

    // The whole surface is one fill: backdrop BitmapShader (mapped to screen space), tint and specular
    // gradient are composed into a single shader. Fill-rate is the bottleneck on TV-stick GPUs, so
    // stacking separate translucent passes is avoided.
    override fun ContentDrawScope.draw() {
        if (size != cachedSize) {
            cachedSize = size
            cachedOutline = shape.createOutline(size, layoutDirection, this)
            val band = EDGE_BAND.toPx()
            edgeRing = shape.createOutline(Size(size.width - band, size.height - band), layoutDirection, this)
        }
        val outline = cachedOutline ?: return drawContent()
        val root = state.rootSize
        val backdrop = state.backdrop
        val blur = state.wallpaperBlur.value
        // Once Home's backdrop is blurring (scrolling toward, or resting on, the grid), frosted glass over
        // it looks the same as its tint alone, so skip sampling the texture and draw flat. The glass was
        // most of the GPU time on the dock-to-grid scroll (25% janky frames before, ~1% after).
        // Flat while the backdrop is blurred or blurring; the texture fades back in after the scroll lands.
        val texture = if (style.useOverlay) 1f else if (blur > 0f) 0f else state.textureIn.value
        val overBlur = texture < 1f
        // Clear glass follows the blur behind it: light over the sharp hero, frosted once the grid is up.
        val clear = style.clear && !state.reduceTransparency
        val source = when {
            style.useOverlay -> state.overlay ?: backdrop?.blurredSoftware
            clear -> backdrop?.clearSoftware
            else -> backdrop?.blurredSoftware
        }

        val opaque = state.reduceTransparency
        val key = listOf(style, size, source, opaque)
        if (key != overlayKey) {
            overlayKey = key
            val tint = (if (opaque) solidTint(style.tint) else style.tint).toArgb()
            val tintShader = LinearGradient(0f, 0f, 0f, 1f, tint, tint, Shader.TileMode.CLAMP)
            val highlight = LinearGradient(
                0f, 0f, 0f, size.height,
                // Gloss near the top, clear middle, soft inner shadow at the bottom (the bevel).
                intArrayOf(
                    Color.White.copy(alpha = style.highlight).toArgb(),
                    Color.Transparent.toArgb(),
                    Color.Transparent.toArgb(),
                    Color.Black.copy(alpha = 0.16f).toArgb(),
                ),
                floatArrayOf(0f, 0.35f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
            overlayShader = ComposeShader(tintShader, highlight, PorterDuff.Mode.SRC_OVER)
        }
        if (overBlur) {
            // Flat tint: over a blurred backdrop this is what frosted glass looks like (tvOS's grid tray is
            // a flat translucent slab), and texture shaders here tipped the GPU over budget.
            val flat = if (state.reduceTransparency) solidTint(style.tint) else Color.White.copy(alpha = if (style.tint.luminance() > 0.5f) style.tint.alpha else 0.09f)
            drawOutline(outline, flat, alpha = 1f - texture)
        }
        if (source != null && root != IntSize.Zero && texture > 0f) {
            if (source !== shaderSource) {
                shaderSource = source
                backdropShader = BitmapShader(source.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            matrix.setScale(root.width / source.width.toFloat(), root.height / source.height.toFloat())
            matrix.postTranslate(-origin.x, -origin.y)
            backdropShader!!.setLocalMatrix(matrix)
            // While the backdrop cross-fades to a new slide, the glass does too, on the same clock: the
            // old picture underneath, the new one fading in over it. Otherwise the tray switches first.
            val previous = state.previous
            val fade = state.fade.value
            val previousSource = previous?.let { if (style.useOverlay) null else if (clear) it.clearSoftware else it.blurredSoftware }
            if (previousSource != null && fade < 1f) {
                if (previousSource !== prevShaderSource) {
                    prevShaderSource = previousSource
                    prevShader = BitmapShader(previousSource.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                }
                prevMatrix.setScale(root.width / previousSource.width.toFloat(), root.height / previousSource.height.toFloat())
                prevMatrix.postTranslate(-origin.x, -origin.y)
                prevShader!!.setLocalMatrix(prevMatrix)
                drawOutline(outline, ShaderBrush(ComposeShader(prevShader!!, overlayShader!!, PorterDuff.Mode.SRC_OVER)), alpha = texture)
                drawOutline(outline, ShaderBrush(ComposeShader(backdropShader!!, overlayShader!!, PorterDuff.Mode.SRC_OVER)), alpha = fade * texture)
            } else {
                prevShaderSource = null; prevShader = null
                drawOutline(outline, ShaderBrush(ComposeShader(backdropShader!!, overlayShader!!, PorterDuff.Mode.SRC_OVER)), alpha = texture)
            }
            if (clear) drawEdgeBand(source, (if (previousSource != null) fade else 1f) * texture)
        } else if (!overBlur) {
            drawOutline(outline, ShaderBrush(overlayShader!!))
        }
        if (texture < 0.5f) drawOutline(outline, Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
        else drawRim(outline)
        drawContent()
    }

    /**
     * Liquid Glass bends light at its edge: a thin band just inside the outline shows the content
     * from slightly beyond the edge, compressed. Faked with one stroke whose shader maps the screen
     * through a mild zoom-out about the surface's centre (no RuntimeShader on API 30).
     */
    private fun DrawScope.drawEdgeBand(source: ImageBitmap, alpha: Float = 1f) {
        val ring = edgeRing ?: return
        val band = EDGE_BAND.toPx()
        // Built once per source/size/style/position; rebuilding shaders per frame is wasted work.
        val key = listOf(source, size, style, origin, state.rootSize)
        if (key != edgeKey) {
            edgeKey = key
            val shift = EDGE_SHIFT.toPx()
            val cx = size.width / 2f
            val cy = size.height / 2f
            val m = Matrix(matrix).apply {
                postTranslate(-band / 2f, -band / 2f)
                postScale(cx / (cx + shift), cy / (cy + shift), cx - band / 2f, cy - band / 2f)
            }
            val bent = BitmapShader(source.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            // Fully bent at the rim, fading to nothing at the band's inner edge, so it melts into the
            // body instead of leaving a seam (the long top and bottom edges are where a seam shows).
            val h = size.height
            val edge = (band / h).coerceAtMost(0.45f)
            val mask = LinearGradient(
                0f, -band / 2f, 0f, h - band / 2f,
                intArrayOf(android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK),
                floatArrayOf(0f, edge, 1f - edge, 1f),
                Shader.TileMode.CLAMP,
            )
            edgeBrush = ShaderBrush(ComposeShader(bent, mask, PorterDuff.Mode.DST_IN))
        }
        // One stroke: each extra ring cost about 7 ms a frame on the Fire TV GPU.
        translate(band / 2f, band / 2f) {
            drawOutline(ring, edgeBrush!!, alpha = alpha, style = Stroke(width = band))
        }
    }

    private fun solidTint(tint: Color): Color =
        if (tint.luminance() > 0.5f) Color(0xF2F4F5F8) else Color(0xF21A1D25)

    private fun DrawScope.drawRim(outline: Outline) {
        // Edges are defined by light, not lines: bright along the top, fading out down the sides.
        // Light catches the top edge most and the bottom edge a little (tvOS 27's glossier rim).
        val brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = style.rim),
            0.3f to Color.White.copy(alpha = style.rim * 0.14f),
            0.8f to Color.White.copy(alpha = style.rim * 0.05f),
            1f to Color.White.copy(alpha = style.rim * 0.25f),
        )
        drawOutline(outline, brush, style = Stroke(width = 1.dp.toPx()))
    }
}
