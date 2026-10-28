package dev.glasslauncher.ui

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Right-to-left layouts (P308). Compose mirrors rows, grids, paddings and alignments by itself, and focus search is spatial, so those
 * already follow the screen. What it cannot mirror is code that turns a Left or Right key into a step along a list's order (a slider's
 * value, a tile's place in the dock, a page index): in a mirrored row the next item is on the *left*, so the key must flip.
 * Custom-drawn carousels that place items at physical x (the cover flow, the app switcher) keep their keys physical.
 */
@Composable
fun isRtl(): Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl

/** A Left/Right key as a step along reading order: +1 toward a row's end (right in LTR, left in RTL); 0 for any other key. */
fun horizontalStep(keyCode: Int, rtl: Boolean): Int = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_RIGHT -> if (rtl) -1 else 1
    KeyEvent.KEYCODE_DPAD_LEFT -> if (rtl) 1 else -1
    else -> 0
}
