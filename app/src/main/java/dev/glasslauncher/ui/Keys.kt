package dev.glasslauncher.ui

import android.view.KeyEvent

/**
 * The keys that activate whatever has focus: the remote's centre button, Enter (keyboards), the numpad's
 * Enter, and a gamepad's A. One list for tiles, menu rows and buttons (rows ignored a gamepad's A).
 */
fun isSelectKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A
