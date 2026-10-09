package dev.glasslauncher.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.platform.LocalView
import android.view.SoundEffectConstants
import kotlinx.coroutines.launch

/**
 * A focusable surface with tvOS-style motion: spring lift and scale with a soft shadow, and a
 * slight tilt in from the direction focus arrived from. All motion runs in the layer/draw phase.
 * Select = click, hold Select or press Menu = long click, Play/Pause = [onPlay] (defaults to click).
 * Honours [LocalUiPrefs]: reduce motion drops tilt and wiggle; sounds use the system's
 * navigation sound setting.
 */
@Composable
fun FocusTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Shapes.tile,
    focusedScale: Float = 1.2f,
    wiggle: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = onClick,
    shadow: Boolean = true,
    /** tvOS 27's focus edge light: for app tiles and shelf cards only (not pills, circles or thumbnails). */
    edgeLight: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val longFired = remember { booleanArrayOf(false) }
    val prefs = LocalUiPrefs.current
    val view = LocalView.current

    val scale by animateFloatAsState(
        when {
            pressed -> focusedScale * 0.95f
            focused -> focusedScale
            else -> 1f
        },
        // tvOS 27: a quick, slightly springy arrival; a soft departure with no bounce.
        when {
            prefs.reduceMotion -> tween(120)
            focused || pressed -> Motion.focusIn()
            else -> Motion.focusOut()
        },
        label = "scale",
    )
    // The shadow lifts in step with the tile.
    val lift by animateFloatAsState(if (focused) 1f else 0f, if (focused) Motion.focusIn() else Motion.focusOut(), label = "lift")
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val wiggleAngle = remember { Animatable(0f) }

    LaunchedEffect(focused) {
        if (!focused || prefs.reduceMotion) return@LaunchedEffect
        // A hint of direction, not a wobble: tvOS doesn't tilt on D-pad moves at all (motion-spec §10), so
        // this is tiny and critically damped, settled before the focus scale is (was 8° with overshoot).
        launch { tiltY.snapTo(KeyDirection.dx * TILT_DEG); tiltY.animateTo(0f, spring(dampingRatio = 1f, stiffness = 600f)) }
        launch { tiltX.snapTo(-KeyDirection.dy * TILT_DEG); tiltX.animateTo(0f, spring(dampingRatio = 1f, stiffness = 600f)) }
    }
    LaunchedEffect(wiggle) {
        if (wiggle && !prefs.reduceMotion) {
            // Edit mode: a gentle wobble (tvOS-like, subtler than iOS's jiggle).
            wiggleAngle.snapTo(-0.8f)
            wiggleAngle.animateTo(0.8f, infiniteRepeatable(tween(190, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse))
        }
        else wiggleAngle.animateTo(0f, tween(120))
    }

    Box(
        modifier
            .drawBehind {
                // Pre-blurred shadow bitmap instead of animated elevation, which the render thread
                // would otherwise re-tessellate every frame.
                // Every tile sits slightly above the backdrop (tvOS 27): a soft, close shadow at rest
                // that grows, drops and darkens as the tile lifts on focus.
                if (shadow) {
                    // tvOS 27 tiles cast a clear soft shadow straight down even at rest (inspo
                    // home-grid-scroll): darker and a touch lower than before; focus deepens and drops it.
                    val spread = 1.05f + 0.05f * lift
                    val w = size.width * scale * spread
                    val h = size.height * scale * (spread + 0.06f * lift)
                    val drop = (6.dp.toPx() + 10.dp.toPx() * lift)
                    drawImage(
                        TileShadow.image,
                        dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2 + drop).toInt()),
                        dstSize = IntSize(w.toInt(), h.toInt()),
                        alpha = 0.5f + 0.2f * lift,
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationX = tiltX.value
                rotationY = tiltY.value
                rotationZ = wiggleAngle.value
                cameraDistance = 14f * density
                this.shape = shape
                clip = true
            }
            .drawWithContent {
                drawContent()
                // Reduce motion replaces the move-mode wiggle with a plain outline.
                if (focused && wiggle && prefs.reduceMotion) drawRect(Color.White, style = Stroke(3.dp.toPx()))
            }
            // tvOS 27 lights the edge of a focused app tile: a thin bright line along the top, a fainter one
            // along the bottom. Opt-in via [edgeLight], so other tiles don't carry the modifier at all. Static
            // (it fades with focus, no sweep); the brush is built once per tile size.
            .then(
                if (!edgeLight) Modifier else Modifier.drawWithCache {
                    val stroke = Stroke(EDGE_LIGHT_WIDTH.toPx())
                    val half = stroke.width / 2
                    // Inset by half the stroke so the line sits wholly inside the tile's edge; the corner is the tile's own.
                    val corner = (shape.createOutline(size, layoutDirection, this) as? Outline.Rounded)?.roundRect?.topLeftCornerRadius
                    val brush = Brush.verticalGradient(*EDGE_LIGHT_STOPS, startY = 0f, endY = size.height)
                    onDrawWithContent {
                        drawContent()
                        val strength = lift.coerceAtMost(1f)
                        if (corner != null && strength >= 0.01f) {
                            drawRoundRect(
                                brush, Offset(half, half), Size(size.width - stroke.width, size.height - stroke.width),
                                corner, alpha = strength, style = stroke,
                            )
                        }
                    }
                },
            )
            .onFocusChanged {
                if (it.isFocused && !focused && prefs.sounds) view.playSoundEffect(navigationSound())
                focused = it.isFocused
                if (!it.isFocused) pressed = false
                onFocusChange(it.isFocused)
            }
            .onKeyEvent { event ->
                val e = event.nativeKeyEvent
                when {
                    isSelectKey(e.keyCode) -> {
                        if (e.action == AndroidKeyEvent.ACTION_DOWN) {
                            if (e.repeatCount == 0) {
                                pressed = true
                                longFired[0] = false
                            } else if (!longFired[0] && onLongClick != null && (e.isLongPress || e.repeatCount >= 8)) {
                                longFired[0] = true
                                pressed = false
                                onLongClick()
                            }
                        } else if (e.action == AndroidKeyEvent.ACTION_UP) {
                            val wasPressed = pressed
                            pressed = false
                            if (wasPressed && !longFired[0]) {
                                if (prefs.sounds) view.playSoundEffect(SoundEffectConstants.CLICK)
                                onClick()
                            }
                            longFired[0] = false
                        }
                        true
                    }
                    e.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || e.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> {
                        if (e.action == AndroidKeyEvent.ACTION_UP) onPlay?.invoke()
                        onPlay != null
                    }
                    e.keyCode == AndroidKeyEvent.KEYCODE_MENU -> {
                        if (e.action == AndroidKeyEvent.ACTION_UP) onLongClick?.invoke()
                        onLongClick != null
                    }
                    else -> false
                }
            }
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick { onClick(); true }
                if (onLongClick != null) onLongClick(label = "Options") { onLongClick(); true }
            }
            .focusable(),
    ) {
        content(focused)
    }
}

/** The focus edge light's line: white, 1.25 dp, bright at the top edge, faint through the sides, a little at the bottom. */
private val EDGE_LIGHT_WIDTH = 1.25.dp
private val EDGE_LIGHT_STOPS = arrayOf(
    0f to Color.White.copy(alpha = 0.34f),
    0.2f to Color.White.copy(alpha = 0.08f),
    0.8f to Color.White.copy(alpha = 0.06f),
    1f to Color.White.copy(alpha = 0.16f),
)

/** Matches the system's directional focus sounds to the last D-pad press. */
fun navigationSound(): Int = when {
    KeyDirection.dx < 0 -> SoundEffectConstants.NAVIGATION_LEFT
    KeyDirection.dx > 0 -> SoundEffectConstants.NAVIGATION_RIGHT
    KeyDirection.dy < 0 -> SoundEffectConstants.NAVIGATION_UP
    else -> SoundEffectConstants.NAVIGATION_DOWN
}

private const val TILT_DEG = 1.5f
