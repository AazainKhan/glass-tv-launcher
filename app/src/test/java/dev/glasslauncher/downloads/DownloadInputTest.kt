package dev.glasslauncher.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadInputTest {
    @Test fun aBareHostGetsHttps() {
        assertEquals(Entry.Url("https://example.com/app.apk"), DownloadInput.parse("example.com/app.apk"))
        assertEquals(Entry.Url("https://example.com"), DownloadInput.parse("  example.com "))
    }

    @Test fun httpsStaysAndHttpIsRefused() {
        assertEquals(Entry.Url("https://example.com/a"), DownloadInput.parse("https://example.com/a"))
        assertEquals(Entry.Url("https://example.com/a"), DownloadInput.parse("HTTPS://example.com/a"))
        assertEquals(Entry.NeedsHttps, DownloadInput.parse("http://example.com/a"))
        assertEquals(Entry.NeedsHttps, DownloadInput.parse("HTTP://example.com/a"))
    }

    @Test fun otherSchemesAndJunkAreInvalid() {
        assertEquals(Entry.Invalid, DownloadInput.parse("ftp://example.com/a"))
        assertEquals(Entry.Invalid, DownloadInput.parse("intent://x#Intent;end"))
        assertEquals(Entry.Invalid, DownloadInput.parse(""))
        assertEquals(Entry.Invalid, DownloadInput.parse("two words"))
    }

    @Test fun aWordOrNumberIsACode() {
        assertEquals(Entry.Code("42"), DownloadInput.parse("42"))
        assertEquals(Entry.Code("tv"), DownloadInput.parse(" tv "))
    }

    @Test fun historyIsNewestFirstWithoutRepeats() {
        var h = emptyList<String>()
        h = DownloadInput.pushHistory(h, "a.com")
        h = DownloadInput.pushHistory(h, "b.com")
        h = DownloadInput.pushHistory(h, "a.com")
        assertEquals(listOf("a.com", "b.com"), h)
        assertEquals(h, DownloadInput.pushHistory(h, "  "))
    }

    @Test fun historyKeepsTwenty() {
        val h = (1..30).fold(emptyList<String>()) { acc, i -> DownloadInput.pushHistory(acc, "site$i.com") }
        assertEquals(20, h.size)
        assertEquals("site30.com", h.first())
        assertEquals("site11.com", h.last())
    }

    @Test fun aLinkIsConfirmedOncePerLinkNotPerSite() {
        var accepted = emptySet<String>()
        assertTrue(DownloadInput.needsConfirm("https://Example.com/a", accepted))
        accepted = DownloadInput.accept(accepted, "https://Example.com/a")
        assertFalse(DownloadInput.needsConfirm("https://example.com/a", accepted))
        assertFalse(DownloadInput.needsConfirm("https://example.com/a#frag", accepted))
        assertTrue(DownloadInput.needsConfirm("https://example.com/other", accepted)) // same site, other file
        assertTrue(DownloadInput.needsConfirm("https://example.com/a?x=1", accepted))
        assertTrue(DownloadInput.needsConfirm("https://other.org/a", accepted))
    }

    @Test fun historyKeepsNoQueryFragmentOrSignIn() {
        val h = DownloadInput.pushHistory(listOf("https://old.example/a?token=1"), "https://user:pw@files.example/app.apk?sig=SECRET&x=1#top")
        assertEquals(listOf("https://files.example/app.apk", "https://old.example/a"), h)
        assertEquals(listOf("tv"), DownloadInput.pushHistory(emptyList(), " tv "))
    }

    @Test fun onlyALinkIsTakenFromTheClipboard() {
        assertEquals("https://example.com/a?b=1", DownloadInput.linkOrNull("  https://example.com/a?b=1\n"))
        assertEquals(null, DownloadInput.linkOrNull("my password is hunter2"))
        assertEquals(null, DownloadInput.linkOrNull("http://example.com/a"))
        assertEquals(null, DownloadInput.linkOrNull(null))
    }

    @Test fun clearHistoryAlsoForgetsAcceptedLinks() {
        val d = dev.glasslauncher.data.DownloadsConfig(
            history = listOf("a.com"), acceptedUrls = setOf("https://a.com/x"), concurrent = 3,
            codes = listOf(dev.glasslauncher.data.MyCode("tv", "https://a.com/tv", "")),
        )
        val c = DownloadInput.cleared(d)
        assertTrue(c.history.isEmpty() && c.acceptedUrls.isEmpty())
        assertEquals(3, c.concurrent)
        assertEquals(1, c.codes.size)
    }
}
