package dev.glasslauncher.system

/**
 * "You can always back out" (P87): what Glass knows about the app it just started. For a few seconds after a launch,
 * Back or Home may be taken to mean "cancel it" and return Home, unless the app has really taken the foreground
 * (an activity resumed: [RecentApps.foregroundSince]); a window event is not enough (a starting/splash window
 * appears ~300 ms in, long before the app can take Back; the system holds Back until the activity has focus).
 * Pure (the clock is a parameter) so it is unit-tested; RemoteKeysService does the foreground check.
 */
object LaunchGate {
    /** How long after a launch Back/Home still means "cancel it" (a cap: the foreground check closes it earlier). */
    const val WINDOW_MS = 6_000L

    @Volatile private var pkg: String? = null
    @Volatile private var at = 0L
    @Volatile private var wallAt = 0L

    /** Glass has just asked the system to start [pkg] ([now]: uptime in ms). */
    fun begin(pkg: String, now: Long, wallClock: Long = System.currentTimeMillis()) { this.pkg = pkg; at = now; wallAt = wallClock }

    /** Wall-clock time of the launch (usage events are timed on that clock). */
    fun launchedAtWall(): Long = wallAt

    /** True within [WINDOW_MS] of a launch Glass started. */
    fun interruptible(now: Long): Boolean = pkg != null && now - at in 0..WINDOW_MS

    /** Whether Back/Home should cancel the launch now: recent, and the app hasn't taken the foreground. */
    fun shouldInterrupt(now: Long, appInForeground: Boolean): Boolean = interruptible(now) && !appInForeground

    /** The package being launched, for the log. */
    fun launching(now: Long): String? = pkg.takeIf { interruptible(now) }

    fun clear() { pkg = null }
}
