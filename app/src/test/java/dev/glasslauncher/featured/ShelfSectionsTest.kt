package dev.glasslauncher.featured

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P125b: the sectioned YouTube shelf, its caps and its focus memory. */
class ShelfSectionsTest {
    private fun v(id: String, channel: String, day: Int) =
        FeaturedItem(id = id, title = "t$id", subtitle = channel) to "2026-10-%02dT00:00:00+00:00".format(day)

    private fun channel(name: String, vararg days: Int) = days.map { v("$name$it", name, it) }

    private fun trendingOf(n: Int) = List(n) { FeaturedItem(id = "tr$it", title = "Trend $it") }

    @Test fun sectionPerChannelAfterANewestOfEachRow() {
        val feed = ShelfSections.youtube(listOf(channel("A", 1, 5, 3), channel("B", 4, 2)), emptyList())
        assertEquals(listOf(ShelfSections.NEW_HEADING, "From A", "From B"), feed.sections.map { it.heading })
        assertEquals(listOf("A5", "B4"), feed.sections[0].items.map { it.id })
        assertEquals(listOf("A5", "A3", "A1"), feed.sections[1].items.map { it.id })
        assertEquals("every title once, in shelf order", listOf("A5", "B4", "A3", "A1", "B2"), feed.items.map { it.id })
    }

    @Test fun trendingIsTheLastRow() {
        val feed = ShelfSections.youtube(listOf(channel("A", 1), channel("B", 2)), trendingOf(3))
        assertEquals(ShelfSections.TRENDING_HEADING, feed.sections.last().heading)
        assertEquals(4, feed.sections.size)
    }

    @Test fun oneChannelIsTheOldPlainRowWithoutANewRow() {
        val feed = ShelfSections.youtube(listOf(channel("A", 1, 2)), emptyList())
        assertTrue(feed.sections.isEmpty())
        assertEquals("From A", feed.heading)
        assertEquals(listOf("A2", "A1"), feed.items.map { it.id })
    }

    @Test fun trendingAloneIsThePlainTrendingRow() {
        val feed = ShelfSections.youtube(emptyList(), trendingOf(2))
        assertTrue(feed.sections.isEmpty())
        assertEquals(ShelfSections.TRENDING_HEADING, feed.heading)
        assertEquals(2, feed.items.size)
    }

    @Test fun emptyAndFailedChannelsAreDropped() {
        val feed = ShelfSections.youtube(listOf(emptyList(), channel("A", 1), emptyList(), channel("B", 2)), emptyList())
        assertEquals(listOf(ShelfSections.NEW_HEADING, "From A", "From B"), feed.sections.map { it.heading })
        assertTrue(ShelfSections.youtube(listOf(emptyList(), emptyList()), emptyList()).items.isEmpty())
    }

    @Test fun capsAtSixRowsAndTwelveCardsKeepingNewAndTrending() {
        val many = (1..10).map { c -> channel("C$c", *(1..20).toList().toIntArray()) }
        val feed = ShelfSections.youtube(many, trendingOf(30))
        assertEquals(ShelfSections.MAX_SECTIONS, feed.sections.size)
        assertEquals(ShelfSections.NEW_HEADING, feed.sections.first().heading)
        assertEquals(ShelfSections.TRENDING_HEADING, feed.sections.last().heading)
        assertEquals(listOf("From C1", "From C2", "From C3", "From C4"), feed.sections.subList(1, 5).map { it.heading })
        assertTrue(feed.sections.all { it.items.size <= ShelfSections.MAX_CARDS })
        assertEquals(12, feed.sections[1].items.size)
        assertEquals("newest first", "C120", feed.sections[1].items.first().id)
    }

    @Test fun aRepeatedVideoCountsOnceInTheFeedItems() {
        val feed = ShelfSections.youtube(listOf(channel("A", 3)), listOf(FeaturedItem(id = "A3", title = "dup"), FeaturedItem(id = "x", title = "x")))
        assertEquals(listOf("A3", "x"), feed.items.map { it.id })
        assertEquals(2, feed.sections.size)
    }

    @Test fun rowsOfAPlainFeedIsOneRowUnderItsHeading() {
        val rows = ShelfSections.rows(FeaturedFeed("Popular", trendingOf(2)))
        assertEquals(listOf("Popular"), rows.map { it.heading })
    }

    @Test fun neighbourStopsAtBothEnds() {
        assertEquals(1, ShelfSections.neighbour(0, 1, 3))
        assertEquals(null, ShelfSections.neighbour(2, 1, 3))
        assertEquals(null, ShelfSections.neighbour(0, -1, 3))
    }

    @Test fun rowOfPrefersTheGivenRowThenTheFirstHoldingTheTitle() {
        val rows = listOf(FeaturedSection("a", listOf(FeaturedItem("x", "x"))), FeaturedSection("b", listOf(FeaturedItem("x", "x"), FeaturedItem("y", "y"))))
        assertEquals(0, ShelfSections.rowOf(rows, "x"))
        assertEquals(1, ShelfSections.rowOf(rows, "x", prefer = 1))
        assertEquals(1, ShelfSections.rowOf(rows, "y", prefer = 0))
        assertEquals(0, ShelfSections.rowOf(rows, "gone"))
    }

    @Test fun focusMemoryReturnsToTheCardLeftAndClamps() {
        val mem = SectionMemory()
        assertEquals("unvisited: same column", 3, mem.landing(1, 12, fromPosition = 3))
        assertEquals("unvisited, shorter row: clamped", 1, mem.landing(1, 2, fromPosition = 3))
        mem.remember(1, 7)
        assertEquals(7, mem.landing(1, 12, fromPosition = 0))
        assertEquals("row shrank", 4, mem.landing(1, 5, fromPosition = 0))
        assertEquals(0, mem.landing(2, 0, fromPosition = 5))
    }
}
