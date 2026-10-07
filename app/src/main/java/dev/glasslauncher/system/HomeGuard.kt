package dev.glasslauncher.system

import android.accessibilityservice.AccessibilityService
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import dev.glasslauncher.MainActivity
import dev.glasslauncher.app

/**
 * Optional fallback for devices that never let a third-party launcher become Home (Fire TV):
 * when the stock launcher's home screen comes to the front, bring Glass Launcher back.
 * It only watches window changes of known stock launchers and never filters keys.
 */
class HomeGuardService : AccessibilityService() {
    private var lastJump = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!app.config.config.value.homeGuard) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()
        val isStockHome = when (pkg) {
            "com.amazon.tv.launcher" -> cls.contains("Home", ignoreCase = true) || cls.contains("Launcher", ignoreCase = true)
            in HomeSetup.STOCK_LAUNCHERS -> true
            else -> false
        }
        if (!isStockHome) return
        val now = SystemClock.uptimeMillis()
        if (now - lastJump < 800) return
        lastJump = now
        startActivity(HomeSetup.homeIntent(this))
    }

    override fun onInterrupt() = Unit
}

object HomeSetup {
    val STOCK_LAUNCHERS = setOf(
        "com.amazon.tv.launcher",
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
    )

    val isFireTv: Boolean get() = Build.MANUFACTURER.equals("Amazon", ignoreCase = true) || Build.MODEL.startsWith("AFT")

    fun homeIntent(context: Context) = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun isDefaultHome(context: Context): Boolean {
        val info = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        return info?.activityInfo?.packageName == context.packageName
    }

    /** Standard Android path: the Home role dialog, or the system home picker. Null if neither exists. */
    fun requestDefaultHomeIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }
        val settings = Intent(Settings.ACTION_HOME_SETTINGS)
        return settings.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    private fun guardComponent(context: Context) = ComponentName(context, HomeGuardService::class.java)

    fun isGuardEnabled(context: Context): Boolean = isServiceEnabled(context, guardComponent(context))
    fun isRemoteKeysEnabled(context: Context): Boolean = isServiceEnabled(context, ComponentName(context, RemoteKeysService::class.java))

    /**
     * Android drops an app's accessibility services from the enabled list when the app is force-stopped
     * (Settings › Force Stop, adb, benchmarks). With the remote-button layout installed that would leave
     * the remapped buttons dead, so Glass turns its service back on at startup (needs WRITE_SECURE_SETTINGS).
     */
    fun ensureRemoteKeys(context: Context) {
        if (!RemoteButtons.takeoverActive() || isRemoteKeysEnabled(context)) return
        runCatching {
            val cr = context.contentResolver
            val own = ComponentName(context, RemoteKeysService::class.java).flattenToString()
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.filter { it.isNotBlank() }.orEmpty()
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (current + own).distinct().joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }

    private fun isServiceEnabled(context: Context, component: ComponentName): Boolean =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true

    /** Turns the accessibility service on or off directly; needs WRITE_SECURE_SETTINGS. */
    fun setGuardEnabled(context: Context, enabled: Boolean): Boolean = runCatching {
        val cr = context.contentResolver
        val own = guardComponent(context).flattenToString()
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != guardComponent(context) }.orEmpty()
        val next = if (enabled) current + own else current
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next.joinToString(":"))
        if (next.isNotEmpty()) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        true
    }.getOrDefault(false)

    const val DISABLE_STOCK_COMMAND = "adb shell pm disable-user --user 0 com.amazon.tv.launcher"
    const val RESTORE_STOCK_COMMAND = "adb shell pm enable com.amazon.tv.launcher"
}
