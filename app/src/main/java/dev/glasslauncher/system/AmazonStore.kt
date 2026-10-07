package dev.glasslauncher.system

import android.content.Context
import android.content.Intent

/**
 * The Amazon Appstore on Fire OS 8 has no screen of its own: its launcher entry sends Home a request
 * (`navigate_node=l_apps`) that only Amazon's launcher understands, and with that launcher disabled the
 * request lands on Glass and the store never appears. With root, Glass turns Amazon's launcher on just
 * long enough to show the store, and off again as soon as Home is pressed (see watchForHome;
 * Fire OS makes its launcher Home whenever it's enabled, so the press would otherwise stay there).
 */
object AmazonStore {
    const val PACKAGE = "com.amazon.venezia"
    private const val STOCK = "com.amazon.tv.launcher"
    private const val STOCK_HOME = "$STOCK/.ui.HomeActivity_vNext"
    private const val PREFS = "amazon-store"

    /** A Home intent asking for one of the stock launcher's pages (what the Appstore sends). */
    fun isStoreRequest(intent: Intent?): Boolean = intent?.getStringExtra("navigate_node") != null

    suspend fun open(context: Context, node: String = "l_apps", source: String = "appstore"): Boolean {
        if (!Root.available()) return false
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("open", true).apply()
        val ok = Root.action(context, "Open Appstore (Amazon launcher on)", """
            pm enable $STOCK
            am start -n $STOCK_HOME -a android.intent.action.MAIN -c android.intent.category.HOME --es navigate_node '$node' --es source '$source'
        """.trimIndent()).ok
        if (ok) watchForHome(context.applicationContext)
        return ok
    }

    @Volatile private var watcher: Process? = null

    /**
     * Home presses never reach apps or accessibility services on Fire OS (its key policy takes them, and
     * its HOME_PRESSED broadcast goes to two Amazon packages only), but every one logs an activity start
     * with the HOME category. Followed as root, from now on, while the store is open.
     */
    private fun watchForHome(context: Context) {
        watcher?.destroy()
        val p = runCatching {
            ProcessBuilder("su", "-c", "logcat -T 1 -b main -b events -v brief ActivityTaskManager:I wm_new_intent:I '*:S'").redirectErrorStream(true).start()
        }.getOrNull() ?: return
        watcher = p
        val started = android.os.SystemClock.uptimeMillis()
        Thread {
            runCatching {
                p.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isOpen(context)) break
                        // Home from an app: a HOME start. Home while on Amazon's Home screen (the store is
                        // part of it): a new intent to that activity. The store's own start doesn't count.
                        if (android.os.SystemClock.uptimeMillis() - started < 1_500) continue
                        val home = ("START u0" in line && "android.intent.category.HOME" in line) ||
                            ("wm_new_intent" in line && STOCK in line && "android.intent.action.MAIN" in line)
                        if (home) {
                            kotlinx.coroutines.runBlocking { close(context, bringHome = true) }
                            break
                        }
                    }
                }
            }
            p.destroy()
            if (watcher === p) watcher = null
        }.apply { isDaemon = true; name = "glass-store-home" }.start()
    }

    /** Puts Glass back as Home if a store visit left Amazon's launcher on. Cheap when nothing is pending. */
    suspend fun close(context: Context, bringHome: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        if (!prefs.getBoolean("open", false)) return
        prefs.edit().putBoolean("open", false).apply()
        watcher?.destroy(); watcher = null
        Root.action(context, "Close Appstore (Amazon launcher off)", buildString {
            appendLine("pm disable-user --user 0 $STOCK")
            appendLine("cmd package set-home-activity ${context.packageName}/.MainActivity")
            if (bringHome) appendLine("am start -a android.intent.action.MAIN -c android.intent.category.HOME -n ${context.packageName}/.MainActivity")
        })
    }

    fun isOpen(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean("open", false)
}
