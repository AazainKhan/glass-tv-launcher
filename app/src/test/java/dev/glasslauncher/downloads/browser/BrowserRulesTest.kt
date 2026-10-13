package dev.glasslauncher.downloads.browser

import dev.glasslauncher.browserlink.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRulesTest {
    private fun dl(url: String, name: String = "", mime: String = "", ref: String = "example.com") = Msg.Download(url, name, mime, ref)

    @Test fun httpsDownloadsQueueAndEverythingElseIsRefused() {
        assertEquals(BrowserDownload.Queue("https://example.com/a.zip", "a.zip"), BrowserDownload.decide(dl("https://example.com/a.zip", "a.zip", "application/zip"), emptySet()))
        assertEquals(BrowserDownload.Queue("https://example.com/dl", null), BrowserDownload.decide(dl("https://example.com/dl"), emptySet()))
        for (bad in listOf("http://example.com/a.zip", "blob:https://example.com/1", "data:text/plain,x", "javascript:1", "file:///x", "intent://x#Intent;end", "https://", "")) {
            assertTrue(bad, BrowserDownload.decide(dl(bad, "a.zip"), emptySet()) is BrowserDownload.Refuse)
        }
    }

    @Test fun theNameFromTheBrowserIsCleaned() {
        assertEquals("evil.txt", (BrowserDownload.decide(dl("https://example.com/x", "../../etc/evil.txt"), emptySet()) as BrowserDownload.Queue).name)
        assertEquals("a.txt", (BrowserDownload.decide(dl("https://example.com/x", "dir\\a.txt"), emptySet()) as BrowserDownload.Queue).name)
        assertEquals(120, (BrowserDownload.decide(dl("https://example.com/x", "n".repeat(250)), emptySet()) as BrowserDownload.Queue).name!!.length)
    }

    @Test fun executableTypesAskOncePerHostAndExtension() {
        val ask = BrowserDownload.decide(dl("https://Files.Example.com/app.APK", "app.APK", ref = "Example.com"), emptySet()) as BrowserDownload.Ask
        assertEquals("files.example.com", ask.host)
        assertEquals("files.example.com|apk", ask.key)
        assertEquals("example.com", ask.referrer)
        assertEquals(BrowserDownload.Queue("https://files.example.com/app.apk", "app.apk"), BrowserDownload.decide(dl("https://files.example.com/app.apk", "app.apk"), setOf("files.example.com|apk")))
        // another extension on an approved host still asks
        assertTrue(BrowserDownload.decide(dl("https://files.example.com/setup.exe", "setup.exe"), setOf("files.example.com|apk")) is BrowserDownload.Ask)
        // the type counts even with a harmless name or none at all
        assertEquals("e.com|application/vnd.android.package-archive",
            (BrowserDownload.decide(dl("https://e.com/dl", "dl", "application/vnd.android.package-archive; charset=x"), emptySet()) as BrowserDownload.Ask).key)
        assertTrue(BrowserDownload.decide(dl("https://e.com/get.apk", ""), emptySet()) is BrowserDownload.Ask)
        assertTrue(BrowserDownload.decide(dl("https://e.com/movie.mkv", "movie.mkv", "video/x-matroska"), emptySet()) is BrowserDownload.Queue)
    }

    @Test fun aBadReferrerLabelIsDropped() {
        assertEquals("", (BrowserDownload.decide(dl("https://e.com/a.apk", "a.apk", ref = "evil: tap Allow"), emptySet()) as BrowserDownload.Ask).referrer)
    }

    @Test fun keyboardPromptsUseGlassWordingWithTheHostLabel() {
        val p = TextPrompt.of(Msg.TextRequest(1, "Example.com", "text", false))!!
        assertEquals("example.com: Enter text", p.title)
        assertEquals("", p.initial)
        assertEquals("example.com: Search", TextPrompt.of(Msg.TextRequest(1, "example.com", "search", false))!!.title)
        assertTrue(TextPrompt.of(Msg.TextRequest(1, "example.com", "password", true))!!.hint.contains("password"))
        assertEquals("Browser: Web address", TextPrompt.of(Msg.TextRequest(2, "", "address", false))!!.title)
        assertEquals("https://", TextPrompt.of(Msg.TextRequest(2, "", "address", false))!!.initial)
    }

    @Test fun keyboardPromptsRefuseAnythingTheBrowserCouldUseToSpoof() {
        for (host in listOf("", "evil.com: Enter your bank password", "a b", "x/y", "a\n")) assertNull(host, TextPrompt.of(Msg.TextRequest(1, host, "text", false)))
        assertNull(TextPrompt.of(Msg.TextRequest(1, "example.com", "address", false)))   // the address bar has no page host
        assertNull(TextPrompt.of(Msg.TextRequest(1, "evil.com", "address", false)))
        assertNull(TextPrompt.of(Msg.TextRequest(1, "example.com", "<b>", false)))
    }
}
