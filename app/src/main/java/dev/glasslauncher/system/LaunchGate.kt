package dev.glasslauncher.system

/**
 * "You can always back out" (P87): what Glass knows about the app it just started. While it hasn't drawn a window and
 * the launch is recent, Back or Home may be taken to mean "cancel it" and return Home; once any app window has
 * appeared the gate closes, so Back inside an app is never eaten. Pure (the clock is a parameter) so it is
 * unit-tested; RemoteKeysService feeds it key and window events.
 */
object LaunchGate {
    /** How long after a launch Back/Home still means "cancel it". */
    const val WINDOW_MS = 6_000L

    @Volatile private var pkg: String? = null
    @Volatile private var at = 0L
    @Volatile private var drawn = false

    /** Glass has just asked the system to start [pkg]. */
    fun begin(pkg: String, now: Long) { this.pkg = pkg; at = now; drawn = false }

    /** An app window appeared ([window] = its package): the launch (or a trampoline of it) is on screen. */
    fun windowShown(window: String, now: Long) {
        if (pkg != null && !drawn && now - at <= WINDOW_MS) drawn = true
    }

    /**
     * Glass's own Home was stopped (something covered it): the launched app's window, or a trampoline that never
     * fires a window-state event, is in front, so Back is the app's.
     */
    fun homeStopped(now: Long) = windowShown("glass:home-stopped", now)

    /** True while a Back or Home press should cancel the launch instead of reaching the (not yet visible) app. */
    fun interruptible(now: Long): Boolean = pkg != null && !drawn && now - at in 0..WINDOW_MS

    /** The package being launched, for the log. */
    fun launching(now: Long): String? = pkg.takeIf { interruptible(now) }

    fun clear() { pkg = null; drawn = false }
}
