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
     * on Home are returned, so system screens and launchers never show up. An app in [closed] (closed from the
     * switcher, with when) stays out until it comes to the foreground again after that.
     */
    fun list(
        context: Context, installed: List<AppEntry>, own: List<String>, closed: Map<String, Long> = emptyMap(),
        limit: Int = 12, days: Int = 3,
    ): List<AppEntry> {
        val byPackage = installed.associateBy { it.packageName }
        val usage = if (hasUsageAccess(context)) fromUsage(context, days) else emptyList()
        return order(usage, own, closed).mapNotNull { byPackage[it] }.filter { it.packageName != context.packageName }.take(limit)
    }

    /**
     * The switcher's order, newest first: [usage] (package to when it last came to the foreground) then [own]
     * (launched through Glass, no time). Closed apps are left out unless the system saw them in the foreground
     * after they were closed; an app of [own] has no foreground time, and is dropped from [own] when closed.
     */
    fun order(usage: List<Pair<String, Long>>, own: List<String>, closed: Map<String, Long>): List<String> {
        val fromUsage = usage.sortedByDescending { it.second }.filter { (pkg, at) -> at > (closed[pkg] ?: Long.MIN_VALUE) }.map { it.first }
        val recentOwn = own.filter { pkg -> pkg !in closed || usage.any { it.first == pkg && it.second > closed.getValue(pkg) } }
        return (fromUsage + recentOwn).distinct()
    }

    private fun fromUsage(context: Context, days: Int): List<Pair<String, Long>> {
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
        return last.entries.map { it.key to it.value }
    }

    /**
     * Really stops [pkg] (not just its background processes, which the system starts again at once): as a
     * privileged app through ActivityManager.forceStopPackage, else as root (`am force-stop`). Returns whether
     * something worked.
     */
    suspend fun stop(context: Context, pkg: String): Boolean {
        runCatching { context.getSystemService(ActivityManager::class.java)?.killBackgroundProcesses(pkg) }
        val privileged = runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            ActivityManager::class.java.getMethod("forceStopPackage", String::class.java).invoke(am, pkg)
            true
        }.getOrDefault(false)
        if (privileged) return true
        // A "no root" answer cached before the grant must not stick: look again when something needs it.
        if (!Root.available() && !Root.recheck()) return false
        return Root.action(context, "Close $pkg", "am force-stop $pkg").ok
    }
}
