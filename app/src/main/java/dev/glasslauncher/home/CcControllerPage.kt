package dev.glasslauncher.home

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import dev.glasslauncher.R
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import androidx.tv.material3.Text

/**
 * What the controller tester shows (P174): the buttons held now, and each stick, trigger and the D-pad hat as a value from -1 to 1.
 * Pure, so the reduce step is a plain JVM test.
 */
data class PadState(
    val pressed: Set<String> = emptySet(),
    val leftX: Float = 0f, val leftY: Float = 0f, val rightX: Float = 0f, val rightY: Float = 0f,
    val leftTrigger: Float = 0f, val rightTrigger: Float = 0f, val hatX: Float = 0f, val hatY: Float = 0f,
) {
    fun key(code: Int, down: Boolean): PadState {
        val name = PadLabels.name(code)
        return copy(pressed = if (down) pressed + name else pressed - name)
    }

    /** Sticks inside the dead zone read as centred, so a worn stick doesn't flicker the tester. */
    fun axes(x: Float, y: Float, z: Float, rz: Float, lt: Float, rt: Float, hx: Float, hy: Float): PadState {
        fun d(v: Float) = if (kotlin.math.abs(v) < DEAD_ZONE) 0f else v
        return copy(leftX = d(x), leftY = d(y), rightX = d(z), rightY = d(rz), leftTrigger = lt, rightTrigger = rt, hatX = hx, hatY = hy)
    }

    val idle get() = pressed.isEmpty() && leftX == 0f && leftY == 0f && rightX == 0f && rightY == 0f && leftTrigger == 0f && rightTrigger == 0f && hatX == 0f && hatY == 0f

    companion object { const val DEAD_ZONE = 0.12f }
}

/** Short names for the buttons a pad sends. */
object PadLabels {
    fun name(code: Int): String = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> "A"; KeyEvent.KEYCODE_BUTTON_B -> "B"; KeyEvent.KEYCODE_BUTTON_X -> "X"; KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "LB"; KeyEvent.KEYCODE_BUTTON_R1 -> "RB"; KeyEvent.KEYCODE_BUTTON_L2 -> "LT"; KeyEvent.KEYCODE_BUTTON_R2 -> "RT"
        KeyEvent.KEYCODE_BUTTON_START -> "Start"; KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"; KeyEvent.KEYCODE_BUTTON_MODE -> "Guide"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"; KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_DPAD_UP -> "Up"; KeyEvent.KEYCODE_DPAD_DOWN -> "Down"; KeyEvent.KEYCODE_DPAD_LEFT -> "Left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
        KeyEvent.KEYCODE_DPAD_CENTER -> "Select"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").lowercase().replaceFirstChar { it.uppercase() }
    }
}

private fun MotionEvent.readAxes(prev: PadState): PadState {
    fun a(axis: Int) = getAxisValue(axis)
    return prev.axes(a(MotionEvent.AXIS_X), a(MotionEvent.AXIS_Y), a(MotionEvent.AXIS_Z), a(MotionEvent.AXIS_RZ),
        maxOf(a(MotionEvent.AXIS_LTRIGGER), a(MotionEvent.AXIS_BRAKE)), maxOf(a(MotionEvent.AXIS_RTRIGGER), a(MotionEvent.AXIS_GAS)),
        a(MotionEvent.AXIS_HAT_X), a(MotionEvent.AXIS_HAT_Y))
}

/**
 * A controller's page in Control Center (P174): its name and state, Test Controller (live buttons and sticks) and Button Mapping
 * (Settings). Test mode takes every key but Back, so the whole pad can be pressed without moving focus; Back leaves it.
 */
@Composable
internal fun CcControllerPage(sz: CcSizes, name: String, testing: Boolean, state: PadState, onTest: () -> Unit, onMapping: () -> Unit, onKey: (Int, Boolean) -> Unit, onMotion: (MotionEvent) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(testing) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(sz.gap), modifier = Modifier.width(sz.column + CC_BLEED * 2).padding(horizontal = CC_BLEED).testTag("cc-controller-page")) {
        Text(name, style = CcSectionHeader, color = palette.primary, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        if (!testing) {
            CcTile("Test Controller", "Buttons, sticks and triggers", Shapes.pill, sz.column, sz.pill, onClick = onTest, modifier = Modifier.focusRequester(first).testTag("cc-controller-test")) { fg ->
                PillContent(R.drawable.ic_sports_esports, "Test Controller", "Buttons, sticks and triggers", fg, on = false, accent = fg)
            }
            CcTile("Button Mapping", null, Shapes.pill, sz.column, sz.pill, onClick = onMapping, modifier = Modifier.testTag("cc-controller-mapping")) { fg ->
                PillContent(R.drawable.ic_tune, "Button Mapping", null, fg, on = false, accent = fg)
            }
        } else {
            val view = LocalView.current
            DisposableEffect(view) {
                view.setOnGenericMotionListener { _, e -> if (e.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) { onMotion(e); true } else false }
                onDispose { view.setOnGenericMotionListener(null) }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.width(sz.column).focusRequester(first).focusable().testTag("cc-controller-tester")
                    .onPreviewKeyEvent { e ->
                        if (e.key.nativeKeyCode == KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                        when (e.type) { KeyEventType.KeyDown -> onKey(e.key.nativeKeyCode, true); KeyEventType.KeyUp -> onKey(e.key.nativeKeyCode, false); else -> Unit }
                        true
                    },
            ) {
                Text(if (state.idle) "Press a button or move a stick" else "Held: " + (state.pressed.sorted().joinToString(", ").ifEmpty { "sticks" }), style = Type.body, color = palette.primary, modifier = Modifier.testTag("cc-pad-held"))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    StickView(state.leftX, state.leftY, "cc-pad-left")
                    StickView(state.rightX, state.rightY, "cc-pad-right")
                }
                Text("Triggers ${(state.leftTrigger * 100).toInt()}% · ${(state.rightTrigger * 100).toInt()}%    D-pad ${state.hatX.toInt()}, ${state.hatY.toInt()}", style = Type.secondary, color = palette.secondary, modifier = Modifier.testTag("cc-pad-triggers"))
                Text("Back leaves the test", style = Type.secondary, color = palette.secondary)
            }
        }
    }
}

@Composable
private fun StickView(x: Float, y: Float, tag: String) {
    val c = LocalPalette.current.primary
    Canvas(Modifier.size(72.dp).testTag(tag)) {
        val r = size.minDimension / 2
        drawCircle(c.copy(alpha = 0.35f), r, style = Stroke(2.dp.toPx()))
        drawCircle(c, 7.dp.toPx(), Offset(center.x + x * (r - 8.dp.toPx()), center.y + y * (r - 8.dp.toPx())))
    }
}

internal fun PadState.apply(e: MotionEvent): PadState = e.readAxes(this)
