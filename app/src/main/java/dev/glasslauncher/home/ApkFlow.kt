package dev.glasslauncher.home

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.glasslauncher.app
import dev.glasslauncher.downloads.installOutcome
import dev.glasslauncher.system.ApkInstall
import dev.glasslauncher.system.InstallResult
import dev.glasslauncher.system.RemoteNotice
import dev.glasslauncher.system.StagedApk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Select on a downloaded APK: stage a copy, ask on the install card, install. Shared by the manager and the file browser. */
internal class ApkFlow(
    private val context: Context,
    private val scope: CoroutineScope,
    private val open: (Overlay) -> Unit,
    private val keepApk: () -> Boolean,
) {
    // Keys being staged or installed (Select on them is ignored), and staged copies kept for the retry after "allow installs".
    private val busy = mutableSetOf<String>()
    private val kept = mutableMapOf<String, StagedApk>()

    fun dispose() { kept.values.forEach { it.discard() }; kept.clear() }

    /** [removeAfter] runs once the install succeeded and "Keep Installed APKs" is off. */
    fun start(key: String, file: File, expectedSha256: String?, removeAfter: () -> Unit) {
        val unchecked = expectedSha256 == null
        if (!busy.add(key)) return
        kept[key]?.let { ask(key, it, unchecked, removeAfter); return }
        scope.launch {
            val staged = withContext(Dispatchers.IO) { runCatching { StagedApk.stage(context, file) }.getOrNull() }
            when {
                staged == null -> { RemoteNotice.show(context, "Couldn't read that APK"); busy.remove(key) }
                !checksumMatches(expectedSha256, staged.sha256) -> {
                    staged.discard(); busy.remove(key)
                    RemoteNotice.show(context, "This file no longer matches its checksum, so it won't be installed", 4_000)
                }
                else -> ask(key, staged, unchecked, removeAfter)
            }
        }
    }

    private fun ask(key: String, staged: StagedApk, unchecked: Boolean, removeAfter: () -> Unit) {
        open(Overlay.InstallConfirm(
            staged,
            onConfirm = { install(key, staged, removeAfter) },
            onDiscard = { staged.discard(); kept.remove(key); busy.remove(key) },
            unchecked = unchecked,
        ))
    }

    private fun install(key: String, staged: StagedApk, removeAfter: () -> Unit) {
        kept.remove(key)
        // The app scope: leaving the page must not cancel an install that is already running.
        context.app.scope.launch {
            val result = ApkInstall.install(context, staged)
            val outcome = installOutcome(result, staged.info.label, keepApk())
            RemoteNotice.show(context, outcome.notice, 4_000)
            if (result is InstallResult.NeedsUserAction) {
                kept[key] = staged
                runCatching { context.startActivity(result.settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            if (outcome.removeDownload) removeAfter()
            busy.remove(key)
        }
    }
}

/** Null [expected]: nothing to compare. Otherwise the staged copy must hash to what the download was verified against. */
internal fun checksumMatches(expected: String?, actual: String) = expected == null || expected.equals(actual, ignoreCase = true)

@Composable
internal fun rememberApkFlow(keepApk: Boolean, open: (Overlay) -> Unit): ApkFlow {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keep by rememberUpdatedState(keepApk)
    val openNow by rememberUpdatedState(open)
    val flow = remember { ApkFlow(context, scope, { openNow(it) }, { keep }) }
    DisposableEffect(flow) { onDispose { flow.dispose() } }
    return flow
}
