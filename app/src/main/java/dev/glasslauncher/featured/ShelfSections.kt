package dev.glasslauncher.featured

/**
 * The sectioned Top Shelf of tvOS 27 (P125b): several headed rows instead of one. Pure logic: how the YouTube source
 * lays its channels out, the caps, and the focus bookkeeping that lets Up and Down land where the viewer left each row.
 */
object ShelfSections {
    /** At most this many headed rows, and this many cards in each (a row of 12 is already more than a viewer scrolls). */
    const val MAX_SECTIONS = 6
    const val MAX_CARDS = 12

    const val NEW_HEADING = "New from your channels"
    const val TRENDING_HEADING = "Trending on YouTube"

    /**
     * The YouTube shelf. [perChannel] is each picked channel's videos (with publish time, ISO so it sorts as text) in
     * the order the viewer picked the channels; a channel that failed or is empty is an empty list. [trending] is the
     * Data-API chart when a key is set. Rows, in order: "New from your channels" (the newest video of each channel, when
     * two or more channels have any), "From <channel>" per channel, and "Trending on YouTube" last. A row with no videos
     * is dropped; past [MAX_SECTIONS] the last channels give way (trending and the new row keep their place). Only one
     * row left is the old single-row feed, so nothing sectioned shows for one row. Nothing at all gives an empty feed.
     */
    fun youtube(perChannel: List<List<Pair<FeaturedItem, String>>>, trending: List<FeaturedItem>): FeaturedFeed {
        val channels = perChannel.mapNotNull { vids ->
            val sorted = vids.sortedByDescending { it.second }.distinctBy { it.first.id }
            if (sorted.isEmpty()) null else sorted
        }
        val trendingRow = trending.distinctBy { it.id }.take(MAX_CARDS).takeIf { it.isNotEmpty() }
        val wantsNew = channels.size >= 2
        val room = MAX_SECTIONS - (if (trendingRow != null) 1 else 0) - (if (wantsNew) 1 else 0)
        val shown = channels.take(room.coerceAtLeast(1))
        val sections = buildList {
            if (shown.size >= 2) {
                val newest = shown.map { it.first() }.sortedByDescending { it.second }.map { it.first }.take(MAX_CARDS)
                add(FeaturedSection(NEW_HEADING, newest))
            }
            shown.forEach { vids ->
                val name = vids.first().first.subtitle
                add(FeaturedSection(if (name.isNullOrBlank()) "From a channel" else "From $name", vids.map { it.first }.take(MAX_CARDS)))
            }
            if (trendingRow != null) add(FeaturedSection(TRENDING_HEADING, trendingRow))
        }.take(MAX_SECTIONS)
        return when (sections.size) {
            0 -> FeaturedFeed(TRENDING_HEADING, emptyList())
            1 -> FeaturedFeed(sections[0].heading, sections[0].items)
            else -> FeaturedFeed("YouTube", sections.flatMap { it.items }.distinctBy { it.id }, sections)
        }
    }

    /** The headed rows to draw: the feed's own, or its one row under its heading. */
    fun rows(feed: FeaturedFeed): List<FeaturedSection> =
        feed.sections.filter { it.items.isNotEmpty() }.ifEmpty { listOf(FeaturedSection(feed.heading, feed.items)) }

    /** The row Up (-1) or Down (+1) goes to from [from] among [count] rows; null at either end (the shelf's own Up and Down take over). */
    fun neighbour(from: Int, dir: Int, count: Int): Int? = (from + dir).takeIf { it in 0 until count }

    /** The row to start in for the title at [itemId]: [prefer] when it holds that title, else the first row that does, else 0. */
    fun rowOf(rows: List<FeaturedSection>, itemId: String?, prefer: Int? = null): Int {
        if (itemId == null) return 0
        if (prefer != null && rows.getOrNull(prefer)?.items?.any { it.id == itemId } == true) return prefer
        return rows.indexOfFirst { r -> r.items.any { it.id == itemId } }.coerceAtLeast(0)
    }
}

/**
 * Where focus was in each row, so moving to another row lands on the card the viewer left there (tvOS). A row never
 * visited takes the column the viewer is in, clamped to its length.
 */
class SectionMemory {
    private val last = HashMap<Int, Int>()

    fun remember(section: Int, position: Int) { last[section] = position }

    fun landing(section: Int, size: Int, fromPosition: Int): Int =
        (last[section] ?: fromPosition).coerceIn(0, (size - 1).coerceAtLeast(0))
}

/**
 * Where focus is among the sectioned shelf's rows, shared with Home: Down leaves full screen from the last row only, so
 * Home asks [hasRowBelow] before it takes the key. Plain fields, not state: they are read in a key handler, never drawn.
 */
class SectionCursor {
    var row = 0
    var rows = 1
    /** A card has focus (not the Play and More Info buttons above the rows, where Down still leaves full screen). */
    var focusedCard = -1L
    val inRows: Boolean get() = focusedCard >= 0
    val hasRowBelow: Boolean get() = inRows && row < rows - 1
    /** More than one headed row is showing (a plain shelf keeps rows at 1). */
    val sectioned: Boolean get() = rows > 1
    /** Moves focus to the first row's remembered card; set by the rows while they are composed (P132). */
    var enterRows: (() -> Boolean)? = null

    /** What Down does in full screen (the status pill aside): see [DownAction]. */
    fun down(): DownAction = when {
        hasRowBelow -> DownAction.Native
        sectioned && !inRows && enterRows != null -> DownAction.EnterRows
        else -> DownAction.Leave
    }
}

/** Down in the full-screen shelf: let the rows move it, enter the rows from the hero buttons (P132), or leave to the tray. */
enum class DownAction { Native, EnterRows, Leave }
