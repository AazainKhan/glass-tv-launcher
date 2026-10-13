package dev.glasslauncher.home

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.downloads.CodeIndex
import dev.glasslauncher.downloads.DownloadItem
import dev.glasslauncher.downloads.DownloadPlanner
import dev.glasslauncher.downloads.DownloadState
import dev.glasslauncher.downloads.DownloadText
import dev.glasslauncher.downloads.Downloads
import dev.glasslauncher.downloads.Plan
import dev.glasslauncher.downloads.forApp
import dev.glasslauncher.app
import dev.glasslauncher.downloads.installOutcome
import dev.glasslauncher.system.ApkInstall
import dev.glasslauncher.system.InstallResult
import dev.glasslauncher.system.RemoteNotice
import dev.glasslauncher.system.StagedApk
import dev.glasslauncher.ui.Hint
import dev.glasslauncher.ui.MenuRow
import dev.glasslauncher.ui.SectionLabel
import dev.glasslauncher.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The download manager (P119): everything queued, running, paused and finished, and where a new link or code is entered. */
@Composable
fun DownloadsPage(
    model: HomeModel,
    cfg: LauncherConfig,
    active: Boolean,
    open: (Overlay) -> Unit,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val queue = remember { Downloads.queue(context) }
    val items by queue.items.collectAsState()
    val scope = rememberCoroutineScope()
    val config by rememberUpdatedState(cfg)
    // Rows being staged or installed (Select on them is ignored), and staged copies kept for the retry after "allow installs".
    val busy = remember { mutableSetOf<String>() }
    val kept = remember { mutableMapOf<String, StagedApk>() }
    DisposableEffect(Unit) { onDispose { kept.values.forEach { it.discard() }; kept.clear() } }

    fun install(item: DownloadItem, staged: StagedApk) {
        kept.remove(item.id)
        // The app scope: leaving this page must not cancel an install that is already running.
        context.app.scope.launch {
            val result = ApkInstall.install(context, staged)
            val outcome = installOutcome(result, staged.info.label, config.downloads.keepInstalledApks)
            RemoteNotice.show(context, outcome.notice, 4_000)
            if (result is InstallResult.NeedsUserAction) {
                kept[item.id] = staged
                runCatching { context.startActivity(result.settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            if (outcome.removeDownload) queue.remove(item.id, deleteFile = true)
            busy.remove(item.id)
        }
    }

    fun askInstall(item: DownloadItem, staged: StagedApk) {
        open(Overlay.InstallConfirm(
            staged,
            onConfirm = { install(item, staged) },
            onDiscard = { staged.discard(); kept.remove(item.id); busy.remove(item.id) },
            unchecked = item.expectedSha256 == null,
        ))
    }

    fun onInstall(item: DownloadItem, file: File) {
        if (!busy.add(item.id)) return
        kept[item.id]?.let { askInstall(item, it); return }
        scope.launch {
            val staged = withContext(Dispatchers.IO) { runCatching { StagedApk.stage(context, file) }.getOrNull() }
            if (staged == null) { RemoteNotice.show(context, "Couldn't read that APK"); busy.remove(item.id) } else askInstall(item, staged)
        }
    }

    fun begin(plan: Plan.Start) {
        val d = config.downloads
        queue.setSegments(d.segments)
        queue.setConcurrency(d.concurrent)
        runCatching { queue.add(plan.url, plan.name, plan.sha256) }
            .onSuccess { RemoteNotice.show(context, "Downloading ${plan.name ?: DownloadText.name(DownloadItem("", plan.url))}") }
            .onFailure { RemoteNotice.show(context, it.message ?: "Couldn't start that download") }
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        scope.launch {
            val d = config.downloads
            val plan = DownloadPlanner.plan(text, d.codes, d.acceptedUrls) { CodeIndex.forApp(context).load() }
            when (plan) {
                is Plan.Refuse -> RemoteNotice.show(context, plan.message, 4_000)
                is Plan.Start -> {
                    model.edit { it.copy(downloads = it.downloads.copy(history = dev.glasslauncher.downloads.DownloadInput.pushHistory(it.downloads.history, text))) }
                    if (plan.confirmHost == null) begin(plan) else open(
                        Overlay.Confirm("Download from ${plan.confirmHost}?", "Code “${plan.code}” points to ${plan.url}. Glass asks once for each link.${DownloadText.checksumNote(plan.url, plan.name, plan.sha256)}", "Download") {
                            model.edit { it.copy(downloads = it.downloads.copy(acceptedUrls = dev.glasslauncher.downloads.DownloadInput.accept(it.downloads.acceptedUrls, plan.url))) }
                            begin(plan)
                        },
                    )
                }
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalPageKey provides Overlay.Downloads) { DownloadsList(
        items, active,
        onAdd = {
            open(Overlay.TextInput("Add Download", "", "A link, or a code you saved.", config.downloads.history, { clipboardUrl(context) }) { submit(it) })
        },
        onSelect = { item ->
            when (item.state) {
                DownloadState.Active, DownloadState.Queued -> queue.pause(item.id)
                DownloadState.Paused, DownloadState.Failed -> retry(queue, item, open)
                DownloadState.Done -> {
                    val file = File(Downloads.folder(context), item.file ?: return@DownloadsList)
                    when {
                        !file.exists() -> RemoteNotice.show(context, "That file is gone")
                        DownloadText.isApk(item) -> onInstall(item, file)
                        else -> DownloadText.open(context, file)
                    }
                }
            }
        },
        onMenu = { open(Overlay.DownloadMenu(it.id)) },
        onPauseAll = { queue.pauseAll() },
        onDeleteFinished = {
            open(Overlay.Confirm("Delete Finished Downloads?", "Every finished and failed download is removed, and so are the files. Downloads in progress stay.", "Delete", destructive = true) {
                queue.removeFinished(deleteFiles = true)
            })
        },
    ) }
}

/** The list itself: no queue, no config, so a preview can draw it with made-up downloads. */
@Composable
internal fun DownloadsList(
    items: List<DownloadItem>,
    active: Boolean,
    onAdd: () -> Unit,
    onSelect: (DownloadItem) -> Unit,
    onMenu: (DownloadItem) -> Unit,
    onPauseAll: () -> Unit,
    /** Null: no bulk-delete row (the previews). */
    onDeleteFinished: (() -> Unit)? = null,
) {
    // Rows carry a progress bar and a status: this page uses the whole width, with no icon column.
    val sink = LocalTitleSink.current
    val pageKey = LocalPageKey.current
    DisposableEffect(sink, pageKey) {
        sink?.wide = pageKey
        onDispose { if (sink != null && sink.wide == pageKey) sink.wide = null }
    }
    MenuList(active) { first ->
        PanelTitle("Downloads")
        MenuRow("Add Download", onAdd, Modifier.focusRequester(first).testTag("dl-add"), chevron = true)
        if (items.isEmpty()) Hint("Nothing downloaded yet. Add a link, or a code you saved in Settings.")
        val running = items.filter { it.state == DownloadState.Active || it.state == DownloadState.Queued || it.state == DownloadState.Paused }
        val finished = items.filter { it.state == DownloadState.Done || it.state == DownloadState.Failed }
        if (running.isNotEmpty()) {
            SectionLabel("In progress")
            running.forEach { key(it.id) { DownloadRow(it, onSelect, onMenu) } }
        }
        if (finished.isNotEmpty()) {
            SectionLabel("Finished")
            finished.forEach { key(it.id) { DownloadRow(it, onSelect, onMenu) } }
        }
        if (items.any { it.state == DownloadState.Active || it.state == DownloadState.Queued }) MenuRow("Pause All", onPauseAll)
        if (onDeleteFinished != null && finished.isNotEmpty()) MenuRow("Delete Finished Downloads", onDeleteFinished, destructive = true)
    }
}

@Composable
private fun ColumnScope.DownloadRow(item: DownloadItem, onSelect: (DownloadItem) -> Unit, onMenu: (DownloadItem) -> Unit) {
    MenuRow(
        DownloadText.name(item),
        { onSelect(item) },
        // The app-menu key (≡) opens the row's actions; the row's own handler leaves it alone, so it bubbles here.
        Modifier.testTag("download:${item.id}").onKeyEvent { e ->
            val menu = e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
            if (menu && e.nativeKeyEvent.action == KeyEvent.ACTION_UP) onMenu(item)
            menu
        },
        value = DownloadText.detail(item),
        trailing = {
            if (item.state != DownloadState.Done && item.state != DownloadState.Failed) ProgressCapsule(DownloadText.fraction(item))
            if (item.state != DownloadState.Failed) Text(
                DownloadText.status(item), style = Type.secondary, color = LocalContentColor.current.copy(alpha = 0.75f),
                maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(150.dp),
            )
        },
    )
}

@Composable
private fun ProgressCapsule(fraction: Float) {
    val color = LocalContentColor.current
    Box(Modifier.size(110.dp, 6.dp).clip(CircleShape).background(color.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(color))
    }
}

/** Continues a download; one that stopped at a redirect to another site asks first, naming the site. */
private fun retry(queue: dev.glasslauncher.downloads.DownloadQueue, item: DownloadItem, open: (Overlay) -> Unit) {
    val host = item.blockedHost
    if (item.state == DownloadState.Failed && host != null) {
        open(Overlay.Confirm("Continue to $host?", "The link you gave sends this download to a different site.", "Continue") { queue.allowRedirect(item.id, host) })
    } else queue.resume(item.id)
}

/** The app-menu key's list on one download: what can be done depends on where it is. */
@Composable
internal fun DownloadMenuBody(id: String, active: Boolean, open: (Overlay) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val queue = remember { Downloads.queue(context) }
    val item = queue.items.collectAsState().value.firstOrNull { it.id == id }
    LaunchedEffect(item == null) { if (item == null) close() }
    item ?: return
    MenuList(active) { first ->
        var firstRow = Modifier.focusRequester(first)
        fun f(): Modifier = firstRow.also { firstRow = Modifier }
        PanelTitle(DownloadText.name(item))
        val waiting = item.state == DownloadState.Active || item.state == DownloadState.Queued || item.state == DownloadState.Paused
        if (item.state == DownloadState.Failed) MenuRow("Retry", { retry(queue, item, open); close() }, f())
        if (waiting) MenuRow("Move to Top", { queue.moveToTop(id); close() }, f())
        if (waiting) MenuRow("Cancel Download", { queue.remove(id, deleteFile = true); close() }, f(), destructive = true)
        if (!waiting) MenuRow("Remove from List", { queue.remove(id, deleteFile = false); close() }, f())
        if (item.state == DownloadState.Done) MenuRow("Delete File", {
            open(Overlay.Confirm("Delete “${DownloadText.name(item)}”?", "The file is removed from this TV.", "Delete", destructive = true) {
                queue.remove(id, deleteFile = true); close()
            })
        }, f(), destructive = true)
    }
}

/** The clipboard's text when it is a link Glass can download. Read only when the user selects Paste. */
private fun clipboardUrl(context: Context): String? = runCatching {
    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
    dev.glasslauncher.downloads.DownloadInput.linkOrNull(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString())
}.getOrNull()
