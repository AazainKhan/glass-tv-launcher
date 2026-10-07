package dev.glasslauncher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

data class AppEntry(
    val packageName: String,
    val label: String,
    val component: ComponentName,
)

class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    fun apps(): Flow<List<AppEntry>> = packageChanges()
        .onStart { emit(Unit) }
        .conflate()
        .map { queryApps() }
        .flowOn(Dispatchers.IO)

    fun launchIntent(app: AppEntry): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: pm.getLaunchIntentForPackage(app.packageName))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun queryApps(): List<AppEntry> {
        val byPackage = LinkedHashMap<String, AppEntry>()
        // Leanback entries win: they are the TV-specific activity with a banner.
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val ai = info.activityInfo
                if (ai.packageName == context.packageName || ai.packageName in byPackage) continue
                byPackage[ai.packageName] = AppEntry(
                    packageName = ai.packageName,
                    label = info.loadLabel(pm).toString().trim(),
                    component = ComponentName(ai.packageName, ai.name),
                )
            }
        }
        return byPackage.values.sortedBy { it.label.lowercase() }
    }

    private fun packageChanges(): Flow<Unit> = callbackFlow {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) { trySend(Unit) }
            override fun onPackageAdded(packageName: String, user: UserHandle) { trySend(Unit) }
            override fun onPackageChanged(packageName: String, user: UserHandle) { trySend(Unit) }
            override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, replacing: Boolean) { trySend(Unit) }
            override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, replacing: Boolean) { trySend(Unit) }
        }
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { launcherApps.unregisterCallback(callback) }
    }
}
