package dev.glasslauncher.featured

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P89: an app's Top Shelf row is a full row (up to 20), not what survives a title-only de-duplication. */
class TvRowsAssembleTest {
    private fun item(id: Int, title: String = "Title $id", link: String? = "intent://$id", image: String? = "https://img/$id.jpg", aspect: Float? = 16f / 9f) =
        FeaturedItem(id = "tv:$id", title = title, subtitle = null, image = image, link = link, aspect = aspect)

    @Test fun twentyDistinctProgramsAreTwentyItems() {
        assertEquals(20, TvRows.assemble((1..30).map { item(it) }).size)
        assertEquals(14, TvRows.assemble((1..14).map { item(it) }).size)
    }

    @Test fun sameTitleWithDifferentLinksAreDifferentPrograms() {
        // Episodes / seasons of one show: one title, many links (the old de-dup by title kept one of each title).
        val row = TvRows.assemble((1..10).map { item(it, title = "The Show") })
        assertEquals(10, row.size)
        // The same link twice is one program.
        assertEquals(1, TvRows.assemble(listOf(item(1), item(2, link = "intent://1"))).size)
    }

    @Test fun itemsWithArtComeFirstButNoneAreDropped() {
        val row = TvRows.assemble(listOf(item(1, image = null), item(2), item(3, image = ""), item(4)))
        assertEquals(listOf("tv:2", "tv:4", "tv:1", "tv:3"), row.map { it.id })
    }

    /**
     * P94: the row follows what was watched most recently, and art is only the tiebreak. The old art-first hoist
     * re-sorted the Watch Next query's own `last_engagement_time DESC`, so a just-watched item with no cover fell
     * behind an older one that had art.
     */
    @Test fun mostRecentlyWatchedComesFirstAndArtOnlyBreaksTies() {
        val row = TvRows.assemble(
            listOf(
                item(1, image = "https://img/1.jpg").copy(watchedAt = 100L),
                item(2, image = null).copy(watchedAt = 300L),        // newest, and no art: must still lead
                item(3, image = "https://img/3.jpg").copy(watchedAt = 200L),
            ),
        )
        assertEquals(listOf("tv:2", "tv:3", "tv:1"), row.map { it.id })
    }

    @Test fun artDecidesOnlyWhenTheSourceGivesNoRecency() {
        // Same timestamp (and the no-recency case) keeps the previous art-first behaviour, so nothing regressed.
        val tied = TvRows.assemble(
            listOf(item(1, image = null).copy(watchedAt = 5L), item(2).copy(watchedAt = 5L)),
        )
        assertEquals(listOf("tv:2", "tv:1"), tied.map { it.id })
        val noRecency = TvRows.assemble(listOf(item(1, image = null), item(2)))
        assertEquals(listOf("tv:2", "tv:1"), noRecency.map { it.id })
    }

    @Test fun aShapeFilterNeverShrinksTheRowToAFew() {
        // 5 square covers among 6 portrait posters: uniform would keep 5 (square majority is false here: 5 of 11 -> no
        // filter); with a square majority and only 5 left (< 8) the whole row stays.
        val mixed = (1..6).map { item(it, aspect = 1f) } + (7..9).map { item(it, aspect = 2f / 3f) }
        assertEquals(9, TvRows.assemble(mixed).size)
        // A big square majority is filtered to squares as before.
        val squares = (1..12).map { item(it, aspect = 1f) } + (13..14).map { item(it, aspect = 2f / 3f) }
        assertTrue(TvRows.assemble(squares).all { it.aspect == 1f })
    }
}

class TvRowsRotationTest {
    @org.junit.Test fun aWindowRotatesHalfAStepOverEverythingPublishedAndWraps() {
        val all = (1..50).toList()
        assertEquals((1..20).toList(), TvRows.window(all, 20, 0))
        assertEquals((11..30).toList(), TvRows.window(all, 20, 1))
        assertEquals("wraps past the end", listOf(41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), TvRows.window(all, 20, 4))
        assertEquals("everything shows when it fits", (1..14).toList(), TvRows.window((1..14).toList(), 20, 7))
    }

    @org.junit.Test fun channelsAreInterleavedSoABatchMixesThem() {
        val out = TvRows.interleave(listOf(listOf("r1", "r2", "r3"), listOf("m1"), listOf("f1", "f2")))
        assertEquals(listOf("r1", "m1", "f1", "r2", "f2", "r3"), out)
    }
}
