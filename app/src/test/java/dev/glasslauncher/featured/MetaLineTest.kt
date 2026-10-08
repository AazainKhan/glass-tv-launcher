package dev.glasslauncher.featured

import org.junit.Assert.assertEquals
import org.junit.Test

class MetaLineTest {
    @Test fun ordersRatingYearDurationGenre() {
        val item = FeaturedItem("1", "T", year = 2024, rating = "14+", durationMin = 125, genre = "Drama")
        assertEquals("14+ · 2024 · 2 hr 5 min · Drama", item.metaLine())
    }

    @Test fun skipsMissingFields() {
        assertEquals("2024 · Comedy", FeaturedItem("1", "T", year = 2024, genre = "Comedy").metaLine())
    }

    @Test fun episodesShowInsteadOfDuration() {
        assertEquals("S2, E4 · 45 min", FeaturedItem("1", "T", episode = "S2, E4", durationMin = 45).metaLine())
    }

    @Test fun fallsBackToSubtitle() {
        assertEquals("IMDb 7.1", FeaturedItem("1", "T", subtitle = "IMDb 7.1").metaLine())
    }
}
