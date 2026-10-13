package dev.glasslauncher.sonos

import dev.glasslauncher.data.AudioOutputConfig
import kotlinx.coroutines.isActive
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

    /** The choice a row stores: null for "This TV". Nothing here touches the speaker, least of all its volume. */
    fun choice(row: AudioOutputRow): AudioOutputConfig? = row.speaker

    fun notice(row: AudioOutputRow): String? =
        if (row.speaker != null) "Sonos output selected. Audio routing comes in the next update." else null
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

/** Where Glass's audio goes. [SonosRoutes] drives the capture route; this interface is the seam for other sources. */
interface AudioRoute {
    /** Starts routing [source], 48 kHz 16-bit stereo little-endian PCM, to the output. */
    suspend fun start(source: AudioSource)
    suspend fun stop()
}

/** Where PCM comes from; the implementation is the next part of P121. */
fun interface AudioSource {
    /** Fills [buf] with PCM and returns the byte count, or -1 at the end. */
    fun read(buf: ByteArray): Int
}

/** Routes [source] to one Sonos speaker through [SonosSession]: starts the session, then pumps PCM into its ring. Never sets the volume. */
class SonosAudioRoute(private val session: SonosSession) : AudioRoute {
    private var pump: kotlinx.coroutines.Job? = null

    override suspend fun start(source: AudioSource) {
        session.start()
        val ring = session.ring ?: return
        pump = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val buf = ByteArray(3840)
            while (isActive) { val n = source.read(buf); if (n < 0) break; if (n > 0) ring.write(buf, 0, n) }
        }
    }

    override suspend fun stop() { pump?.cancel(); pump = null; session.stop() }
}
