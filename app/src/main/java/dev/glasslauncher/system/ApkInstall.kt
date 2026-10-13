package dev.glasslauncher.system

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface InstallResult {
    data object Success : InstallResult
    /** The user has to act first (allow unknown sources for Glass); start [settings], then call install again. */
    data class NeedsUserAction(val settings: Intent) : InstallResult
    data class Failed(val reason: String) : InstallResult
}

enum class InstallMode { Apk, Pro, Os }

/** One-press install of a downloaded APK, by what this device lets Glass do. */
object ApkInstall {
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<InstallResult>>()

    /** Glass OS (privileged INSTALL_PACKAGES) installs silently, Glass Pro (root) through su, else the system card. */
    internal fun chooseMode(hasInstallPackages: Boolean, root: Boolean) = when {
        hasInstallPackages -> InstallMode.Os
        root -> InstallMode.Pro
        else -> InstallMode.Apk
    }

    suspend fun mode(context: Context): InstallMode {
        val perm = context.checkSelfPermission("android.permission.INSTALL_PACKAGES") == PackageManager.PERMISSION_GRANTED
        return chooseMode(perm, !perm && (Root.known || Root.available()))
    }

    /** Suspends until the install ends; in APK mode that includes the user's answer on the system card. */
    suspend fun install(context: Context, file: File): InstallResult {
        val mode = mode(context)
        if (mode == InstallMode.Apk && !Updater.canInstall(context)) return InstallResult.NeedsUserAction(Updater.unknownSourcesIntent(context))
        if (mode == InstallMode.Pro) {
            val r = Root.runWithInput("pm install -S ${file.length()} -r -d", file)
            Root.log(context, "Install ${file.name}: ${if (r.ok) "ok" else "failed (${r.code}) ${r.out.take(200)}"}")
            fromRoot(r.code, r.out)?.let { return it }
            // su is there but refused or broke: the system card still works
            if (!Updater.canInstall(context)) return InstallResult.NeedsUserAction(Updater.unknownSourcesIntent(context))
        }
        return commit(context, file, userAction = mode != InstallMode.Os)
    }

    /** pm's verdict, or null when su itself failed (127 no su, 124 timeout, 1 with no pm output) and the session should try. */
    internal fun fromRoot(code: Int, out: String): InstallResult? = when {
        code == 0 && out.contains("Success") -> InstallResult.Success
        code == 127 || code == 124 || out.isBlank() -> null
        else -> InstallResult.Failed(out.lines().lastOrNull { it.isNotBlank() }.orEmpty().removePrefix("Failure ").trim())
    }

    /** Null while the install is still waiting on the user (PENDING_USER_ACTION). */
    internal fun fromStatus(status: Int, message: String?): InstallResult? = when (status) {
        PackageInstaller.STATUS_SUCCESS -> InstallResult.Success
        PackageInstaller.STATUS_PENDING_USER_ACTION -> null
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Failed("Cancelled")
        PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallResult.Failed("Conflicts with an installed app (different signature?)")
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallResult.Failed("Not compatible with this device")
        PackageInstaller.STATUS_FAILURE_STORAGE -> InstallResult.Failed("Not enough storage")
        PackageInstaller.STATUS_FAILURE_INVALID -> InstallResult.Failed("The file is not a valid APK")
        else -> InstallResult.Failed(message?.takeIf { it.isNotBlank() } ?: "Install failed ($status)")
    }

    /** Called by [InstallResultReceiver] for every status of a session. */
    internal fun onStatus(sessionId: Int, status: Int, message: String?) {
        val result = fromStatus(status, message) ?: return
        waiting.remove(sessionId)?.complete(result)
    }

    /** The PackageInstaller commit shared with [Updater] (which wants no card where the system allows it). */
    internal suspend fun commit(context: Context, file: File, userAction: Boolean, await: Boolean = true): InstallResult = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(
            if (userAction) PackageInstaller.SessionParams.USER_ACTION_REQUIRED else PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED,
        )
        val id = installer.createSession(params)
        val done = CompletableDeferred<InstallResult>().also { if (await) waiting[id] = it }
        try {
            installer.openSession(id).use { session ->
                session.openWrite("install.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                val intent = Intent(context, InstallResultReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
            if (await) done.await() else InstallResult.Success
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            if (e is kotlinx.coroutines.CancellationException) throw e
            InstallResult.Failed(e.message ?: "Install failed")
        } finally {
            waiting.remove(id)
        }
    }
}
