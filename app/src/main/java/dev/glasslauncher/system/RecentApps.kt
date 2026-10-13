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

    /** A real recent task: [taskId] is what [resume] needs to bring it to the front with its state intact. */
    data class RecentTask(val packageName: String, val taskId: Int)

    /** The task id [resume] can use for [pkg], from the last [list] call. Null without task access, or if it has no current task. */
    @Volatile private var taskIds: Map<String, Int> = emptyMap()
    fun taskId(pkg: String): Int? = taskIds[pkg]

    /**
     * The real task stack, most recent first, each with the id [resume] needs. `getRecentTasks` is a deprecated
     * public API whose own behaviour changed under it: since API 21 it silently returns only the caller's tasks
     * unless the caller is privileged, in which case it returns everyone's (I08 spec). Glass is a priv-app via
     * the glass-system Magisk module, so this is empty rather than throwing on any build where that isn't true.
     */
    fun tasks(context: Context, maxNum: Int = 50): List<RecentTask> = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return@runCatching emptyList()
        @Suppress("DEPRECATION")
        am.getRecentTasks(maxNum, 0).mapNotNull { info ->
            info.baseIntent?.component?.packageName?.let { RecentTask(it, info.id) }
        }
    }.getOrDefault(emptyList())

    /**
     * Brings an already-running task to the front with its state intact, instead of relaunching the app fresh.
     * False if it couldn't (the task was evicted from memory, or this build isn't privileged) — the caller should
     * fall back to a normal launch intent then, which is the APK-mode behaviour this never regresses.
     */
    fun resume(context: Context, taskId: Int, options: android.os.Bundle? = null): Boolean = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return@runCatching false
        @Suppress("DEPRECATION")
        am.moveTaskToFront(taskId, 0, options)
        true
    }.getOrDefault(false)

    /**
     * Apps brought to the front in the last [days] days per the system's usage history (Fire OS only
     * keeps it since the last restart), followed by launches Glass recorded itself ([own]). Only apps
     * on Home are returned, so system screens and launchers never show up. An app in [closed] (closed from the
     * switcher, with when) stays out until it comes to the foreground again after that.
     *
     * Prefers the real task stack ([tasks]) when this build can see it: no manual `adb appops` grant needed,
     * and it also populates [taskId] so the switcher can [resume] instead of relaunching. Falls back to usage
     * history otherwise.
     */
    fun list(
        context: Context, installed: List<AppEntry>, own: List<String>, closed: Map<String, Long> = emptyMap(),
        limit: Int = 12, days: Int = 3,
    ): List<AppEntry> {
        val byPackage = installed.associateBy { it.packageName }
        val privileged = tasks(context)
        val usage = if (privileged.isNotEmpty()) {
            taskIds = privileged.associate { it.packageName to it.taskId }
            val now = System.currentTimeMillis()
            privileged.mapIndexed { i, t -> t.packageName to (now - i) }
        } else {
            taskIds = emptyMap()
            if (hasUsageAccess(context)) fromUsage(context, days) else emptyList()
        }
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
