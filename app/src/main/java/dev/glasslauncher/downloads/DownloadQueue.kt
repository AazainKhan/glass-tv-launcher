package dev.glasslauncher.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

    /** Reads the saved queue. Downloads that were running are queued again; nothing starts until [startPending]. */
    @Synchronized
    fun load() {
        val saved = runCatching { json.decodeFromString<QueueFile>(stateFile.readText()) }.getOrNull() ?: return
        concurrency = saved.concurrency.coerceIn(1, 4)
        segments = saved.segments.coerceIn(1, 8)
        _items.value = saved.items.map { if (it.state == DownloadState.Active) it.copy(state = DownloadState.Queued, bytesPerSecond = 0) else it }
    }

    /** Starts whatever is waiting (after [load], or when the service is restarted). */
    @Synchronized
    fun startPending() = pump()

    @Synchronized
    fun add(url: String, name: String? = null, sha256: String? = null, paused: Boolean = false): String {
        require(url.toHttpUrlOrNull() != null) { "Not an http(s) URL: $url" }
        val item = DownloadItem(
            UUID.randomUUID().toString(), url, name, sha256,
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

    @Synchronized
    fun pauseAll() = _items.value.forEach { pause(it.id) }

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
            try {
                val done = engine.download(DownloadRequest(item.id, item.url, dir, item.name, item.expectedSha256, segments)) { p ->
                    patch(item.id) { it.copy(downloaded = p.downloaded, size = p.size, bytesPerSecond = p.bytesPerSecond, restarted = it.restarted || p.restarted) }
                }
                finish(item.id) {
                    it.copy(state = DownloadState.Done, file = done.file.name, size = done.size, downloaded = done.size, bytesPerSecond = 0,
                        sha256 = done.sha256, finalUrl = done.finalUrl, restarted = it.restarted || done.restarted, error = null)
                }
            } catch (e: CancellationException) {
                throw e // pause and remove already set the state
            } catch (e: Exception) {
                finish(item.id) { it.copy(state = DownloadState.Failed, bytesPerSecond = 0, error = e.message ?: e.javaClass.simpleName) }
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

    private fun save() {
        val data = QueueFile(concurrency, segments, _items.value)
        runCatching {
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.path + ".tmp")
            tmp.writeText(json.encodeToString(QueueFile.serializer(), data))
            tmp.renameTo(stateFile)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
