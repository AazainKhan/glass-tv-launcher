package dev.glasslauncher.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * The first use of a draw variant after an install or update compiles its Skia shader on the RenderThread: 11 compiles,
 * ~290 ms, spread over the first focus move and overlay open (P114's trace). They are cached afterwards, so every
 * variant the animated paths use is drawn once, invisibly, when Home has settled after an update (P115).
 */
object ShaderWarmup {
    /** Marker key (in the "home" preferences) holding the install time the warm-up last ran for. */
    const val KEY = "shaderWarmFor"

    /** An update (or first run) whose install time has not been warmed yet. */
    fun due(updatedAt: Long, warmedFor: Long): Boolean = updatedAt != warmedFor
}

private const val ALPHA = 0.01f

/**
 * Draws the variants the animated paths use, at an alpha too low to see, in a few dp: a layer fade (offscreen and
 * modulated), the scene crossfade and logo hero (image with alpha, tint, DstIn), the dim (rect), the glass rim
 * (outline stroke, gradient), the tile and page chrome (shadow, rounded fills, shape clips, ImageShader brush).
 * [onDone] after the frames that compiled them have run.
 */
@Composable
fun ShaderWarmupLayer(sample: ImageBitmap?, onDone: () -> Unit) {
    if (sample == null) return
    val tint = remember { ColorFilter.tint(Color(0xFFB9BBC2)) }
    val shape = remember { RoundedCornerShape(3.dp) }
    // An offscreen layer at a fade (Cover Flow, overlay pages) and the cheaper modulated one.
    Box(Modifier.size(6.dp).graphicsLayer { alpha = ALPHA; compositingStrategy = CompositingStrategy.Offscreen }.background(Color.Gray, shape))
    Box(Modifier.size(6.dp).graphicsLayer { alpha = ALPHA; compositingStrategy = CompositingStrategy.ModulateAlpha }.background(Brush.verticalGradient(listOf(Color.Black, Color.White)), shape))
    // A shadow under a fill (the shadow goes first), a clip and a gradient fill.
    Box(Modifier.size(6.dp).graphicsLayer { alpha = ALPHA }.shadow(2.dp, shape).background(Color.White, shape))
    Canvas(Modifier.size(8.dp).graphicsLayer { alpha = ALPHA; compositingStrategy = CompositingStrategy.Offscreen }) {
        val src = IntSize(sample.width, sample.height)
        val dst = IntSize(size.width.toInt().coerceAtLeast(2), size.height.toInt().coerceAtLeast(2))
        // Scene crossfade, logo hero: images with alpha, tinted, and masked (the glass's DstIn).
        drawImage(sample, srcOffset = IntOffset.Zero, srcSize = src, dstSize = dst, alpha = 0.5f)
        drawImage(sample, srcOffset = IntOffset.Zero, srcSize = src, dstSize = dst, alpha = 0.5f, colorFilter = tint)
        drawImage(sample, srcOffset = IntOffset.Zero, srcSize = src, dstSize = dst, alpha = 0.5f, blendMode = BlendMode.DstIn)
        // The dim, the rim, the fills.
        drawRect(Color.Black, alpha = 0.4f)
        drawRect(Brush.verticalGradient(listOf(Color.White, Color.Transparent)), alpha = 0.5f)
        drawRect(Brush.linearGradient(listOf(Color.White, Color.Transparent), Offset.Zero, Offset(size.width, size.height)), alpha = 0.5f)
        drawRoundRect(Color.White, Offset.Zero, size, CornerRadius(3f), alpha = 0.5f)
        drawRoundRect(ShaderBrush(ImageShader(sample)), size = Size(size.width, size.height), cornerRadius = CornerRadius(3f), alpha = 0.5f)
        drawRoundRect(Color.White, Offset.Zero, size, CornerRadius(3f), alpha = 0.5f, style = Stroke(1f))
        drawCircle(Color.White, radius = size.width / 4f, alpha = 0.5f)
        drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(0f, size.height); close() }, Color.White, alpha = 0.5f)
    }
    LaunchedEffect(Unit) { repeat(3) { withFrameNanos { } }; onDone() }
}
