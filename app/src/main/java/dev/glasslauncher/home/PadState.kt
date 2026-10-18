package dev.glasslauncher.home

import android.view.KeyEvent
import android.view.MotionEvent

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
    /** The short name of a controller button by its [dev.glasslauncher.system.RemoteButtons.pad] id (without the `pad:` prefix). */
    fun shortName(id: String): String = when (id) {
        "a" -> "A"; "b" -> "B"; "x" -> "X"; "y" -> "Y"; "l1" -> "LB"; "r1" -> "RB"; "l2" -> "LT"; "r2" -> "RT"
        "start" -> "Start"; "select" -> "Select"; "mode" -> "Guide"; "thumbl" -> "L3"; "thumbr" -> "R3"
        else -> id
    }

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

internal fun PadState.apply(e: MotionEvent): PadState = e.readAxes(this)
