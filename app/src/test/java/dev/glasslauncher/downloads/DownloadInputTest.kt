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

    @Test fun aHostIsConfirmedOncePerHost() {
        var accepted = emptySet<String>()
        assertTrue(DownloadInput.needsHostConfirm("https://Example.com/a", accepted))
        accepted = DownloadInput.acceptHost(accepted, "https://Example.com/a")
        assertFalse(DownloadInput.needsHostConfirm("https://example.com/other", accepted))
        assertTrue(DownloadInput.needsHostConfirm("https://other.org/a", accepted))
    }
}
