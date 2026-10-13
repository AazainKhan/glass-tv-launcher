package dev.glasslauncher.featured

import dev.glasslauncher.data.FeaturedConfig
import dev.glasslauncher.data.FeaturedMode
import dev.glasslauncher.data.FeaturedSourceId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class YouTubeFeedTest {
    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
         <title>Marques Brownlee</title>
         <author><name>Marques Brownlee</name></author>
         <published>2008-03-21T15:25:54+00:00</published>
         <entry>
          <yt:videoId>vid0000001</yt:videoId>
          <title>First &amp; best</title>
          <author><name>Marques Brownlee</name></author>
          <published>2026-10-08T21:00:40+00:00</published>
          <media:group>
           <media:title>First &amp; best</media:title>
           <media:description>

        Line one of the description.
        Line two.</media:description>
          </media:group>
         </entry>
         <entry>
          <yt:videoId>vid0000002</yt:videoId>
          <title>Older</title>
          <author><name>Marques Brownlee</name></author>
          <published>2026-10-02T20:50:25+00:00</published>
         </entry>
         <entry><title>No id</title></entry>
        </feed>
    """.trimIndent()

    @Test fun parsesEntries() {
        val items = YouTube.parseFeed(rss)
        assertEquals(listOf("vid0000001", "vid0000002"), items.map { it.first.id })
        val first = items.first().first
        assertEquals("First & best", first.title)
        assertEquals("Marques Brownlee", first.subtitle)
        assertEquals("Line one of the description.", first.description)
        assertEquals("https://i.ytimg.com/vi/vid0000001/hq720.jpg", first.image)
        assertEquals("https://www.youtube.com/watch?v=vid0000001", first.link)
        assertEquals("2026-10-08T21:00:40+00:00", items.first().second)
        assertTrue("io.gh.reisxd.tizentube.cobalt" in first.packages)
    }

    @Test fun malformedFeedGivesNothing() {
        assertTrue(YouTube.parseFeed("<feed><entry>").isEmpty())
        assertTrue(YouTube.parseFeed("").isEmpty())
    }

    @Test fun channelIdsFromIdsAndUrls() {
        val a = "UCBJycsmduvYEL83R_U4JriQ"
        val b = "UCX6OQ3DkcsbYNE6H8uQQuVA"
        assertEquals(listOf(a, b), YouTube.channelIds("$a, https://www.youtube.com/channel/$b\n$a"))
        assertTrue(YouTube.channelIds("  @mkbhd  ").isEmpty())
    }

    @Test fun channelsAloneMakeTheSourceUsable() {
        assertFalse(AppSources.usable(FeaturedConfig(source = FeaturedSourceId.YouTube)))
        assertTrue(AppSources.usable(FeaturedConfig(source = FeaturedSourceId.YouTube, youtubeChannels = "UCBJycsmduvYEL83R_U4JriQ")))
        assertTrue(AppSources.usable(FeaturedConfig(source = FeaturedSourceId.YouTube, youtubeKey = "k")))
    }

    @Test fun focusedYouTubeAppShowsNothingUntilConfigured() {
        val cfg = FeaturedConfig(mode = FeaturedMode.FocusedApp)
        assertNull(AppSources.effective(cfg, "io.gh.reisxd.tizentube.cobalt"))
        val set = cfg.copy(youtubeChannels = "UCBJycsmduvYEL83R_U4JriQ")
        assertEquals(FeaturedSourceId.YouTube, AppSources.effective(set, "io.gh.reisxd.tizentube.cobalt")?.source)
    }

    @Test fun neitherChannelsNorKeyFailsWithoutNetwork() {
        try {
            runBlocking { YouTube.load(OkHttpClient(), FeaturedConfig(source = FeaturedSourceId.YouTube)) }
            fail("expected a failure")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test fun configRoundTripsAndOldConfigsGetNoChannels() {
        val json = Json { ignoreUnknownKeys = true }
        val cfg = FeaturedConfig(youtubeChannels = "UCBJycsmduvYEL83R_U4JriQ")
        assertEquals(cfg, json.decodeFromString(FeaturedConfig.serializer(), json.encodeToString(FeaturedConfig.serializer(), cfg)))
        assertEquals("", json.decodeFromString(FeaturedConfig.serializer(), "{}").youtubeChannels)
    }
}
