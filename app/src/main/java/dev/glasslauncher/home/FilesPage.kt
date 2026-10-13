package dev.glasslauncher.home

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import dev.glasslauncher.R
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.downloads.DownloadState
import dev.glasslauncher.downloads.DownloadText
import dev.glasslauncher.downloads.Downloads
import dev.glasslauncher.downloads.FileBrowser
import dev.glasslauncher.downloads.FileEntry
import dev.glasslauncher.downloads.FileKind
import dev.glasslauncher.system.RemoteNotice
import dev.glasslauncher.ui.Hint
import dev.glasslauncher.ui.MenuRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** The file browser (P119): the Downloads folder, with one level of subfolders. */
@Composable
fun FilesPage(
    cfg: LauncherConfig,
    active: Boolean,
    open: (Overlay) -> Unit,
) {
    val apk = rememberApkFlow(cfg.downloads.keepInstalledApks, open)
    val context = LocalContext.current
    val root = remember { Downloads.folder(context) }
    val queue = remember { Downloads.queue(context) }
    val items by queue.items.collectAsState()
    val changes by FileBrowser.changes.collectAsState()
    var sub by remember { mutableStateOf<File?>(null) }
    val folder = sub ?: root
    // Listed off the main thread; again when a download finishes, a file changes, or this page comes back to the front.
    val state by produceState<Pair<List<FileEntry>, Long>?>(null, folder, items, changes, active) {
        value = withContext(Dispatchers.IO) { FileBrowser.list(folder, if (sub == null) FileBrowser.busyNames(items) else emptySet()) to folder.usableSpace }
    }
    val listed = state
    CompositionLocalProvider(LocalPageKey provides Overlay.Files) {
        FilesList(
            entries = listed?.first.orEmpty(), loaded = listed != null, free = listed?.second, inFolder = sub?.name, active = active,
            onSelect = { e ->
                when {
                    e.dir -> sub = e.file
                    !e.file.exists() -> RemoteNotice.show(context, "That file is gone")
                    FileBrowser.kind(e) == FileKind.Apk -> {
                        // A file that came from a download with a published hash was already checked; anything else is unchecked.
                        val checked = items.any { it.state == DownloadState.Done && it.file == e.name && sub == null && it.expectedSha256 != null }
                        apk.start(e.file.path, e.file, !checked) { queue.deleteFile(e.file) }
                    }
                    else -> DownloadText.open(context, e.file)
                }
            },
            onMenu = { open(Overlay.FileMenu(it.file.path)) },
            onUp = { sub = null },
        )
    }
}

/** The list itself: no storage, no queue, so a preview can draw it with made-up files. */
@Composable
internal fun FilesList(
    entries: List<FileEntry>,
    loaded: Boolean,
    free: Long?,
    inFolder: String?,
    active: Boolean,
    onSelect: (FileEntry) -> Unit,
    onMenu: (FileEntry) -> Unit,
    onUp: () -> Unit,
) {
    val sink = LocalTitleSink.current
    val pageKey = LocalPageKey.current
    DisposableEffect(sink, pageKey) {
        sink?.wide = pageKey
        onDispose { if (sink != null && sink.wide == pageKey) sink.wide = null }
    }
    MenuList(active) { first ->
        PanelTitle(inFolder ?: "Files")
        if (free != null) Hint("${DownloadText.bytes(free)} free on this TV.")
        var firstRow = Modifier.focusRequester(first)
        fun f(): Modifier = firstRow.also { firstRow = Modifier }
        if (inFolder != null) MenuRow("Downloads", onUp, f().testTag("files-up"), leading = { MenuIcon(R.drawable.ic_folder) })
        if (loaded && entries.isEmpty()) Hint("No files yet. Downloads you finish show up here.")
        entries.forEach { e ->
            key(e.file.path) {
                MenuRow(
                    e.name, { onSelect(e) },
                    // The app-menu key (≡) opens the row's actions; the row's own handler leaves it alone, so it bubbles here.
                    f().testTag("file:${e.name}").onKeyEvent { k ->
                        val menu = k.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
                        if (menu && k.nativeKeyEvent.action == KeyEvent.ACTION_UP) onMenu(e)
                        menu
                    },
                    value = FileText.detail(e),
                    leading = { MenuIcon(FileText.icon(FileBrowser.kind(e))) },
                    chevron = e.dir,
                )
            }
        }
    }
}

/** How the browser words a row. */
internal object FileText {
    fun detail(e: FileEntry): String {
        val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(e.modified)
        return if (e.dir) date else "${DownloadText.bytes(e.size)} · $date"
    }

    fun icon(kind: FileKind): Int = when (kind) {
        FileKind.Folder -> R.drawable.ic_folder
        FileKind.Apk -> R.drawable.ic_apps
        FileKind.Video -> R.drawable.ic_movie
        FileKind.Audio -> R.drawable.ic_music_note
        FileKind.Image -> R.drawable.ic_image
        FileKind.Other -> R.drawable.ic_description
    }
}

/** The app-menu key's list on one file: open it, rename, check its hash, delete. */
@Composable
internal fun FileMenuBody(path: String, active: Boolean, open: (Overlay) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(path) { File(path) }
    var name by remember(path) { mutableStateOf(file.name) }
    val here = File(file.parentFile, name)
    var deleted by remember(path) { mutableStateOf(false) }
    val gone = deleted || !here.exists()
    LaunchedEffect(gone) { if (gone) close() }
    if (gone) return
    MenuList(active) { first ->
        PanelTitle(name)
        var firstRow = Modifier.focusRequester(first)
        fun f(): Modifier = firstRow.also { firstRow = Modifier }
        if (!here.isDirectory) MenuRow("Open", {
            if (here.extension.equals("apk", true)) RemoteNotice.show(context, "Select the file in the list to install it") else DownloadText.open(context, here)
        }, f())
        MenuRow("Rename", {
            open(Overlay.TextInput("Rename", name, "A new name for this ${if (here.isDirectory) "folder" else "file"}.") { text ->
                val error = FileBrowser.rename(here, text)
                if (error != null) RemoteNotice.show(context, error) else if (text.trim() != name) {
                    forget(context, here)
                    name = text.trim()
                    FileBrowser.changes.value++
                }
            })
        }, f())
        if (!here.isDirectory) MenuRow("Show Hash", {
            RemoteNotice.show(context, "Checking ${name}…", 2_000)
            scope.launch {
                val hash = runCatching { withContext(Dispatchers.IO) { FileBrowser.sha256(here) } }.getOrNull()
                if (hash == null) RemoteNotice.show(context, "Couldn't read that file") else open(Overlay.Confirm("SHA-256", FileBrowser.hashLines(hash), "Done") {})
            }
        }, f())
        MenuRow("Delete", {
            open(Overlay.Confirm("Delete “$name”?", if (here.isDirectory) "The folder is removed if it is empty." else "The file is removed from this TV.", "Delete", destructive = true) {
                if (Downloads.queue(context).deleteFile(here)) { FileBrowser.changes.value++; deleted = true } else RemoteNotice.show(context, "Couldn't delete that")
            })
        }, f(), destructive = true)
    }
}

/** A finished download whose file is gone or renamed would only say "That file is gone": take it off the manager's list. */
private fun forget(context: Context, file: File) {
    if (file.parentFile != Downloads.folder(context)) return
    val queue = Downloads.queue(context)
    queue.items.value.filter { it.state == DownloadState.Done && it.file == file.name }.forEach { queue.remove(it.id, deleteFile = false) }
}
