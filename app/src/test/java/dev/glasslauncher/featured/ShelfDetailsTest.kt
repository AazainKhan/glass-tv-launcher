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

class InitialsTest {
    @Test fun firstAndLastWordUpperCase() {
        assertEquals("KR", initials("Keanu Reeves"))
        assertEquals("JD", initials("jane  mary doe"))
        assertEquals("M", initials("Madonna"))
        assertEquals("", initials("   "))
    }
}

class PersonPhotosTest {
    private fun summary(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun anActorsThumbnailIsUsed() {
        val s = summary("""{"type":"standard","description":"Canadian actor (born 1964)","thumbnail":{"source":"https://upload.wikimedia.org/x/320px-Keanu.jpg","width":320}}""")
        assertEquals("https://upload.wikimedia.org/x/320px-Keanu.jpg", PersonPhotos.photoFrom(s))
    }

    @Test fun aNamesakePageOrADisambiguationGivesNoFace() {
        assertNull(PersonPhotos.photoFrom(summary("""{"type":"standard","description":"River in Spain","thumbnail":{"source":"https://u/x.jpg"}}""")))
        assertNull(PersonPhotos.photoFrom(summary("""{"type":"disambiguation","description":"actor, singer","thumbnail":{"source":"https://u/x.jpg"}}""")))
    }

    @Test fun noThumbnailOrANonHttpsOneIsNull() {
        assertNull(PersonPhotos.photoFrom(summary("""{"type":"standard","description":"American actress"}""")))
        assertNull(PersonPhotos.photoFrom(summary("""{"type":"standard","description":"American actress","thumbnail":{"source":"http://u/x.jpg"}}""")))
    }

    @Test fun onlyAnAnswerIsRemembered() {
        assertEquals(true, PersonPhotos.cacheable(200))
        assertEquals(true, PersonPhotos.cacheable(404))
        assertEquals("a network error has no code", false, PersonPhotos.cacheable(null))
        assertEquals(false, PersonPhotos.cacheable(503))
        assertEquals(false, PersonPhotos.cacheable(429))
    }
}

/** P131: Prime Video's rows have no IMDb link, so the Info sheet finds the title in Cinemeta by name. */
class CinemetaTitleMatchTest {
    private fun metas(json: String) = Json.parseToJsonElement(json).let { (it as kotlinx.serialization.json.JsonArray).map { e -> e.jsonObject } }

    @Test fun titlesMatchWithoutCaseAccentsOrPunctuation() {
        assertEquals("amelie", normTitle("Amélie"))
        assertEquals("spider man no way home", normTitle("Spider-Man: No Way Home"))
    }

    @Test fun anExactNameAndNearYearIsTheMatch() {
        val m = metas("""[{"id":"tt1","imdb_id":"tt1","name":"Dune","type":"movie","releaseInfo":"1984"},
            {"id":"tt2","imdb_id":"tt2","name":"Dune","type":"movie","releaseInfo":"2021"}]""")
        assertEquals("movie" to "tt2", pickCinemetaMatch(m, "Dune", 2021))
        assertEquals("movie" to "tt1", pickCinemetaMatch(m, "Dune", null))
    }

    @Test fun aDifferentNameOrAFarYearIsNotAMatch() {
        val m = metas("""[{"id":"tt3","name":"Dune: Part Two","type":"movie","releaseInfo":"2024"},
            {"id":"tt4","name":"Fargo","type":"series","releaseInfo":"2014–2024"}]""")
        assertNull(pickCinemetaMatch(m, "Dune", 2021))
        assertNull(pickCinemetaMatch(m, "Fargo", 1996))
        assertEquals("series" to "tt4", pickCinemetaMatch(m, "Fargo", 2014))
    }
}
