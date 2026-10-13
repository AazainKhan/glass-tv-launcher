package dev.glasslauncher.sonos

import android.content.Context
import dev.glasslauncher.system.Root

/**
 * P135: with root (Glass Pro) the MediaProjection dialog is skipped. `appops PROJECT_MEDIA allow` plus the RECORD_AUDIO
 * grant make the system's MediaProjectionPermissionActivity approve by itself. The plain APK never calls this and
 * keeps the dialog every session. App opt-outs (allowAudioPlaybackCapture=false, DRM) are unaffected.
 */
object ProjectionPregrant {
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** The two commands, or null when [pkg] is not a plain package name (it ends up in a root shell). */
    internal fun commands(pkg: String): List<String>? {
        if (!PACKAGE.matches(pkg)) return null
        return listOf("appops set $pkg PROJECT_MEDIA allow", "pm grant $pkg android.permission.RECORD_AUDIO")
    }

    /** Checks first, so a granted device costs one `appops get`. */
    internal fun script(pkg: String): String? {
        val cmds = commands(pkg) ?: return null
        return "appops get $pkg PROJECT_MEDIA 2>/dev/null | grep -q ': allow' && exit 0\n" + cmds.joinToString("\n")
    }

    /** Suspends off Main (Root.run is on IO). Does nothing without root. */
    suspend fun ensure(context: Context) {
        val script = script(context.packageName) ?: return
        if (!(Root.known || Root.available())) return
        Root.action(context, "Skip the projection dialog for Sonos", script)
    }
}
