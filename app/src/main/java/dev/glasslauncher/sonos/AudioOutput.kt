package dev.glasslauncher.sonos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.glasslauncher.data.AudioOutputConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AudioOutputs {
    /** How the volume row reads a speaker's volume (GetVolume). Screenshot tests replace it; nothing else does. */
    @Volatile var volumeReader: suspend (host: String) -> Int = { host -> SonosControl("http://$host:1400").getVolume() }

    /** A row's one-line subtitle: a selected speaker shows the route's state ("Connecting…", an opt-out) when there is one. */
    fun subtitle(row: PickerRow, route: RouteState): String? =
        if (row.selected && row.network != null) RouteLifecycle.label(route) ?: row.subtitle else row.subtitle
}

/** What a click on a row asks the route to do. There is no volume command: selecting never sets a volume. */
sealed interface OutputCommand {
    data object Stop : OutputCommand
    /** The first speaker(s): asks for consent, then streams. */
    data class Start(val speakers: List<AudioOutputConfig>) : OutputCommand
    /** Another speaker on a route that already plays (no new consent). */
    data class Add(val speaker: AudioOutputConfig) : OutputCommand
    data class Remove(val host: String) : OutputCommand
}

/** The new stored choice (empty = This TV) and the route commands to run, in order. */
data class OutputChange(val choice: List<AudioOutputConfig>, val commands: List<OutputCommand>)

/**
 * tvOS's picker (rendererWasSelected toggles membership): a click on a network speaker adds it or takes it off, several at once
 * (multi-room). The first one starts the capture route, the others join it, the last one off stops it; This TV stops any
 * route. Bluetooth is not here: it is one sink at a time, through the system's A2DP route.
 */
object OutputToggle {
    fun click(current: List<AudioOutputConfig>, target: AudioOutputConfig?): OutputChange = when {
        target == null -> if (current.isEmpty()) OutputChange(emptyList(), emptyList()) else OutputChange(emptyList(), listOf(OutputCommand.Stop))
        current.any { it.host == target.host } -> {
            val rest = current.filter { it.host != target.host }
            OutputChange(rest, listOf(if (rest.isEmpty()) OutputCommand.Stop else OutputCommand.Remove(target.host)))
        }
        current.isEmpty() -> OutputChange(listOf(target), listOf(OutputCommand.Start(listOf(target))))
        else -> OutputChange(current + target, listOf(OutputCommand.Add(target)))
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
