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

    /**
     * The privapp-permissions allowlist for [pkg]: everything it requests plus [EXTRA_PRIVILEGED], that this device
     * [known]s. Fire OS enforces the allowlist (ro.control_privapp_permissions=enforce): a privileged permission
     * that is requested and missing from it stops system_server from booting, so this is the one generator, used
     * by the in-app install; scripts/privapp-check builds the same list from the APK before any `scripts/build`.
     */
    internal fun privappXml(pkg: String, requested: List<String>, known: (String) -> Boolean = { true }): String {
        val perms = (requested + EXTRA_PRIVILEGED).distinct().filter(known).sorted()
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<permissions>")
            appendLine("    <privapp-permissions package=\"$pkg\">")
            perms.forEach { appendLine("        <permission name=\"$it\"/>") }
            appendLine("    </privapp-permissions>")
            appendLine("</permissions>")
        }
    }

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
        // A permission this Android doesn't define (BLUETOOTH_SCAN on API 30) is left out: it would be ignored, but
        // the allowlist stays honest.
        val xml = privappXml(context.packageName, requested) { name -> runCatching { pm.getPermissionInfo(name, 0) }.isSuccess }
        val apk = context.applicationInfo.sourceDir
        // Boot-time half of a refresh: see assets/glass-system-service.sh.
        val service = context.assets.open("glass-system-service.sh").bufferedReader().use { it.readText() }.trimEnd()
        val script = """
            set -e
            M=$SYSTEM_MODULE
            mkdir -p ${'$'}M/system/priv-app/GlassLauncher ${'$'}M/system/etc/permissions
            cp '$apk' ${'$'}M/system/priv-app/GlassLauncher/GlassLauncher.apk
            cat > ${'$'}M/system/etc/permissions/privapp-permissions-${context.packageName}.xml <<'GLASSXML'
            ${xml.trimEnd()}
            GLASSXML
            cat > ${'$'}M/service.sh <<'GLASSSVC'
            $service
            GLASSSVC
            chmod 755 ${'$'}M/service.sh
            touch ${'$'}M/uninstall-updates
            printf 'id=glass-system\nname=Glass TV Launcher as a system app\nversion=1\nversionCode=1\nauthor=Glass TV Launcher\ndescription=Installs Glass TV Launcher as a privileged system app (Continue Watching from other apps, secure settings). Remove to revert.\n' > ${'$'}M/module.prop
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

    // ── Own remote pairing (P75 B) ───────────────────────────────────────────────────────────────

    /**
     * Fire's AutoPairService unpairs a sleeping remote when its 60 s timer fires. With
     * `persist.sys.auto_pair_sys_service=false` it never starts; Glass pairs the remote itself ([RemotePairer]).
     * Both halves move together (the property, and Glass's own pairing), after a restart for the property.
     */
    fun ownRemotePairing(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean("ownRemotePairing", false)

    suspend fun setOwnRemotePairing(context: Context, on: Boolean): Root.Result {
        val r = Root.action(
            context, "Own remote pairing ${if (on) "on" else "off"} (Fire's auto-pair ${if (on) "off" else "back"} after a restart)",
            "setprop persist.sys.auto_pair_sys_service ${if (on) "false" else "true"} || resetprop -p persist.sys.auto_pair_sys_service ${if (on) "false" else "true"}",
        )
        if (r.ok) context.getSharedPreferences(PREFS, 0).edit().putBoolean("ownRemotePairing", on).apply()
        return r
    }

    // ── Home takeover ─────────────────────────────────────────────────────────────────────────────

    private val STOCK_HOME = listOf("com.amazon.tv.launcher", "com.amazon.firehomestarter")
    private const val STOCK_HOME_ACTIVITY = "com.amazon.tv.launcher/.ui.HomeActivity_vNext"

    fun homeTakeoverOn(context: Context): Boolean =
        HomeSetup.isDefaultHome(context) && STOCK_HOME.none { isEnabled(context, it) }

    private fun isEnabled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    }.getOrDefault(false)

    /**
     * Keeps Glass as Home: Amazon's launcher came back on once outside any store visit and took over Home.
     * Called whenever Glass comes forward; does nothing unless that launcher is on while no store visit is
     * open and Home Takeover hasn't been turned off.
     */
    suspend fun keepHome(context: Context) {
        if (!context.getSharedPreferences(PREFS, 0).getBoolean("takeover", true)) return
        if (AmazonStore.isOpen(context) || STOCK_HOME.none { isEnabled(context, it) }) return
        if (!Root.available()) return
        Root.action(context, "Amazon launcher was on: Glass back as Home", STOCK_HOME.joinToString("\n") { "pm disable-user --user 0 $it" } +
            "\ncmd package set-home-activity ${context.packageName}/.MainActivity")
    }

    // ── The remote-keys accessibility service must always be on (P87) ────────────────────────────

    /** The enabled-services setting with [service] in it (pure, tested): unchanged when it already is. */
    fun withService(current: String?, service: String): String = when {
        current.isNullOrBlank() || current == "null" -> service
        current.split(':').any { it == service } -> current
        else -> "$current:$service"
    }

    suspend fun setHomeTakeover(context: Context, on: Boolean): Root.Result = (if (on) {
        Root.action(context, "Home takeover on", STOCK_HOME.joinToString("\n") { "pm disable-user --user 0 $it" } +
            "\ncmd package set-home-activity ${context.packageName}/.MainActivity")
    } else {
        Root.action(context, "Home takeover off (stock launcher back)", STOCK_HOME.joinToString("\n") { "pm enable $it" } +
            "\ncmd package set-home-activity $STOCK_HOME_ACTIVITY")
    }).also { context.getSharedPreferences(PREFS, 0).edit().putBoolean("takeover", on).apply() }

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
        if (!Root.available()) return
        // Fire OS turns its Home-key black flash (SystemUI KeyFeedbackUI) back on at every boot; it ran
        // over Glass's app-close animation. Found by e2e/test_device_setup.py after a restart.
        Root.run("[ \"$(getprop persist.perfanimation.enabled)\" = false ] || setprop persist.perfanimation.enabled false")
        if (fast(context)) Root.run(profileScript(true))
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
            printf 'id=glass-tuning\nname=Glass Memory Tuning\nversion=1\nversionCode=1\nauthor=Glass TV Launcher\ndescription=1.2 GB zram, LRU-first low-memory killer, 12 cached apps. Remove to restore Fire OS defaults.\n' > ${'$'}M/module.prop
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
        if (Root.known || Root.available()) Root.action(context, "Free memory", "am kill-all")
        else killBackground(context)
        kotlinx.coroutines.delay(600)
        return (availableMb() - before).coerceAtLeast(0)
    }

    /**
     * No root: ask the system to end the cached background processes of every launchable app but Glass
     * (KILL_BACKGROUND_PROCESSES is a normal permission). Anything playing or otherwise perceptible keeps running.
     */
    private fun killBackground(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val launchers = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
        context.packageManager.queryIntentActivities(launchers, 0)
            .map { it.activityInfo.packageName }.distinct().filter { it != context.packageName }
            .forEach { runCatching { am.killBackgroundProcesses(it) } }
    }

    /** Restart: root `svc power reboot`; a system app PowerManager.reboot (REBOOT); else false (the caller opens Fire's settings). */
    suspend fun reboot(context: Context): Boolean {
        if (Root.known || Root.available()) return Root.action(context, "Restart", "svc power reboot || reboot").ok
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            android.os.PowerManager::class.java.getMethod("reboot", String::class.java).invoke(pm, null)
            true
        }.getOrDefault(false)
    }

    /** What Clean Up freed: bulk caches in MB (root `pm trim-caches`, system app freeStorageAndNotify; none otherwise) and memory in MB. */
    data class CleanUp(val cachesMb: Int, val memoryMb: Int, val bulkCaches: Boolean)

    suspend fun cleanUp(context: Context): CleanUp {
        val memory = freeMemory(context)
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val before = stat.availableBytes
        var bulk = false
        if (Root.known || Root.available()) {
            bulk = Root.action(context, "Clean up: trim caches", "pm trim-caches 999G").ok
        } else {
            bulk = runCatching {
                val pm = context.packageManager
                val m = android.content.pm.PackageManager::class.java.getMethod(
                    "freeStorageAndNotify", String::class.java, Long::class.javaPrimitiveType, Class.forName("android.content.pm.IPackageDataObserver"),
                )
                m.invoke(pm, null, Long.MAX_VALUE, null)
                true
            }.getOrDefault(false)
        }
        kotlinx.coroutines.delay(800)
        stat.restat(android.os.Environment.getDataDirectory().path)
        val freed = ((stat.availableBytes - before).coerceAtLeast(0) / (1024 * 1024)).toInt()
        return CleanUp(freed, memory, bulk)
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
