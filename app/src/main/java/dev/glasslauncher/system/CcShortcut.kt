package dev.glasslauncher.system

import android.view.KeyEvent

/**
 * The remote key that opens Control Center over any app without the key-layout module (P167): the accessibility service sees these
 * keys on a stock Fire TV or Android TV, so APK mode has a shortcut too. A hold is timed from the first down (the system makes key repeats
 * after accessibility services have seen the key, so a held key reaches the service as one down and, later, one up); a short press
 * still reaches the app. [key] is the stored name.
 */
enum class CcShortcut(val key: String, val label: String, val keyCode: Int, val holdMs: Long) {
    Off("off", "Off", KeyEvent.KEYCODE_UNKNOWN, 0),
    MenuHold("menu_hold", "Hold Menu", KeyEvent.KEYCODE_MENU, 700),
    PlayPauseHold("playpause_hold", "Hold Play/Pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 800),
    SettingsKey("settings", "Settings key", KeyEvent.KEYCODE_SETTINGS, 0);

    /** A hold (the app keeps the short press) or a plain press of a key made for settings. */
    val hold: Boolean get() = holdMs > 0

    fun matches(keyCode: Int): Boolean = this != Off && keyCode == this.keyCode

    companion object {
        /** What new installs use: holding Menu (the three-line key every Fire remote has). */
        val DEFAULT = MenuHold

        fun parse(key: String?): CcShortcut = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
