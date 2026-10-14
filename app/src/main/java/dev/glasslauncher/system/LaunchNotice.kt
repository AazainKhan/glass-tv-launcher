package dev.glasslauncher.system

import android.content.Context

/**
 * A launch that failed (the app gone, turned off, or refused) says so instead of nothing happening (P162). tvOS always
 * tells you why; a launcher's lighter form is the top-right capsule notice, in tvOS's plain voice.
 */
object LaunchNotice {
    fun text(label: String) = "Couldn’t open ${label.trim().ifEmpty { "the app" }}."

    fun couldNotOpen(context: Context, label: String) = RemoteNotice.show(context, text(label), SHOW_MS)

    private const val SHOW_MS = 4_000L
}
