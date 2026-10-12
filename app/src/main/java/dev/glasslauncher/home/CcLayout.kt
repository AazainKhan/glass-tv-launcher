package dev.glasslauncher.home

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The customizable Control Center's layout model (P83, docs/superpowers/specs/2026-10-11-customizable-cc-design.md):
 * pages of an ordered list of `{id, size}`. Positions are never stored; [CcPacking] recomputes them, so the layout is
 * deterministic and the stored value tiny. Pure Kotlin: no Compose, no Android.
 */
enum class CcSize(val w: Int, val h: Int, val key: String) {
    S1x1(1, 1, "1x1"), S2x1(2, 1, "2x1"), S2x2(2, 2, "2x2"), S4x1(4, 1, "4x1");

    companion object { fun parse(key: String?): CcSize? = entries.firstOrNull { it.key == key } }
}

data class CcTile(val id: String, val size: CcSize)

data class CcPage(val id: String, val icon: String, val tiles: List<CcTile>)

/**
 * [parked]: ids of an uninstalled add-on's tiles, kept so a re-install brings them back (spec 9).
 * [autoAdded]: new built-ins this layout was given once (so one the user removes stays removed).
 */
data class CcLayout(val pages: List<CcPage>, val parked: List<String> = emptyList(), val autoAdded: List<String> = emptyList()) {
    fun page(id: String): CcPage? = pages.firstOrNull { it.id == id }

    private fun withPage(id: String, change: (CcPage) -> CcPage) = copy(pages = pages.map { if (it.id == id) change(it) else it })

    /** The tile at [from] moves so it ends up at list index [to] (the others shift by one between the two). */
    fun move(pageId: String, from: Int, to: Int): CcLayout = withPage(pageId) { p ->
        if (from !in p.tiles.indices || to !in p.tiles.indices || from == to) return@withPage p
        val list = p.tiles.toMutableList()
        list.add(to, list.removeAt(from))
        p.copy(tiles = list)
    }

    /** Menu in edit mode: the next of the control's allowed sizes, wrapping. A one-size control is unchanged. */
    fun resize(pageId: String, tileId: String): CcLayout = withPage(pageId) { p ->
        p.copy(tiles = p.tiles.map { t ->
            if (t.id != tileId) t else {
                val sizes = CcControls.sizes(t.id)
                if (sizes.size < 2) t else t.copy(size = sizes[(sizes.indexOf(t.size).coerceAtLeast(-1) + 1) % sizes.size])
            }
        })
    }

    fun resizeTo(pageId: String, tileId: String, size: CcSize): CcLayout = withPage(pageId) { p ->
        p.copy(tiles = p.tiles.map { if (it.id == tileId && size in CcControls.sizes(it.id)) it.copy(size = size) else it })
    }

    /** Play/Pause in edit mode. Fire TV Settings can't be removed ([CcControls.removable]). */
    fun remove(pageId: String, tileId: String): CcLayout =
        if (!CcControls.removable(tileId)) this else withPage(pageId) { p -> p.copy(tiles = p.tiles.filter { it.id != tileId }) }

    /** The gallery: the control at the end of the page, at its default size. A control already on a page isn't added again unless parameterized. */
    fun add(pageId: String, id: String, size: CcSize? = null): CcLayout {
        if (!CcControls.repeatable(id) && pages.any { p -> p.tiles.any { it.id == id } }) return this
        val s = size?.takeIf { it in CcControls.sizes(id) } ?: CcControls.sizes(id).first()
        return withPage(pageId) { it.copy(tiles = it.tiles + CcTile(id, s)) }
    }

    /**
     * The gallery's add, kept within [maxRows] packed rows per page: a control that would make [pageId] taller goes to
     * the next page that has room (a new page is made after the last editable one when none does).
     */
    fun addFitting(pageId: String, id: String, maxRows: Int): CcLayout {
        if (!CcControls.repeatable(id) && pages.any { p -> p.tiles.any { it.id == id } }) return this
        fun rows(tiles: List<CcTile>): Int = CcPacking.pack(tiles).maxOfOrNull { it.row + it.tile.size.h } ?: 0
        val size = CcControls.sizes(id).first()
        val order = pages.map { it.id }.filter { it != "alexa" }
        var at = order.indexOf(pageId).takeIf { it >= 0 } ?: 0
        while (at < order.size) {
            val page = page(order[at])!!
            if (rows(page.tiles + CcTile(id, size)) <= maxRows) return withPage(page.id) { it.copy(tiles = it.tiles + CcTile(id, size)) }
            at++
        }
        val fresh = "page${pages.size}-${System.nanoTime() % 100000}"
        val newPage = CcPage(fresh, "auto", listOf(CcTile(id, size)))
        val alexa = pages.indexOfFirst { it.id == "alexa" }
        return copy(pages = if (alexa >= 0) pages.toMutableList().also { it.add(alexa, newPage) } else pages + newPage)
    }

    /** A lifted tile dropped on another page: removed here, added at the end there. */
    fun moveToPage(fromPage: String, tileId: String, toPage: String): CcLayout {
        val tile = page(fromPage)?.tiles?.firstOrNull { it.id == tileId } ?: return this
        if (fromPage == toPage || page(toPage) == null) return this
        return withPage(fromPage) { p -> p.copy(tiles = p.tiles.filter { it.id != tileId }) }
            .withPage(toPage) { it.copy(tiles = it.tiles + tile) }
    }

    /** A page left empty when edit mode ends is deleted (the first one stays: there's always a "Controls"). */
    fun withoutEmptyPages(keepPageIds: Set<String> = emptySet()): CcLayout =
        copy(pages = pages.filterIndexed { i, p -> i == 0 || p.tiles.isNotEmpty() || p.id in keepPageIds })

    /**
     * Up/Down while a tile is lifted: the list index of the tile whose packed slot is nearest in the row above
     * ([dir] -1) or below (+1), or null when there is none. [available] drops tiles that aren't there right now.
     */
    fun neighbour(pageId: String, index: Int, dir: Int, available: (String) -> Boolean = { true }): Int? {
        val page = page(pageId) ?: return null
        val tile = page.tiles.getOrNull(index) ?: return null
        val shown = page.tiles.filter { available(it.id) }
        val placed = CcPacking.pack(shown)
        val me = placed.firstOrNull { it.tile === tile || (it.tile.id == tile.id && it.tile.size == tile.size) } ?: return null
        val rowWanted = me.row + dir
        val candidates = placed.filter { it.tile !== me.tile && me.row != it.row && (rowWanted in it.row until it.row + it.tile.size.h) }
        val best = candidates.minByOrNull { kotlin.math.abs(it.col - me.col) } ?: return null
        return page.tiles.indexOfFirst { it.id == best.tile.id }.takeIf { it >= 0 }
    }

    /** Is this control (or, for "alexa", its page) in the layout? Settings › Control Center's switches read this. */
    fun shown(id: String): Boolean = if (id == "alexa") page("alexa") != null else pages.any { p -> p.tiles.any { it.id == id } }

    /** Settings's switch: off removes every copy; on puts it back where today's order has it (or the end). */
    fun withShown(id: String, on: Boolean): CcLayout {
        if (id == "alexa") {
            return if (!on) copy(pages = pages.filter { it.id != "alexa" })
            else if (page("alexa") != null) this else copy(pages = pages + CcPage("alexa", "alexa", emptyList()))
        }
        val id = CcControls.canonical(id)
        if (shown(id) == on) return this
        if (!on) return copy(pages = pages.map { p -> p.copy(tiles = p.tiles.filter { it.id != id }) })
        val order = CcControls.DEFAULT_ORDER
        return withPage(pages.first().id) { p ->
            val mine = order.indexOf(id)
            val at = if (mine < 0) p.tiles.size else p.tiles.indexOfFirst { order.indexOf(it.id) > mine }.let { if (it < 0) p.tiles.size else it }
            p.copy(tiles = p.tiles.toMutableList().also { it.add(at, CcTile(id, CcControls.sizes(id).first())) })
        }
    }

    fun toJson(): String = JSON.encodeToString(Dto.serializer(), Dto(
        v = VERSION,
        pages = pages.map { p -> PageDto(p.id, p.icon, p.tiles.map { TileDto(it.id, it.size.key) }) },
        parked = parked,
        autoAdded = autoAdded,
    ))

    @Serializable private data class TileDto(val id: String, val size: String)
    @Serializable private data class PageDto(val id: String, val icon: String, val tiles: List<TileDto>)
    @Serializable private data class Dto(
        val v: Int = 1, val pages: List<PageDto> = emptyList(), val parked: List<String> = emptyList(),
        val autoAdded: List<String> = emptyList(),
    )

    /**
     * Built-ins that arrived after a layout was saved are put in once: each id in [CcControls.AUTO_ADD] that this layout
     * has never been given and doesn't show. It goes after its neighbour ([CcControls.AUTO_ADD_AFTER]) or at the end of
     * the last editable page; the id is then in [autoAdded], so a user's removal sticks.
     */
    fun withNewBuiltins(): CcLayout {
        var out = this
        for (id in CcControls.AUTO_ADD) {
            if (id in out.autoAdded) continue
            if (out.shown(id)) { out = out.copy(autoAdded = out.autoAdded + id); continue }
            val after = CcControls.AUTO_ADD_AFTER[id]
            val host = out.pages.firstOrNull { p -> p.tiles.any { it.id == after } }
                ?: out.pages.lastOrNull { it.id != "alexa" } ?: out.pages.first()
            out = out.withPage(host.id) { p ->
                val at = p.tiles.indexOfFirst { it.id == after }.let { if (it < 0) p.tiles.size else it + 1 }
                p.copy(tiles = p.tiles.toMutableList().also { it.add(at, CcTile(id, CcControls.sizes(id).first())) })
            }.copy(autoAdded = out.autoAdded + id)
        }
        return out
    }

    companion object {
        const val VERSION = 1
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** What a stored value says; null for none or an unreadable one (the caller then uses [default]). */
        fun fromJson(text: String?): CcLayout? {
            if (text.isNullOrBlank()) return null
            val dto = runCatching { JSON.decodeFromString(Dto.serializer(), text) }.getOrNull() ?: return null
            if (dto.pages.isEmpty()) return null
            return CcLayout(
                pages = dto.pages.map { p ->
                    CcPage(p.id, p.icon, p.tiles.mapNotNull { t ->
                        // An unknown size is the control's default one; an unknown id is kept (its add-on may return).
                        val id = CcControls.canonical(t.id)
                        val size = CcSize.parse(t.size) ?: CcControls.sizes(id).first()
                        CcTile(id, size)
                    })
                },
                parked = dto.parked,
                autoAdded = dto.autoAdded,
            )
        }

        /** Today's Control Center (the ids of CONTROL_CENTER_TILES), minus what the user had turned off ([hidden], the old ccHidden). */
        fun default(hidden: Set<String> = emptySet()): CcLayout {
            val off = hidden.map { CcControls.canonical(it) }.toSet()
            fun tiles(list: List<CcTile>) = list.filter { it.id !in off }
            val pages = buildList {
                add(CcPage("controls", "grid", tiles(CcControls.DEFAULT_CONTROLS)))
                // More: what doesn't fit the 4x4 first page (VPN shows only when a VPN app is installed).
                add(CcPage("more", "more", tiles(CcControls.DEFAULT_MORE)))
                // Alexa is a page of its own today (a menu-bar icon); the spec's page model keeps it that way until the pages work lands.
                if ("alexa" !in hidden) add(CcPage("alexa", "alexa", emptyList()))
            }
            // A fresh layout already has every built-in: nothing is added to it later.
            return CcLayout(pages, autoAdded = CcControls.AUTO_ADD)
        }

        /** The stored layout, or the default carrying over the old per-tile switches (migration of ccHidden, once). */
        fun resolve(stored: String?, hidden: Set<String>): CcLayout = (fromJson(stored) ?: default(hidden)).withNewBuiltins()
    }
}

/** What each control is: the sizes it allows (default first) and whether it may be removed or added twice. */
object CcControls {
    private val ONE = listOf(CcSize.S1x1)
    /** Icon only, a pill with its name (and value), a big tile: every control that has a name can be any of these (F01). */
    private val CIRCLE_PILL = listOf(CcSize.S1x1, CcSize.S2x1)
    private val CIRCLE_PILL_BIG = listOf(CcSize.S1x1, CcSize.S2x1, CcSize.S2x2)
    private val PILL_FIRST = listOf(CcSize.S2x1, CcSize.S1x1, CcSize.S2x2)
    private val TABLE: Map<String, List<CcSize>> = mapOf(
        "fire_settings" to listOf(CcSize.S2x2),
        "wifi" to PILL_FIRST,
        "bluetooth" to PILL_FIRST,
        "launcher" to listOf(CcSize.S4x1, CcSize.S2x1, CcSize.S1x1),
        "sleep_timer" to PILL_FIRST,
        "vpn" to PILL_FIRST,
        "restart" to listOf(CcSize.S2x1, CcSize.S1x1),
        "cleanup" to PILL_FIRST,
        "now_playing" to listOf(CcSize.S2x2, CcSize.S2x1, CcSize.S1x1),
        "controllers" to CIRCLE_PILL, "remotes" to CIRCLE_PILL, "screensaver" to CIRCLE_PILL, "switcher" to CIRCLE_PILL,
        "appearance" to CIRCLE_PILL_BIG, "airplay" to CIRCLE_PILL_BIG, "performance" to CIRCLE_PILL_BIG,
        "memory" to PILL_FIRST,
    )

    /** Rows a page holds without scrolling: tvOS's panel is about 867 px tall at 1080p and a row pitch is 112 px. */
    const val MAX_ROWS = 7

    /** Built-ins given once to a layout saved before they existed, and what each goes after (the More page: Controls is a full 4x4). */
    val AUTO_ADD = listOf("remotes")
    val AUTO_ADD_AFTER = mapOf("remotes" to "controllers")

    /** Today's packed order of the Controls page (the spec's default grid, before the new built-ins arrive). */
    val DEFAULT_ORDER = listOf(
        "fire_settings", "wifi", "bluetooth", "sleep_timer", "cleanup", "restart", "airplay",
        "appearance", "screensaver", "switcher", "launcher", "controllers", "performance", "vpn",
    )

    /**
     * The default first page, exactly 4 columns x 4 rows (130 px pitch, the panel's viewport ends after four rows), so
     * it fits with no scroll at rest: Settings 2x2 beside Wi-Fi and Bluetooth, then Sleep Timer and Clean Up as pills,
     * Restart as a pill beside AirPlay and Theme as circles (F01: the three that carry a state or a result get a name).
     */
    val DEFAULT_CONTROLS = listOf(
        CcTile("fire_settings", CcSize.S2x2), CcTile("wifi", CcSize.S2x1), CcTile("bluetooth", CcSize.S2x1),
        CcTile("sleep_timer", CcSize.S2x1), CcTile("cleanup", CcSize.S2x1), CcTile("restart", CcSize.S2x1), CcTile("airplay", CcSize.S1x1),
        CcTile("appearance", CcSize.S1x1),
    )

    /** The default second page. */
    val DEFAULT_MORE = listOf(
        CcTile("screensaver", CcSize.S1x1), CcTile("switcher", CcSize.S1x1), CcTile("launcher", CcSize.S1x1), CcTile("controllers", CcSize.S1x1),
        CcTile("remotes", CcSize.S1x1), CcTile("performance", CcSize.S1x1), CcTile("vpn", CcSize.S2x1),
    )

    /** Ids that were renamed: Free Memory became Clean Up (it does both jobs, one tap). */
    fun canonical(id: String): String = if (id == "memory") "cleanup" else id

    /** An add-on's control (`addon:...`) may use 1x1 and 2x1 only; an unknown built-in is 1x1. */
    fun sizes(id: String): List<CcSize> = TABLE[id] ?: if (id.startsWith("addon:")) listOf(CcSize.S2x1, CcSize.S1x1) else ONE

    /** Fire TV Settings is the only way into Fire's own settings: it stays. */
    fun removable(id: String) = id != "fire_settings"

    /** Parameterized controls (a Home Assistant scene with a chosen entity) may appear more than once: their id has a `#`. */
    fun repeatable(id: String) = '#' in id
}

/**
 * Cursor-based next-fit packing (spec 1): each tile goes at the first free cell at or after the cursor, in reading
 * order, where it fits inside [columns]; the cursor then sits at the tile's right edge and never moves backwards, so a
 * tile never jumps ahead to fill an earlier hole (iOS's "backfill"). A 2x2 occupies two rows and later tiles wrap
 * beside it because overlap is checked.
 */
data class CcPlaced(val tile: CcTile, val row: Int, val col: Int)

object CcPacking {
    const val COLUMNS = 4

    fun pack(tiles: List<CcTile>, columns: Int = COLUMNS): List<CcPlaced> {
        val occupied = HashSet<Long>()
        fun key(r: Int, c: Int) = r.toLong() * 100 + c
        fun fits(t: CcTile, r: Int, c: Int): Boolean {
            if (c + t.size.w > columns) return false
            for (dr in 0 until t.size.h) for (dc in 0 until t.size.w) if (key(r + dr, c + dc) in occupied) return false
            return true
        }
        var curRow = 0; var curCol = 0
        val out = ArrayList<CcPlaced>(tiles.size)
        for (t in tiles) {
            val w = minOf(t.size.w, columns)
            val tile = if (w == t.size.w) t else t.copy(size = CcSize.S1x1)
            var r = curRow; var c = curCol
            while (!fits(tile, r, c)) { c++; if (c >= columns) { c = 0; r++ } }
            for (dr in 0 until tile.size.h) for (dc in 0 until tile.size.w) occupied += key(r + dr, c + dc)
            out += CcPlaced(tile, r, c)
            curRow = r; curCol = c + tile.size.w
            if (curCol >= columns) { curCol = 0; curRow = r + 1 }
        }
        return out
    }
}
