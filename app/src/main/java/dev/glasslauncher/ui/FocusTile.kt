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
import androidx.compose.ui.graphics.drawscope.Stroke
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
    focusedScale: Float = 1.15f,
    wiggle: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = onClick,
    shadow: Boolean = true,
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
        if (prefs.reduceMotion) tween(120) else spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "scale",
    )
    val lift by animateFloatAsState(if (focused) 1f else 0f, spring(stiffness = 300f), label = "lift")
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val wiggleAngle = remember { Animatable(0f) }

    LaunchedEffect(focused) {
        if (!focused || prefs.reduceMotion) return@LaunchedEffect
        launch { tiltY.snapTo(KeyDirection.dx * 8f); tiltY.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 180f)) }
        launch { tiltX.snapTo(-KeyDirection.dy * 8f); tiltX.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 180f)) }
    }
    LaunchedEffect(wiggle) {
        if (wiggle && !prefs.reduceMotion) {
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
                if (shadow && lift > 0.01f) {
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
            .onFocusChanged {
                if (it.isFocused && !focused && prefs.sounds) view.playSoundEffect(navigationSound())
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
                            if (wasPressed && !longFired[0]) {
                                if (prefs.sounds) view.playSoundEffect(SoundEffectConstants.CLICK)
                                onClick()
                            }
                            longFired[0] = false
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> {
                        if (e.action == AndroidKeyEvent.ACTION_UP) onPlay?.invoke()
                        onPlay != null
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
                if (onLongClick != null) onLongClick(label = "Options") { onLongClick(); true }
            }
            .focusable(),
    ) {
        content(focused)
    }
}

/** Matches the system's directional focus sounds to the last D-pad press. */
fun navigationSound(): Int = when {
    KeyDirection.dx < 0 -> SoundEffectConstants.NAVIGATION_LEFT
    KeyDirection.dx > 0 -> SoundEffectConstants.NAVIGATION_RIGHT
    KeyDirection.dy < 0 -> SoundEffectConstants.NAVIGATION_UP
    else -> SoundEffectConstants.NAVIGATION_DOWN
}
