package dev.glasslauncher.featured

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P110: what the Info sheet shows is new (people, country, seasons), and tolerant of what the source lacks. */
class ShelfDetailsTest {
    private fun parse(json: String) = ShelfDetails.parse(Json.parseToJsonElement(json).jsonObject)

    @Test fun appExtrasGiveFacesAndRolesAndCapAtSix() {
        val cast = (1..9).joinToString(",") { """{"name":"Actor $it","character":"Role $it","photo":"https://img/$it.jpg"}""" }
        val d = parse("""{"app_extras":{"cast":[$cast],"directors":[{"name":"Dir One"}],"writers":[{"name":"W1"},{"name":"W2"}]}}""")
        assertEquals(6, d.cast.size)
        assertEquals(Person("Actor 1", "Role 1", "https://img/1.jpg"), d.cast.first())
        assertEquals(listOf("Dir One"), d.directors)
        assertEquals(listOf("W1", "W2"), d.writers)
    }

    @Test fun plainNameArraysAreTheFallbackWithNoPhotos() {
        val d = parse("""{"cast":["A","B"],"director":["D"],"writer":["W"],"country":"United States","awards":"Won 2 Oscars"}""")
        assertEquals(listOf(Person("A"), Person("B")), d.cast)
        assertEquals(listOf("D"), d.directors)
        assertEquals("United States", d.country)
        assertEquals("Won 2 Oscars", d.awards)
        assertNull(d.cast.first().photo)
    }

    @Test fun seasonsAreCountedFromTheEpisodesNotTheEpisodeCount() {
        val d = parse("""{"videos":[{"season":1},{"season":1},{"season":2},{"season":0},{"season":3}]}""")
        assertEquals(3, d.seasons)
        assertNull(parse("""{"videos":[]}""").seasons)
    }

    @Test fun nothingKnownIsEmptyAndShowsNothing() {
        assertTrue(parse("""{"name":"X","description":"y"}""").isEmpty)
    }

    @Test fun theShelfsOwnFieldsAreNotRepeated() {
        // rating, year, runtime, genre and the synopsis are on the shelf already: ShelfDetails has no place for them.
        val d = parse("""{"imdbRating":"8.1","releaseInfo":"2020","runtime":"120 min","genres":["Drama"],"description":"Long."}""")
        assertTrue(d.isEmpty)
    }
}
