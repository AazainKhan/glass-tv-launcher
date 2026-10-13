package dev.glasslauncher.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.UUID

@Serializable
private data class QueueFile(
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val segments: Int = DEFAULT_SEGMENTS,
    val items: List<DownloadItem> = emptyList(),
)

/** The persisted download list and its scheduler. List order is queue order. */
class DownloadQueue(
    private val scope: CoroutineScope,
    private val engine: DownloadEngine,
    private val dir: File,
    private val stateFile: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val saveDelayMs: Long = 300,
    /** Tests serve from a local http server; the app never does. */
    private val allowCleartext: Boolean = false,
    /** Called whenever a download starts, so the foreground service can come up. */
    private val onWork: () -> Unit = {},
) {
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items

    var concurrency = DEFAULT_CONCURRENCY
        private set
    var segments = DEFAULT_SEGMENTS
        private set

    private val jobs = HashMap<String, Job>()
    private var saving: Job? = null
    private val fileLock = Any()
    private val saveLock = Any()
    private var pending = false
    private var dirty = false

    /** Tests pause a write here to change the list while it is in flight. */
    @Volatile internal var afterSnapshot: (() -> Unit)? = null

    /** Reads the saved queue. Downloads that were running are queued again; nothing starts until [startPending]. */
    @Synchronized
    fun load() {
        val saved = runCatching { json.decodeFromString<QueueFile>(stateFile.readText()) }.getOrNull() ?: return
        concurrency = saved.concurrency.coerceIn(1, 4)
        segments = saved.segments.coerceIn(1, 8)
        _items.value = saved.items.map { if (it.state == DownloadState.Active) it.copy(state = DownloadState.Queued, bytesPerSecond = 0) else it }
        scope.launch(io) { DownloadEngine.sweep(dir) { _items.value.mapTo(HashSet()) { it.id } } }
    }

    /** Starts whatever is waiting (after [load], or when the service is restarted). */
    @Synchronized
    fun startPending() = pump()

    @Synchronized
    fun add(url: String, name: String? = null, sha256: String? = null, paused: Boolean = false, guardExec: Boolean = false): String {
        val parsed = url.toHttpUrlOrNull()
        require(parsed != null && (parsed.isHttps || allowCleartext)) { "Downloads need an https link" }
        val item = DownloadItem(
            UUID.randomUUID().toString(), url, name, sha256, guardExec = guardExec,
            state = if (paused) DownloadState.Paused else DownloadState.Queued,
        )
        _items.update { it + item }
        pump()
        return item.id
    }

    @Synchronized
    fun pause(id: String) {
        if (state(id) != DownloadState.Active && state(id) != DownloadState.Queued) return
        set(id) { it.copy(state = DownloadState.Paused, bytesPerSecond = 0) }
        jobs[id]?.cancel()
        pump()
    }

    /** Continues a paused download or retries a failed one. */
    @Synchronized
    fun resume(id: String) {
        if (state(id) != DownloadState.Paused && state(id) != DownloadState.Failed) return
        set(id) { it.copy(state = DownloadState.Queued, error = null) }
        pump()
    }

    /** Marks everything paused first, so cancelling one run cannot let the scheduler start the next. */
    @Synchronized
    fun pauseAll() {
        val waiting = _items.value.filter { it.state == DownloadState.Active || it.state == DownloadState.Queued }.map { it.id }.toSet()
        if (waiting.isEmpty()) return
        _items.update { list -> list.map { if (it.id in waiting) it.copy(state = DownloadState.Paused, bytesPerSecond = 0) else it } }
        waiting.forEach { jobs[it]?.cancel() }
        pump()
    }

    /** Takes the finished and failed downloads off the list; [deleteFiles] also deletes the files. */
    @Synchronized
    fun removeFinished(deleteFiles: Boolean) {
        _items.value.filter { it.state == DownloadState.Done || it.state == DownloadState.Failed }.forEach { remove(it.id, deleteFiles) }
    }

    /** Deletes a file in the downloads folder on IO, before returning; a finished download's row goes only if the file did. */
    suspend fun deleteFile(file: File): FileBrowser.Deleted {
        val result = withContext(io) { FileBrowser.delete(file) }
        if (result == FileBrowser.Deleted.Ok && file.parentFile == dir) synchronized(this) {
            _items.value.firstOrNull { it.state == DownloadState.Done && it.file == file.name }?.let { remove(it.id, deleteFile = false) }
        }
        return result
    }

    /** The user said yes to an app file the server turned out to send: continue, without the check. */
    @Synchronized
    fun allowExecutable(id: String) {
        if (state(id) != DownloadState.Failed) return
        set(id) { it.copy(guardExec = false, blockedExec = null) }
        resume(id)
    }

    /** Lets a download that stopped at a redirect continue to [host]. */
    @Synchronized
    fun allowRedirect(id: String, host: String) {
        if (state(id) != DownloadState.Failed) return
        set(id) { it.copy(allowedHosts = it.allowedHosts + host, blockedHost = null) }
        resume(id)
    }

    @Synchronized
    fun moveToTop(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        _items.update { listOf(item) + it.filter { other -> other.id != id } }
        save()
    }

    /** Takes the download off the list and deletes its partial file, and the finished file when [deleteFile]. */
    @Synchronized
    fun remove(id: String, deleteFile: Boolean = true) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        _items.update { list -> list.filter { it.id != id } }
        val job = jobs[id]
        job?.cancel()
        scope.launch {
            job?.join()
            DownloadEngine.discard(dir, id)
            if (deleteFile) item.file?.let { File(dir, it).delete() }
        }
        pump()
    }

    @Synchronized
    fun setConcurrency(n: Int) {
        concurrency = n.coerceIn(1, 4); pump()
    }

    @Synchronized
    fun setSegments(n: Int) {
        segments = n.coerceIn(1, 8); save()
    }

    private fun state(id: String) = _items.value.firstOrNull { it.id == id }?.state

    private fun set(id: String, change: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) change(it) else it } }
        save()
    }

    private fun pump() {
        val room = concurrency - _items.value.count { it.state == DownloadState.Active }
        _items.value.filter { it.state == DownloadState.Queued }.take(room.coerceAtLeast(0)).forEach { start(it) }
        save()
    }

    private fun start(item: DownloadItem) {
        set(item.id) { it.copy(state = DownloadState.Active) }
        onWork()
        val previous = jobs[item.id]
        jobs[item.id] = scope.launch {
            previous?.join() // a paused run may still be writing its sidecar
            if (state(item.id) != DownloadState.Active) return@launch // the old run finished it, or it was paused again
            try {
                val done = engine.download(DownloadRequest(item.id, item.url, dir, item.name, item.expectedSha256, segments, item.allowedHosts, item.guardExec), onFinished = { finish(item.id, it) }) { p ->
                    patch(item.id) { it.copy(downloaded = p.downloaded, size = p.size, bytesPerSecond = p.bytesPerSecond, restarted = it.restarted || p.restarted) }
                }
                finish(item.id, done)
            } catch (e: CancellationException) {
                throw e // pause and remove already set the state
            } catch (e: Exception) {
                finish(item.id) {
                    it.copy(state = DownloadState.Failed, bytesPerSecond = 0, error = e.message ?: e.javaClass.simpleName,
                        blockedHost = (e as? DownloadFailure.CrossHost)?.host, blockedExec = (e as? DownloadFailure.Executable)?.name)
                }
            }
        }
    }

    // Progress is not saved: the sidecar holds the real offsets, and the list only needs states.
    private fun patch(id: String, change: (DownloadItem) -> DownloadItem) =
        _items.update { list -> list.map { if (it.id == id && it.state == DownloadState.Active) change(it) else it } }

    @Synchronized
    private fun finish(id: String, change: (DownloadItem) -> DownloadItem) {
        if (state(id) != DownloadState.Active) return
        set(id, change)
        pump()
    }

    /** A file that finished is kept even if Pause arrived first: the part file is gone, so it cannot be resumed. */
    @Synchronized
    internal fun finish(id: String, done: Finished) {
        val s = state(id)
        if (s != DownloadState.Active && s != DownloadState.Paused) return
        set(id) {
            it.copy(state = DownloadState.Done, file = done.file.name, size = done.size, downloaded = done.size, bytesPerSecond = 0,
                sha256 = done.sha256, finalUrl = done.finalUrl, restarted = it.restarted || done.restarted, error = null, blockedHost = null)
        }
        pump()
    }

    // Many state changes come in a burst; one write after a short quiet beats a disk write on Main for each.
    private fun save() {
        synchronized(saveLock) {
            if (pending) { dirty = true; return } // a write is queued or running: it goes round again
            pending = true
        }
        saving = scope.launch(io) {
            delay(saveDelayMs)
            while (true) {
                ensureActive() // flush() cancels this job; an old writer must not keep looping
                synchronized(saveLock) { dirty = false }
                write()
                synchronized(saveLock) { if (!dirty) { pending = false; return@launch } }
            }
        }
    }

    /** Writes the list now; for tests and for a clean exit. */
    internal suspend fun flush() = withContext(io) { saving?.cancel(); synchronized(saveLock) { pending = false; dirty = false }; write() }

    private fun write() {
        synchronized(fileLock) {
            // Snapshot inside the lock: a writer that waited here must not write an older list over a newer file.
            val data = QueueFile(concurrency, segments, _items.value)
            afterSnapshot?.invoke()
            runCatching {
                stateFile.parentFile?.mkdirs()
                val tmp = File(stateFile.path + ".tmp")
                tmp.writeText(json.encodeToString(QueueFile.serializer(), data))
                tmp.renameTo(stateFile)
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
