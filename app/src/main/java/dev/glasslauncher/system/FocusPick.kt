package dev.glasslauncher.system

/** Which application window has input focus (P87), from a flat description of the interactive windows. Pure, tested. */
object FocusPick {
    class Win(val focused: Boolean, val application: Boolean, val pkg: String?)

    /** The package of the focused application window, or null when none has focus (the system is holding input). */
    fun focusedAppPackage(windows: List<Win>): String? = windows.firstOrNull { it.focused && it.application }?.pkg
}
