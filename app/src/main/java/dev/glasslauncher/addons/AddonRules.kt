package dev.glasslauncher.addons

import dev.glasslauncher.addon.AddonApi
import dev.glasslauncher.addon.AddonCodec
import dev.glasslauncher.addon.TileState

/** A service the system says handles [AddonApi.ACTION], reduced to what discovery needs (so it tests without a PackageManager). */
data class AddonCandidate(
    val pkg: String,
    val service: String,
    val exported: Boolean,
    val permission: String?,
    val meta: Map<String, String?>,
    /** SHA-256 of the signing certificate(s), hex. */
    val cert: String,
)

data class AddonInfo(
    val pkg: String,
    val service: String,
    val title: String,
    val api: Int,
    val minGlassApi: Int,
    val needsRoot: Boolean,
    val cert: String,
    val compatible: Boolean,
)

object AddonDiscovery {
    /**
     * Keeps only services that require BIND_ADDON and declare an API level; flags ones Glass cannot talk to.
     * One add-on service per package: with several, the first by class name wins.
     */
    fun filter(candidates: List<AddonCandidate>, glassLevel: Int = AddonApi.LEVEL): List<AddonInfo> = candidates.sortedBy { it.service }.mapNotNull { c ->
        if (!c.exported || c.permission != AddonApi.PERMISSION) return@mapNotNull null
        val api = c.meta[AddonApi.META_API]?.toIntOrNull() ?: return@mapNotNull null
        val min = c.meta[AddonApi.META_MIN_GLASS_API]?.toIntOrNull() ?: 1
        AddonInfo(
            c.pkg, c.service, AddonText.clean(c.meta[AddonApi.META_TITLE], 32).ifEmpty { c.pkg }, api, min,
            c.meta[AddonApi.META_NEEDS_ROOT].toBoolean(), c.cert, AddonApi.compatible(api, min, glassLevel),
        )
    }.distinctBy { it.pkg }
}

/** Sideloaded add-ons start off; the user enables one per package and signing certificate (config: package -> cert). */
object AddonTrust {
    fun isEnabled(enabled: Map<String, String>, pkg: String, cert: String) = enabled[pkg] == cert
    fun enable(enabled: Map<String, String>, pkg: String, cert: String) = enabled + (pkg to cert)
    fun disable(enabled: Map<String, String>, pkg: String) = enabled - pkg
    /** True when [pkg] was enabled before but its signing certificate is not the one the user approved. */
    fun certChanged(enabled: Map<String, String>, pkg: String, cert: String) = enabled[pkg]?.let { it != cert } == true
    /** Short form of the first signing certificate's SHA-256 for people to compare: AB:CD:EF:12. */
    fun fingerprint(cert: String): String = cert.substringBefore(',').take(8).uppercase().chunked(2).joinToString(":")
}

/**
 * Per-add-on call budget (spec 7): one call per tile per [minGapMs], and [failsToMute] failures in a row (timeout or
 * error) mute the add-on until [resume]. Pure, so a fake clock tests it.
 */
class AddonPolicy(private val now: () -> Long, private val minGapMs: Long = 1_000, private val failsToMute: Int = 3) {
    private val last = HashMap<String, Long>()
    private val fails = HashMap<String, Int>()
    private val muted = HashSet<String>()

    @Synchronized fun tryAcquire(pkg: String, tile: String): Boolean {
        if (pkg in muted) return false
        val t = now()
        val key = "$pkg/$tile"
        if (last[key]?.let { t - it < minGapMs } == true) return false
        last[key] = t
        return true
    }

    /** Records an outcome; true when this report muted the add-on. */
    @Synchronized fun report(pkg: String, ok: Boolean): Boolean {
        if (ok) { fails.remove(pkg); return false }
        val n = (fails[pkg] ?: 0) + 1
        fails[pkg] = n
        return n >= failsToMute && muted.add(pkg)
    }

    @Synchronized fun isMuted(pkg: String) = pkg in muted
    @Synchronized fun resume(pkg: String) { muted.remove(pkg); fails.remove(pkg) }
}

/** Text from an add-on that Glass will draw: printable characters only, so it cannot spoof or reorder what is on screen. */
object AddonText {
    /** Drops control, format (bidi and zero-width), private-use, unassigned and lone-surrogate code points; caps by code point, so no pair is split. */
    fun clean(s: String?, max: Int): String {
        if (s == null) return ""
        val sb = StringBuilder()
        var n = 0
        var i = 0
        while (i < s.length && n < max) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
                Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> continue
                Character.SPACE_SEPARATOR -> { if (sb.isEmpty() || sb.last() == ' ') continue; sb.append(' '); n++ }
                else -> { sb.appendCodePoint(cp); n++ }
            }
        }
        return sb.toString().trim()
    }
}

/** What an add-on's reply may put on screen: bounded, so a hostile or buggy add-on cannot break the layout or Home. */
object AddonLimits {
    const val MAX_TILES = 8
    private val ID = Regex("[A-Za-z0-9_.-]{1,40}")

    fun sanitize(tiles: List<TileState>): List<TileState> = tiles.asSequence()
        .filter { ID.matches(it.id) }.distinctBy { it.id }.take(MAX_TILES).map(::sanitize).toList()

    fun sanitize(t: TileState) = t.copy(
        label = AddonText.clean(t.label, 24).ifEmpty { t.id }, value = AddonText.clean(t.value, 18).ifEmpty { null },
        icon = AddonText.clean(t.icon, 16),
    )
}

/** A reply is good only if it decodes, carries no error and (for tiles) survives the limits; anything else counts as a failure. */
object AddonReplies {
    fun tiles(json: String?): List<TileState>? = AddonCodec.decodeTiles(json)?.takeIf { it.error == null }?.let { AddonLimits.sanitize(it.tiles) }
    fun state(json: String?, id: String): TileState? =
        AddonCodec.decodeState(json)?.takeIf { it.error == null }?.tile?.takeIf { it.id == id }?.let(AddonLimits::sanitize)
}

/** An add-on tile as Control Center shows it. */
internal data class ShownTile(val state: TileState, val addon: String, val pkg: String, val muted: Boolean)

internal fun addonTileId(pkg: String, tile: String) = "addon:$pkg/$tile"

internal fun MutableMap<String, ShownTile>.dropPkg(pkg: String) { keys.removeAll { it.startsWith("addon:$pkg/") } }

/** Replaces [pkg]'s tiles with a fresh read. */
internal fun MutableMap<String, ShownTile>.setTiles(pkg: String, title: String, tiles: List<TileState>) {
    dropPkg(pkg)
    tiles.forEach { this[addonTileId(pkg, it.id)] = ShownTile(it, title, pkg, muted = false) }
}

/** A failed read: a muted add-on keeps its tiles, shown paused; otherwise the stale tiles go (they then show as inactive). */
internal fun MutableMap<String, ShownTile>.failPkg(pkg: String, muted: Boolean) {
    if (!muted) { dropPkg(pkg); return }
    for ((id, t) in entries.toList()) if (t.pkg == pkg) this[id] = t.copy(muted = true)
}

/** Skips a refresh while the last one is fresh and nothing it depended on ([key], e.g. the enabled set) has changed. */
class RefreshGate(private val now: () -> Long, private val ttlMs: Long = 30_000) {
    private var at = 0L
    private var key: Any? = null
    private var marked = false
    @Synchronized fun due(k: Any) = !marked || key != k || now() - at >= ttlMs
    @Synchronized fun mark(k: Any) { key = k; at = now(); marked = true }
    @Synchronized fun reset() { marked = false }
}
