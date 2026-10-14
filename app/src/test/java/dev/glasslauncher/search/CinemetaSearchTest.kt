package dev.glasslauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CinemetaSearchTest {
    @Test fun parsesPosterYearKindAndAStremioLink() {
        val body = """{"metas":[{"id":"tt0133093","imdb_id":"tt0133093","type":"movie","name":"The Matrix","releaseInfo":"1999","poster":"https://p/m.jpg","description":"d"},
            {"id":"kitsu:1","name":"Not imdb"},{"id":"tt1","name":"","poster":"x"}]}"""
        val out = CinemetaSearch.parse("movie", body)
        assertEquals(1, out.size)
        val m = out.single()
        assertEquals("The Matrix", m.title); assertEquals(1999, m.year); assertEquals("Movie", m.type)
        assertEquals("https://p/m.jpg", m.image); assertEquals("stremio:///detail/movie/tt0133093", m.link)
        assertEquals(listOf("com.stremio.one"), m.packages)
        assertEquals(2f / 3f, m.aspect)
    }

    @Test fun aShowIsATvShow() {
        assertEquals("TV Show", CinemetaSearch.parse("series", """{"metas":[{"id":"tt2","name":"Show","releaseInfo":"2019–2022"}]}""").single().type)
        assertEquals(2019, CinemetaSearch.parse("series", """{"metas":[{"id":"tt2","name":"Show","releaseInfo":"2019–2022"}]}""").single().year)
    }

    @Test fun noMetasIsNoResults() {
        assertEquals(0, CinemetaSearch.parse("movie", """{"metas":[]}""").size)
        assertEquals(0, CinemetaSearch.parse("movie", """{}""").size)
        assertNull(CinemetaSearch.parse("movie", """{"metas":[{"id":"tt3","name":"X"}]}""").single().image)
    }
}
