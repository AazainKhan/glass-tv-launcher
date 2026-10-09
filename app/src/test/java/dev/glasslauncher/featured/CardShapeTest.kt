package dev.glasslauncher.featured

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A row's cards share one shape, taken from its art: Spotify's covers are square, films are 16:9. */
class CardShapeTest {
    private fun item(id: String, aspect: Float?) = FeaturedItem(id = id, title = id, aspect = aspect)

    @Test fun mostlySquareArtMakesASquareRowAndDropsTheOddOneOut() {
        val promo = item("promo", 16 / 9f)
        val row = listOf(promo) + (1..6).map { item("album$it", 1f) }
        val shaped = CardShape.uniform(row)
        assertTrue(CardShape.square(shaped))
        assertFalse(shaped.any { it.id == "promo" })
        assertEquals(6, shaped.size)
    }

    @Test fun filmsStayLandscapeAndKeepEverything() {
        val row = listOf(item("a", null), item("b", 16 / 9f), item("c", 1f))
        assertEquals(row, CardShape.uniform(row))
        assertFalse(CardShape.square(row))
    }

    @Test fun theTvProvidersAspectCodesMapToRatios() {
        assertEquals(1f, CardShape.fromTvContract(3)!!, 0.001f)
        assertEquals(16 / 9f, CardShape.fromTvContract(0)!!, 0.001f)
        assertEquals(null, CardShape.fromTvContract(-1))
    }
}
