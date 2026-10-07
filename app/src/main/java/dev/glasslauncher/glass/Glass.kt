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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

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
            backdrop = next
            return
        }
        previous = old
        backdrop = next
        fade.snapTo(0f)
        fade.animateTo(1f, androidx.compose.animation.core.tween(550))
        previous = null
    }

    var rootSize by mutableStateOf(IntSize.Zero)
    /** 0 = sharp wallpaper, 1 = fully blurred (grid scrolled). Animated by the home screen. */
    val wallpaperBlur = Animatable(0f)
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
) {
    companion object {
        // tvOS 27: milky glass in light appearance, smoky in dark; tint comes from the blurred content.
        fun panel(light: Boolean) = if (light) GlassStyle(Color.White.copy(alpha = 0.45f), 0.30f, 0.70f)
        else GlassStyle(Color(0xFF1A1D24).copy(alpha = 0.28f), 0.12f, 0.48f)

        fun shelf(light: Boolean) = if (light) GlassStyle(Color.White.copy(alpha = 0.32f), 0.26f, 0.75f)
        else GlassStyle(Color.Black.copy(alpha = 0.22f), 0.08f, 0.45f)

        fun overlay(light: Boolean) = panel(light).copy(useOverlay = true)
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
        }
        val outline = cachedOutline ?: return drawContent()
        val root = state.rootSize
        val source = if (style.useOverlay) state.overlay ?: state.backdrop?.blurredSoftware else state.backdrop?.blurredSoftware

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
        if (source != null && root != IntSize.Zero) {
            if (source !== shaderSource) {
                shaderSource = source
                backdropShader = BitmapShader(source.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            matrix.setScale(root.width / source.width.toFloat(), root.height / source.height.toFloat())
            matrix.postTranslate(-origin.x, -origin.y)
            backdropShader!!.setLocalMatrix(matrix)
            drawOutline(outline, ShaderBrush(ComposeShader(backdropShader!!, overlayShader!!, PorterDuff.Mode.SRC_OVER)))
        } else {
            drawOutline(outline, ShaderBrush(overlayShader!!))
        }
        drawRim(outline)
        drawContent()
    }

    private fun solidTint(tint: Color): Color =
        if (tint.luminance() > 0.5f) Color(0xF2F4F5F8) else Color(0xF21A1D25)

    private fun DrawScope.drawRim(outline: Outline) {
        // Edges are defined by light, not lines: bright along the top, fading out down the sides.
        val brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = style.rim),
            0.35f to Color.White.copy(alpha = style.rim * 0.18f),
            1f to Color.White.copy(alpha = style.rim * 0.06f),
        )
        drawOutline(outline, brush, style = Stroke(width = 1.5.dp.toPx()))
    }
}
