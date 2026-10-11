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
