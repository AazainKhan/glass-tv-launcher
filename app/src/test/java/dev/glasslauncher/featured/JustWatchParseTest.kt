package dev.glasslauncher.featured

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P89: Prime's JustWatch row is a full row: titles without a backdrop are dropped, so more are fetched and 20 kept. */
class JustWatchParseTest {
    private fun response(total: Int, withoutBackdrop: Set<Int>): String {
        val edges = (1..total).joinToString(",") { i ->
            val backdrop = if (i in withoutBackdrop) "[]" else """[{"backdropUrl":"/b/$i.jpg"}]"""
            """{"node":{"id":"tm$i","objectType":"MOVIE","content":{"title":"Title $i","originalReleaseYear":2024,"backdrops":$backdrop,"genres":[],"externalIds":{"imdbId":"tt$i"}},"offers":[{"deeplinkURL":"intent://x/$i#Intent;package=com.amazon.amazonvideo.livingroom;end"}]}}"""
        }
        return """{"data":{"popularTitles":{"edges":[$edges]}}}"""
    }

    private val amp = JustWatch.services.first { it.code == "amp" }

    @Test fun enoughTitlesRemainAfterTheOnesWithoutArtAreDropped() {
        // 40 asked for, 22 have no backdrop: 18 remain (the old request of 15 left ~4 for Prime).
        val items = JustWatch.parse(Json.parseToJsonElement(response(40, (1..40).filter { it % 2 == 1 || it > 36 }.toSet())), amp)
        assertEquals(18, items.size.coerceAtMost(20).let { items.size })
        assertTrue(items.all { it.image != null })
    }

    @Test fun neverMoreThanTwenty() {
        val items = JustWatch.parse(Json.parseToJsonElement(response(40, emptySet())), amp)
        assertEquals(20, items.size)
        assertEquals("jw:tm1", items.first().id)
        assertTrue("links target Fire TV's Prime app", items.first().link!!.contains("package=com.amazon.firebat"))
    }
}
