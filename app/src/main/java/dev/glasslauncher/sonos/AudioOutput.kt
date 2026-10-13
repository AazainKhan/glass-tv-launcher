package dev.glasslauncher.sonos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.glasslauncher.data.AudioOutputConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One line of the platter's Audio Output list. [speaker] is null for "This TV". */
data class AudioOutputRow(val speaker: AudioOutputConfig?, val title: String, val subtitle: String?, val selected: Boolean) {
    val key: String get() = speaker?.host ?: THIS_TV_KEY

    companion object { const val THIS_TV_KEY = "this-tv" }
}

object AudioOutputs {
    const val THIS_TV = "This TV"

    /**
     * "This TV" first, then the speakers found, ordered by room name. A stored choice the scan did not find (the
     * speaker is off or the network is down) stays listed so it still shows its checkmark and can be cleared.
     */
    fun rows(found: List<SonosSpeaker>, selected: AudioOutputConfig?): List<AudioOutputRow> {
        val speakers = found.distinctBy { it.host }.sortedBy { it.roomName.lowercase() }
            .map { AudioOutputRow(AudioOutputConfig(it.host, it.roomName), it.roomName.ifBlank { it.host }, it.modelName.ifBlank { null }, it.host == selected?.host) }
        val missing = if (selected != null && speakers.none { it.selected }) {
            listOf(AudioOutputRow(selected, selected.room.ifBlank { selected.host }, "Not found", true))
        } else emptyList()
        return listOf(AudioOutputRow(null, THIS_TV, null, selected == null)) + missing + speakers
    }

    /** How the volume row reads a speaker's volume (GetVolume). Screenshot tests replace it; nothing else does. */
    @Volatile var volumeReader: suspend (host: String) -> Int = { host -> SonosControl("http://$host:1400").getVolume() }

    /** A row's one-line subtitle: the selected speaker shows the route's state ("Connecting…", an opt-out) when there is one. */
    fun subtitle(row: AudioOutputRow, route: RouteState): String? =
        if (row.selected && row.speaker != null) RouteLifecycle.label(route) ?: row.subtitle else row.subtitle

    /** The choice a row stores: null for "This TV". Nothing here touches the speaker, least of all its volume. */
    fun choice(row: AudioOutputRow): AudioOutputConfig? = row.speaker
}

/** What a click on a row asks the route to do. There is no volume command: selecting never sets a volume. */
sealed interface OutputCommand {
    data object Stop : OutputCommand
    data class Start(val speaker: AudioOutputConfig) : OutputCommand
}

/** The new stored choice (null = This TV) and the route commands to run, in order. */
data class OutputChange(val choice: AudioOutputConfig?, val commands: List<OutputCommand>)

/**
 * tvOS's picker (rendererWasSelected): a click toggles the row, applied at once, one output at a time (no multi-select).
 * A speaker starts the capture route, the selected speaker again stops it and returns to This TV, This TV stops any
 * route, and another speaker replaces the current one (stop, then start).
 */
object OutputToggle {
    fun click(current: AudioOutputConfig?, row: AudioOutputRow): OutputChange {
        val target = row.speaker
        return when {
            target == null -> if (current == null) OutputChange(null, emptyList()) else OutputChange(null, listOf(OutputCommand.Stop))
            current?.host == target.host -> OutputChange(null, listOf(OutputCommand.Stop))
            current == null -> OutputChange(target, listOf(OutputCommand.Start(target)))
            else -> OutputChange(target, listOf(OutputCommand.Stop, OutputCommand.Start(target)))
        }
    }
}

/**
 * The volume row under the selected speaker. [load] shows what GetVolume returned and never sends; only [nudge] (the
 * user pressing Left or Right on the row) calls [send], so selecting a speaker can never change its volume.
 */
class VolumeRowModel(private val step: Int = 5, private val send: (Int) -> Unit) {
    var volume: Int? by androidx.compose.runtime.mutableStateOf<Int?>(null)
        private set

    fun load(v: Int) { if (volume == null) volume = v.coerceIn(0, 100) }

    /** [direction] is -1 or 1. False when the volume is not known yet or already at the end. */
    fun nudge(direction: Int): Boolean {
        val now = volume ?: return false
        val next = (now + direction * step).coerceIn(0, 100)
        if (next == now) return false
        volume = next
        send(next)
        return true
    }
}

/** Sends at most one volume per [intervalMs]; while one is pending only the latest value is kept. The first goes at once. */
class ThrottledVolume(scope: kotlinx.coroutines.CoroutineScope, private val intervalMs: Long, private val send: suspend (Int) -> Unit) {
    private val queue = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.CONFLATED)

    init {
        scope.launch { for (v in queue) { send(v); delay(intervalMs) } }
    }

    fun set(volume: Int) { queue.trySend(volume) }

    /** Lets a pending value go out, then ends. */
    fun close() { queue.close() }
}

/** Runs [scan] at most once per [minIntervalMs]; in between, callers get the cached result. */
class ThrottledScan<T>(
    private val minIntervalMs: Long,
    initial: T,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scan: suspend () -> T,
) {
    private val lock = Mutex()
    private var lastRun: Long? = null
    @Volatile var cached: T = initial
        private set

    /** The cached result, refreshed first when the last scan is older than the interval. A failed scan keeps the cache. */
    suspend fun refresh(): T = lock.withLock {
        val now = clock()
        val last = lastRun
        if (last == null || now - last >= minIntervalMs || now < last) {
            lastRun = now
            cached = try { scan() } catch (e: kotlinx.coroutines.CancellationException) { lastRun = last; throw e } catch (_: Exception) { cached }
        }
        cached
    }
}

/** Process-wide cache so reopening the platter shows the last speakers at once. */
object SonosScan {
    private var scanner: ThrottledScan<List<SonosSpeaker>>? = null

    @Synchronized
    fun get(context: android.content.Context): ThrottledScan<List<SonosSpeaker>> =
        scanner ?: ThrottledScan(10_000L, emptyList()) { SonosDiscovery(context.applicationContext).discover() }.also { scanner = it }
}
