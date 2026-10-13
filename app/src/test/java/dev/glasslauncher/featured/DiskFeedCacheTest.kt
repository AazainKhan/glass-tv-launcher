package dev.glasslauncher.featured

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** P105 #12: a feed seen before comes from disk at once, for many apps, not just the last one. */
class DiskFeedCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun feed(heading: String) = FeaturedFeed(heading, emptyList())

    @Test fun severalFeedsSurviveNotOnlyTheLastOne() {
        val cache = DiskFeedCache(tmp.newFolder("featured"))
        cache.write("4:a", feed("A")); cache.write("4:b", feed("B")); cache.write("4:c", feed("C"))
        assertEquals("A", cache.read("4:a")?.heading)
        assertEquals("B", cache.read("4:b")?.heading)
        assertEquals("C", cache.read("4:c")?.heading)
        assertNull(cache.read("4:never"))
    }

    @Test fun theLeastRecentlyUsedGoWhenTheDirectoryIsFull() {
        val dir = tmp.newFolder("featured")
        val cache = DiskFeedCache(dir, max = 2)
        cache.write("k1", feed("1")); Thread.sleep(15)
        cache.write("k2", feed("2")); Thread.sleep(15)
        assertNotNull(cache.read("k1")); Thread.sleep(15) // k1 used again: k2 is now the oldest
        cache.write("k3", feed("3"))
        assertNotNull("kept: used and newest", cache.read("k1"))
        assertNotNull(cache.read("k3"))
        assertNull("dropped", cache.read("k2"))
    }

    @Test fun sectionsSurviveTheDisk() {
        val cache = DiskFeedCache(tmp.newFolder("featured"))
        val sec = listOf(FeaturedSection("From A", listOf(FeaturedItem("a", "A"))), FeaturedSection("Trending", listOf(FeaturedItem("b", "B"))))
        cache.write("5:y", FeaturedFeed("YouTube", sec.flatMap { it.items }, sec))
        assertEquals(sec, cache.read("5:y")?.sections)
        cache.write("5:z", feed("Z"))
        assertEquals(emptyList<FeaturedSection>(), cache.read("5:z")?.sections)
    }

    @Test fun aFileForAnotherKeyIsNotTakenForThisOne() {
        val cache = DiskFeedCache(tmp.newFolder("featured"))
        cache.write("4:x", feed("X"))
        assertNull(cache.read("5:x"))
    }

    @Test fun theSingleFileOfTheOldVersionIsStillReadForItsKey() {
        val legacy = tmp.newFile("featured.json")
        val cache = DiskFeedCache(tmp.newFolder("featured"), legacy = legacy)
        cache.write("4:seed", feed("seed")) // writes the new place
        legacy.writeText("""{"key":"4:old","heading":"Old","items":[]}""")
        assertEquals("Old", cache.read("4:old")?.heading)
        assertNull(cache.read("4:other"))
    }
}
