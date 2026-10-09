package dev.glasslauncher.system

import android.content.Context
import java.io.File

/**
 * A virtual Fire TV remote (tools/uinput/glass-press, bundled as an asset): a uinput device with the real
 * remote's vendor and product, so the remote's key layout applies and Fire OS treats its presses as the
 * remote's own (the mic button, which no app can inject otherwise). Runs as root.
 */
object VirtualRemote {
    /** The tool, unpacked into Glass's files (once per install); null if it can't be. */
    fun path(context: Context): String? = runCatching {
        val out = File(context.filesDir, "glass-press")
        // Unpacked again after every update of Glass, so a newer tool replaces the old one.
        val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        if (!out.exists() || out.lastModified() < updated) {
            context.assets.open("glass-press").use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        out.setExecutable(true, false)
        out.absolutePath
    }.getOrNull()
}
