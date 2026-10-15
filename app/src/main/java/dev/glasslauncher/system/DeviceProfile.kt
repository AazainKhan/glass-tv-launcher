package dev.glasslauncher.system

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * What this box is, found at run time (P155): Glass runs on any Android TV or Google TV device, and the things tuned on the Fire TV
 * Stick 4K are gated here by what the device actually has, never by a build flag. The generic default is stock Android behaviour or
 * nothing at all.
 */
object DeviceProfile {
    /** Fire's memory tuning was measured on a 2 GB karat; above this much RAM the stock settings are already roomy. */
    const val TUNING_MAX_RAM_MB = 2_560L

    /** Pure rule (tested): memory tuning is offered on a Fire TV with little RAM. */
    fun memoryTuningApplies(isFire: Boolean, totalRamMb: Long): Boolean = isFire && totalRamMb in 1..TUNING_MAX_RAM_MB

    fun memoryTuningApplies(context: Context): Boolean = memoryTuningApplies(HomeSetup.isFireTv, totalRamMb(context))

    fun totalRamMb(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    fun installed(pm: PackageManager, pkg: String): Boolean = runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    /** Fire's Alexa app: its page and tiles in Control Center are only worth showing where it is. */
    fun hasAlexa(pm: PackageManager): Boolean = installed(pm, dev.glasslauncher.home.AlexaIcon.PACKAGE)

    /**
     * The system's own Home screens other than Glass (Fire's launcher, Google TV's, the leanback one, a vendor's): the HOME
     * activities of system apps, each as the component the system resolves. Empty when Glass is the only Home there is.
     */
    fun stockHomes(context: Context): List<ComponentName> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(home, PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE)
            .filter { r ->
                val a = r.activityInfo
                isStockHome(a.packageName, context.packageName, a.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0, a.directBootAware, r.priority)
            }
            .map { it.activityInfo }
            .distinctBy { it.packageName }
            .map { ComponentName(it.packageName, it.name) }
    }

    /**
     * Pure rule (tested): a HOME handler that is a real stock launcher, safe to disable for Home takeover. NOT one that
     * only stands in while the device boots: Fire's Settings (com.amazon.tv.settings.v2) and AOSP Settings carry a
     * direct-boot FallbackHome (directBootAware, priority -1000). Disabling it left no HOME while user 0 was still
     * BOOTING and the stick hung at boot (2026-10-14, Magisk safe mode). So: system apps only, never Glass, never a
     * direct-boot or negative-priority handler, never a settings or setup package.
     */
    fun isStockHome(pkg: String, self: String, system: Boolean, directBootAware: Boolean, priority: Int): Boolean =
        system && pkg != self && !directBootAware && priority >= 0 && !neverDisable(pkg)

    /** Packages Home takeover must never disable, whatever they declare: settings and setup (their HOME is a boot fallback). */
    fun neverDisable(pkg: String): Boolean {
        val p = pkg.lowercase()
        return "settings" in p || "provision" in p || "setupwizard" in p || p == "android"
    }

    /** Fire's `com.amazon.*` packages from [candidates] that are installed (the debloat rows list only these). */
    fun present(pm: PackageManager, candidates: List<String>): List<String> = candidates.filter { installed(pm, it) }
}
