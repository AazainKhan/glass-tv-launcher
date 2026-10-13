package dev.glasslauncher.featured

import dev.glasslauncher.data.FeaturedConfig
import dev.glasslauncher.data.FeaturedMode
import dev.glasslauncher.data.FeaturedSourceId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

    private val chA = "UCBJycsmduvYEL83R_U4JriQ"
    private val chB = "UCX6OQ3DkcsbYNE6H8uQQuVA"

    private fun feed(vararg ids: String) = "<feed xmlns:yt=\"y\" xmlns:media=\"m\">" + ids.joinToString("") {
        "<entry><yt:videoId>$it</yt:videoId><title>t$it</title><published>2026-10-${it.takeLast(2)}T00:00:00+00:00</published></entry>"
    } + "</feed>"

    /** Client that sends every request to [server], whatever host the code asked for. */
    private fun clientFor(server: MockWebServer) = OkHttpClient.Builder().addInterceptor { chain ->
        val r = chain.request()
        chain.proceed(r.newBuilder().url(r.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()).build())
    }.build()

    private fun serve(server: MockWebServer, body: (String) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = body(request.requestUrl!!.queryParameter("channel_id")!!)
        }
    }

    private val cfg get() = FeaturedConfig(source = FeaturedSourceId.YouTube, youtubeChannels = "$chA $chB")

    @Test fun usesFeedThumbnail() {
        val xml = rss.replace("<media:group>", "<media:group><media:thumbnail url=\"https://i.ytimg.com/vi/vid0000001/hq2.jpg\"/>")
        assertEquals("https://i.ytimg.com/vi/vid0000001/hq2.jpg", YouTube.parseFeed(xml).first().first.image)
    }

    @Test fun doctypeAndExternalEntitiesAreRejected() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE feed [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <feed xmlns:yt="y"><entry><yt:videoId>vid0000001</yt:videoId><title>&x;</title></entry></feed>"""
        assertTrue(YouTube.parseFeed(xxe).isEmpty())
    }

    @Test fun oversizeBodyCountsAsFailure() {
        MockWebServer().use { server ->
            serve(server) { MockResponse().setBody(feed("vid0000001") + " ".repeat(600 * 1024)) }
            try {
                runBlocking { YouTube.load(clientFor(server), cfg) }
                fail("expected a failure")
            } catch (e: IllegalStateException) {
                assertEquals("Couldn't load YouTube videos", e.message)
            }
        }
    }

    @Test fun oneFailingChannelStillFillsTheRow() {
        MockWebServer().use { server ->
            serve(server) { id -> if (id == chA) MockResponse().setResponseCode(500) else MockResponse().setBody(feed("vid0000011", "vid0000012")) }
            val feed = runBlocking { YouTube.load(clientFor(server), cfg) }
            assertEquals(listOf("vid0000012", "vid0000011"), feed.items.map { it.id })
        }
    }

    @Test fun everyChannelFailingFails() {
        MockWebServer().use { server ->
            serve(server) { MockResponse().setResponseCode(404) }
            try {
                runBlocking { YouTube.load(clientFor(server), cfg) }
                fail("expected a failure")
            } catch (e: IllegalStateException) {
                assertEquals("Couldn't load YouTube videos", e.message)
            }
        }
    }

    @Test fun aChannelContributesAtMostTwelveNewest() {
        MockWebServer().use { server ->
            serve(server) { MockResponse().setBody(feed(*(10..24).map { "vid00000$it" }.toTypedArray())) }
            val one = cfg.copy(youtubeChannels = chA)
            val got = runBlocking { YouTube.load(clientFor(server), one) }
            assertEquals((24 downTo 13).map { "vid00000$it" }, got.items.map { it.id })
            assertTrue("one row is the plain feed", got.sections.isEmpty())
        }
    }

    @Test fun twoChannelsMakeSections() {
        MockWebServer().use { server ->
            serve(server) { id -> MockResponse().setBody(feed(if (id == chA) "vid0000011" else "vid0000012")) }
            val got = runBlocking { YouTube.load(clientFor(server), cfg) }
            assertEquals(3, got.sections.size)
            assertEquals(ShelfSections.NEW_HEADING, got.sections[0].heading)
            assertEquals(listOf("vid0000012", "vid0000011"), got.items.map { it.id })
        }
    }
}
