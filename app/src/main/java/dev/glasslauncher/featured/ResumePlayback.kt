package dev.glasslauncher.featured

/**
 * Resume starts playback where the user left off (P160). Opening the row's own published intent already puts the app on the right
 * page; most apps then wait for a Play press. This decides the one key that finishes the job, after the launch:
 *  - the app already plays: no key;
 *  - the app has a media session and it is paused: KEYCODE_MEDIA_PLAY to it;
 *  - the app's window is up and no session appears: select on the focused control (the page's Play button);
 *  - nothing happened within [TIMEOUT_MS]: give up, no key.
 * Never more than one key (the caller stops after the first decision that is not [Decision.Wait]). Pure, so the rule is unit-tested; the
 * accessibility service ([dev.glasslauncher.system.RemoteKeysService]) feeds it the clock, the window and the session, and sends the key.
 */
object ResumePlayback {
    enum class Session { None, Paused, Playing }
    enum class Decision { Wait, Nothing, MediaPlay, Select, GiveUp }

    /** The whole attempt: after this long from the launch it stops. */
    const val TIMEOUT_MS = 4_000L
    /** How long the app's window must have been up before a key is sent (its first draw). */
    const val WINDOW_SETTLE_MS = 700L
    /** How long to wait for a media session after the window settled before pressing select instead. */
    const val SESSION_GRACE_MS = 1_200L

    /**
     * [sinceLaunchMs]: time since the intent was started. [windowAtMs]: when the app's window appeared (time since launch), null while
     * it has not. [session]: the app's media session right now.
     */
    fun decide(sinceLaunchMs: Long, windowAtMs: Long?, session: Session): Decision = when {
        session == Session.Playing -> Decision.Nothing
        sinceLaunchMs >= TIMEOUT_MS -> Decision.GiveUp
        windowAtMs == null -> Decision.Wait
        session == Session.Paused && sinceLaunchMs - windowAtMs >= WINDOW_SETTLE_MS -> Decision.MediaPlay
        session == Session.None && sinceLaunchMs - windowAtMs >= WINDOW_SETTLE_MS + SESSION_GRACE_MS -> Decision.Select
        else -> Decision.Wait
    }

    /** What the service is working on: the app, and when the launch happened (uptime ms). */
    data class Request(val pkg: String, val launchedAt: Long)
}
