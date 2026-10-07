package dev.glasslauncher.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import java.io.File

/**
 * What Glass can do with root (Settings › Root). Each feature reports its state, applies, and reverts;
 * every action is logged by [Root.action]. Measured effects on the Fire TV Stick 4K are in CLAUDE.md.
 */
object RootFeatures {
    private const val PREFS = "root"

    // ── System app ────────────────────────────────────────────────────────────────────────────────

    private const val SYSTEM_MODULE = "/data/adb/modules/glass-system"

    /** Privileged permissions a system-app Glass uses (on top of everything it already requests). */
    private val EXTRA_PRIVILEGED = listOf(
        "com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA",   // other apps' channels: Continue Watching
        "com.android.providers.tv.permission.ACCESS_WATCHED_PROGRAMS",
    )

    enum class SystemApp { Off, Pending, On, Removing }

    fun systemAppState(context: Context): SystemApp {
        val isSystem = (context.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        // The app can't see /data/adb (SELinux), so pending changes are remembered here.
        val prefs = context.getSharedPreferences(PREFS, 0)
        return when {
            isSystem && prefs.getBoolean("systemRemoving", false) -> SystemApp.Removing
            isSystem -> SystemApp.On
            prefs.getBoolean("systemPending", false) -> SystemApp.Pending
            else -> SystemApp.Off
        }
    }

    /**
     * Installs Glass as a privileged system app through a Magisk module (systemless; removing the module
     * reverts it). Fire OS enforces the privileged-permission allowlist, and a missing entry stops the
     * system from booting, so the allowlist lists every permission Glass requests.
     */
    suspend fun installSystemApp(context: Context): Root.Result {
        val pm = context.packageManager
        val requested = runCatching {
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
        }.getOrNull().orEmpty()
        val perms = (requested + EXTRA_PRIVILEGED).distinct().sorted()
        val xml = buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<permissions>")
            appendLine("    <privapp-permissions package=\"${context.packageName}\">")
            perms.forEach { appendLine("        <permission name=\"$it\"/>") }
            appendLine("    </privapp-permissions>")
            appendLine("</permissions>")
        }
        val apk = context.applicationInfo.sourceDir
        val script = """
            set -e
            M=$SYSTEM_MODULE
            mkdir -p ${'$'}M/system/priv-app/GlassLauncher ${'$'}M/system/etc/permissions
            cp '$apk' ${'$'}M/system/priv-app/GlassLauncher/GlassLauncher.apk
            cat > ${'$'}M/system/etc/permissions/privapp-permissions-${context.packageName}.xml <<'GLASSXML'
            ${xml.trimEnd()}
            GLASSXML
            printf 'id=glass-system\nname=Glass Launcher as a system app\nversion=1\nversionCode=1\nauthor=Glass Launcher\ndescription=Installs Glass Launcher as a privileged system app (Continue Watching from other apps, secure settings). Remove to revert.\n' > ${'$'}M/module.prop
            chmod 755 ${'$'}M/system/priv-app/GlassLauncher
            chmod 644 ${'$'}M/system/priv-app/GlassLauncher/GlassLauncher.apk ${'$'}M/system/etc/permissions/*.xml
            rm -f ${'$'}M/remove ${'$'}M/disable
        """.trimIndent().lines().joinToString("\n") { it.trimStart() }
        val r = Root.action(context, "Install as system app (Magisk module glass-system)", script)
        if (r.ok) context.getSharedPreferences(PREFS, 0).edit().putBoolean("systemPending", true).putBoolean("systemRemoving", false).apply()
        return r
    }

    suspend fun removeSystemApp(context: Context): Root.Result {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("systemPending", false).putBoolean("systemRemoving", true).apply()
        return Root.action(context, "Remove system app (module glass-system, on restart)", "touch $SYSTEM_MODULE/remove 2>/dev/null; true")
    }

    // ── Home takeover ─────────────────────────────────────────────────────────────────────────────

    private val STOCK_HOME = listOf("com.amazon.tv.launcher", "com.amazon.firehomestarter")
    private const val STOCK_HOME_ACTIVITY = "com.amazon.tv.launcher/.ui.HomeActivity_vNext"

    fun homeTakeoverOn(context: Context): Boolean =
        HomeSetup.isDefaultHome(context) && STOCK_HOME.none { isEnabled(context, it) }

    private fun isEnabled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    }.getOrDefault(false)

    suspend fun setHomeTakeover(context: Context, on: Boolean): Root.Result = if (on) {
        Root.action(context, "Home takeover on", STOCK_HOME.joinToString("\n") { "pm disable-user --user 0 $it" } +
            "\ncmd package set-home-activity ${context.packageName}/.MainActivity")
    } else {
        Root.action(context, "Home takeover off (stock launcher back)", STOCK_HOME.joinToString("\n") { "pm enable $it" } +
            "\ncmd package set-home-activity $STOCK_HOME_ACTIVITY")
    }

    // ── Performance profile ───────────────────────────────────────────────────────────────────────

    private const val GPU = "/sys/class/devfreq/13000000.gpu/min_freq"
    private const val CPU = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"

    /** Fast: system window animations off (app switches are instant; Glass keeps its own motion) and the GPU and CPU held at higher minimum clocks. */
    fun fast(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean("fast", false)

    suspend fun setFast(context: Context, on: Boolean): Root.Result {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("fast", on).apply()
        return Root.action(context, if (on) "Performance: Fast" else "Performance: Balanced", profileScript(on))
    }

    /** Sysfs clocks reset at boot, so a Fast profile is re-applied when Glass starts. */
    suspend fun reapplyAtStart(context: Context) {
        if (fast(context) && Root.available()) Root.run(profileScript(true))
    }

    private fun profileScript(fast: Boolean): String {
        val scale = if (fast) "0" else "1.0"
        return """
            settings put global window_animation_scale $scale
            settings put global transition_animation_scale $scale
            [ -w $GPU ] && echo ${if (fast) 850000000 else 650000000} > $GPU
            [ -w $CPU ] && echo ${if (fast) 1300000 else 600000} > $CPU
            true
        """.trimIndent()
    }

    fun systemAnimationsOff(context: Context): Boolean =
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE) == 0f }.getOrDefault(false)

    // ── Memory tuning ─────────────────────────────────────────────────────────────────────────────

    private const val TUNING_MODULE = "/data/adb/modules/glass-tuning"

    suspend fun memoryTuningOn(): Boolean = Root.run("test -d $TUNING_MODULE && ! test -e $TUNING_MODULE/remove").ok

    /** Same as scripts/tuning: 1.2 GB zram, least-recently-used-first lmkd, 12 cached apps (restart to apply). */
    suspend fun setMemoryTuning(context: Context, on: Boolean): Root.Result = if (on) {
        Root.action(context, "Memory tuning on (module glass-tuning)", """
            set -e
            M=$TUNING_MODULE
            mkdir -p ${'$'}M/system/vendor/etc
            echo '/dev/block/zram0 none swap defaults zramsize=1288490188' > ${'$'}M/system/vendor/etc/fstab.enableswap
            cat > ${'$'}M/service.sh <<'GLASSSH'
            resetprop ro.lmk.kill_heaviest_task false
            resetprop ro.lmk.psi_partial_stall_ms 300
            resetprop ro.lmk.thrashing_limit 200
            resetprop ro.lmk.swap_free_low_percentage 3
            until [ "${'$'}(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
            stop lmkd; start lmkd
            settings put global activity_manager_constants max_cached_processes=12
            GLASSSH
            printf 'id=glass-tuning\nname=Glass Memory Tuning\nversion=1\nversionCode=1\nauthor=Glass Launcher\ndescription=1.2 GB zram, LRU-first low-memory killer, 12 cached apps. Remove to restore Fire OS defaults.\n' > ${'$'}M/module.prop
            chmod 755 ${'$'}M/service.sh; chmod 644 ${'$'}M/system/vendor/etc/fstab.enableswap
            rm -f ${'$'}M/remove ${'$'}M/disable
        """.trimIndent().lines().joinToString("\n") { it.trimStart() })
    } else {
        Root.action(context, "Memory tuning off (module glass-tuning, on restart)",
            "touch $TUNING_MODULE/remove 2>/dev/null; settings put global activity_manager_constants max_cached_processes=4")
    }

    // ── Free memory ───────────────────────────────────────────────────────────────────────────────

    /**
     * Ends background apps (the system's own "kill all background processes"): anything playing or
     * otherwise perceptible keeps running. Returns the memory freed, in MB.
     */
    suspend fun freeMemory(context: Context): Int {
        val before = availableMb()
        Root.action(context, "Free memory", "am kill-all")
        kotlinx.coroutines.delay(600)
        return (availableMb() - before).coerceAtLeast(0)
    }

    fun availableMb(): Int = runCatching {
        File("/proc/meminfo").readLines().first { it.startsWith("MemAvailable:") }.split(Regex("\\s+"))[1].toInt() / 1024
    }.getOrDefault(0)

    // ── App freezer ───────────────────────────────────────────────────────────────────────────────

    data class Freezable(val pkg: String, val label: String, val note: String)

    /** Amazon background services that are safe to turn off (the same list as scripts/debloat). */
    val freezable = listOf(
        Freezable("com.amazon.device.software.ota", "System Updates", "OTA updates can unroot or brick a rooted stick"),
        Freezable("com.amazon.device.software.ota.override", "Update Override", "Part of system updates"),
        Freezable("com.amazon.tv.forcedotaupdater.v2", "Forced Updater", "Part of system updates"),
        Freezable("com.amazon.device.metrics", "Device Metrics", "Usage metrics"),
        Freezable("com.amazon.wirelessmetrics.service", "Wireless Metrics", "Wi-Fi metrics"),
        Freezable("com.amazon.device.telemetry.emitter", "Telemetry", "Telemetry upload"),
        Freezable("com.amazon.neo.minerva", "Minerva Metrics", "Metrics"),
        Freezable("com.amazon.device.crashmanager", "Crash Reports", "Crash report upload"),
        Freezable("com.amazon.ftvads.deeplinking", "Ad Deep Links", "Ads"),
        Freezable("com.amazon.media.recommendations", "Recommendations", "For the stock launcher"),
        Freezable("com.amazon.whisperjoin.middleware.v2.np", "Device Setup", "Wi-Fi setup of other Amazon devices"),
        Freezable("com.amazon.whad", "Whole-Home Audio", "Multi-room Echo groups"),
        Freezable("com.amazon.venezia", "Appstore", "Turning it off also stops app updates from it"),
        Freezable("com.amazon.firebat", "Prime Video", "~95 MB in the background; also the remote's Prime Video button"),
    )

    suspend fun frozen(): Set<String> =
        Root.run("pm list packages -d").out.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()

    suspend fun setFrozen(context: Context, pkg: String, frozen: Boolean): Root.Result =
        Root.action(context, "${if (frozen) "Froze" else "Unfroze"} $pkg", if (frozen) "pm disable-user --user 0 $pkg" else "pm enable $pkg")

    suspend fun restart(context: Context) { Root.action(context, "Restart", "svc power reboot || reboot") }
}
