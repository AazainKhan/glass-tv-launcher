package dev.glasslauncher.apps

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import dev.glasslauncher.system.RemoteNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What Home shows on a tile whose app is being installed or updated: how far along ([progress], 0 until it starts), from the system's install sessions. */
data class InstallState(val progress: Float)

/** One install session, as the system reports it (pure copy of the parts used, so the logic below is unit-tested). */
data class InstallSession(val id: Int, val pkg: String, val label: String, val progress: Float)

/**
 * App installs and updates on Home (P168). The system's install sessions (the store's download, a file being installed) say which package is
 * being installed and how far along it is: a tile gets a progress badge, and Glass says in a capsule when one starts and how it ended, with the
 * storage case spelled out. Update availability itself is not readable without the store (no public API lists pending updates), so this
 * shows the updates that are under way. Needs READ_INSTALL_SESSIONS (a normal permission); where the sessions cannot be read it does nothing.
 */
object InstallWatch {
    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val states: StateFlow<Map<String, InstallState>> = _states

    /** Sessions as last seen, by id: a finished session is gone from the system, so its name is kept here for the capsule. */
    private val known = HashMap<Int, InstallSession>()
    private val announced = HashSet<Int>()

    /** The badge states for [sessions] (pure): the most advanced session wins when a package has more than one. */
    fun statesFor(sessions: List<InstallSession>): Map<String, InstallState> =
        sessions.groupBy { it.pkg }.mapValues { (_, list) -> InstallState(list.maxOf { it.progress }.coerceIn(0f, 1f)) }

    /** The capsule for a session that ended (pure): storage is named, other failures are plain. */
    fun finishNotice(label: String, success: Boolean, freeBytes: Long?): String = when {
        success -> "$label updated"
        freeBytes != null && freeBytes < LOW_STORAGE_BYTES -> "Not enough storage to update $label."
        else -> "Couldn't update $label."
    }

    const val LOW_STORAGE_BYTES = 300L * 1024 * 1024

    fun start(context: Context) {
        val app = context.applicationContext
        val installer = runCatching { app.packageManager.packageInstaller }.getOrNull() ?: return
        val handler = Handler(Looper.getMainLooper())
        fun refresh() {
            val sessions = runCatching { installer.allSessions }.getOrNull() ?: return   // not readable: the feature is off
            val mine = sessions.mapNotNull { s ->
                val pkg = s.appPackageName ?: return@mapNotNull null
                if (pkg == app.packageName) return@mapNotNull null
                InstallSession(s.sessionId, pkg, s.appLabel?.toString()?.takeIf { it.isNotBlank() } ?: labelOf(app, pkg), s.progress)
            }
            mine.forEach { s ->
                known[s.id] = s
                if (announced.add(s.id)) RemoteNotice.show(app, "Updating ${s.label}…")
            }
            _states.value = statesFor(mine)
        }
        val callback = object : PackageInstaller.SessionCallback() {
            override fun onCreated(sessionId: Int) = refresh()
            override fun onBadgingChanged(sessionId: Int) = refresh()
            override fun onActiveChanged(sessionId: Int, active: Boolean) = refresh()
            override fun onProgressChanged(sessionId: Int, progress: Float) = refresh()
            override fun onFinished(sessionId: Int, success: Boolean) {
                val s = known.remove(sessionId)
                announced.remove(sessionId)
                refresh()
                if (s != null) {
                    val free = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrNull()
                    RemoteNotice.show(app, finishNotice(s.label, success, free), actionable = !success)
                }
            }
        }
        runCatching { installer.registerSessionCallback(callback, handler) }
        refresh()
    }

    private fun labelOf(context: Context, pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
