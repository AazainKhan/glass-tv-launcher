package dev.glasslauncher.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import kotlinx.coroutines.launch

/**
 * A focusable surface with tvOS-style motion: spring lift and scale, a tilt in from the direction
 * focus arrived from, and a one-shot specular sweep. All motion runs in the layer/draw phase.
 * Select = click, hold Select or press Menu = long click.
 */
@Composable
fun FocusTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Shapes.tile,
    focusedScale: Float = 1.12f,
    wiggle: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFocusChange: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val longFired = remember { booleanArrayOf(false) }

    val scale by animateFloatAsState(
        when {
            pressed -> focusedScale * 0.95f
            focused -> focusedScale
            else -> 1f
        },
        spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "scale",
    )
    val lift by animateFloatAsState(if (focused) 1f else 0f, spring(stiffness = 300f), label = "lift")
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val sheen = remember { Animatable(1f) }
    val wiggleAngle = remember { Animatable(0f) }

    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        launch { tiltY.snapTo(KeyDirection.dx * 8f); tiltY.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 180f)) }
        launch { tiltX.snapTo(-KeyDirection.dy * 8f); tiltX.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 180f)) }
        launch { sheen.snapTo(0f); sheen.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    }
    LaunchedEffect(wiggle) {
        if (wiggle) {
            wiggleAngle.snapTo(-1.6f)
            wiggleAngle.animateTo(1.6f, infiniteRepeatable(tween(140), RepeatMode.Reverse))
        }
        else wiggleAngle.animateTo(0f, tween(120))
    }

    Box(
        modifier
            .drawBehind {
                // Pre-blurred shadow bitmap instead of animated elevation, which the render thread
                // would otherwise re-tessellate every frame.
                if (lift > 0.01f && !dev.glasslauncher.DebugFlags.off(dev.glasslauncher.DebugFlags.TILE_FX)) {
                    val w = size.width * scale * 1.06f
                    val h = size.height * scale * 1.12f
                    drawImage(
                        TileShadow.image,
                        dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2 + 10.dp.toPx() * lift).toInt()),
                        dstSize = IntSize(w.toInt(), h.toInt()),
                        alpha = 0.55f * lift,
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                if (!dev.glasslauncher.DebugFlags.off(dev.glasslauncher.DebugFlags.TILE_FX)) {
                    rotationX = tiltX.value
                    rotationY = tiltY.value
                }
                rotationZ = wiggleAngle.value
                cameraDistance = 14f * density
                this.shape = shape
                clip = true
            }
            .drawWithContent {
                drawContent()
                val s = sheen.value
                if (focused && s < 1f) {
                    val x = -size.width * 0.6f + s * size.width * 2.2f
                    drawRect(
                        Brush.linearGradient(
                            0f to Color.Transparent,
                            0.5f to Color.White.copy(alpha = 0.28f * (1f - s)),
                            1f to Color.Transparent,
                            start = Offset(x, 0f),
                            end = Offset(x + size.width * 0.5f, size.height),
                        ),
                    )
                }
                if (focused) {
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.16f * lift),
                            0.4f to Color.Transparent,
                        ),
                    )
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) pressed = false
                onFocusChange(it.isFocused)
            }
            .onKeyEvent { event ->
                val e = event.nativeKeyEvent
                when (e.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_BUTTON_A -> {
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
                            if (wasPressed && !longFired[0]) onClick()
                            longFired[0] = false
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MENU -> {
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
                if (onLongClick != null) onLongClick { onLongClick(); true }
            }
            .focusable(),
    ) {
        content(focused)
    }
}
