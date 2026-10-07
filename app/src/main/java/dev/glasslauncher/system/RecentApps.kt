package dev.glasslauncher.system

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import dev.glasslauncher.apps.AppEntry

/** Recently used apps for the app switcher, most recent first. */
object RecentApps {

    /** Usage access lets the switcher include apps opened from anywhere (adb shell appops set <pkg> GET_USAGE_STATS allow). */
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Apps brought to the front in the last [days] days per the system's usage history (Fire OS only
     * keeps it since the last restart), followed by launches Glass recorded itself ([own]). Only apps
     * on Home are returned, so system screens and launchers never show up.
     */
    fun list(context: Context, installed: List<AppEntry>, own: List<String>, limit: Int = 12, days: Int = 3): List<AppEntry> {
        val byPackage = installed.associateBy { it.packageName }
        val order = (if (hasUsageAccess(context)) fromUsage(context, days) else emptyList()) + own
        return order.distinct().mapNotNull { byPackage[it] }.filter { it.packageName != context.packageName }.take(limit)
    }

    private fun fromUsage(context: Context, days: Int): List<String> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - days * 86_400_000L, end) ?: return emptyList()
        val last = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last[e.packageName] = e.timeStamp
        }
        return last.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Ends an app's background processes (what "close" can do without system privileges). */
    fun close(context: Context, pkg: String) {
        runCatching { context.getSystemService(ActivityManager::class.java)?.killBackgroundProcesses(pkg) }
    }
}
