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
        assertEquals(BrowserDownload.Queue("https://files.example.com/app.apk", "app.apk", execOk = "apk"), BrowserDownload.decide(dl("https://files.example.com/app.apk", "app.apk"), setOf("files.example.com|apk")))
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

    @Test fun hostsThatAreNoPlainHostNameGetAFallbackLabel() {
        for (host in listOf("", "[2001:db8::1]", "my_host.example.com", "evil.com: Enter your bank password", "a b", "x/y", "a\n")) {
            assertEquals(host, "this site: Enter text", TextPrompt.of(Msg.TextRequest(1, host, "text", false))!!.title)
        }
        val long = "a".repeat(30) + "." + "b".repeat(30) + ".com"
        val title = TextPrompt.of(Msg.TextRequest(1, long, "text", false))!!.title
        assertTrue(title, title.length < 60 && title.contains("..."))
    }

    @Test fun passwordsAreFlagged() {
        assertTrue(TextPrompt.of(Msg.TextRequest(1, "example.com", "password", true))!!.password)
        assertTrue(TextPrompt.of(Msg.TextRequest(1, "example.com", "password", false))!!.password)
        assertTrue(TextPrompt.of(Msg.TextRequest(1, "example.com", "text", true))!!.password)
        assertTrue(!TextPrompt.of(Msg.TextRequest(1, "example.com", "text", false))!!.password)
    }

    @Test fun addressAndUnknownKindsAreRefused() {
        assertNull(TextPrompt.of(Msg.TextRequest(1, "example.com", "address", false)))   // the address bar has no page host
        assertNull(TextPrompt.of(Msg.TextRequest(1, "evil.com", "address", false)))
        assertNull(TextPrompt.of(Msg.TextRequest(1, "example.com", "<b>", false)))
    }

    @Test fun anApprovalCoversOnlyItsOwnExtension() {
        assertEquals("", (BrowserDownload.decide(dl("https://e.com/a.zip", "a.zip"), emptySet()) as BrowserDownload.Queue).execOk)
        assertEquals("apk", (BrowserDownload.decide(dl("https://e.com/a.apk", "a.apk"), setOf("e.com|apk")) as BrowserDownload.Queue).execOk)
        assertEquals("apk", (BrowserDownload.decide(dl("https://e.com/a.apk", "a.apk"), emptySet()) as BrowserDownload.Ask).allow)
    }

    @Test fun theEngineRecheckAllowsOnlyTheApprovedExtension() {
        val x = dev.glasslauncher.downloads.ExecutableTypes
        assertTrue(!x.offends(listOf("a.apk"), "application/vnd.android.package-archive", "apk"))
        assertTrue(!x.offends(listOf("a.zip"), "application/zip", ""))
        assertTrue(x.offends(listOf("a.apk"), null, ""))
        assertTrue(x.offends(listOf("a.apk", "b.exe"), null, "apk"))      // a different type behind the approved one
        assertTrue(x.offends(listOf("a.zip"), "application/vnd.android.package-archive", "apk"))   // an app disguised by name
        assertTrue(!x.offends(listOf("dl"), "application/vnd.android.package-archive", "application/vnd.android.package-archive"))
    }

    @Test fun phoneFormIsOfferedOnlyForPlainFields() {
        fun phone(kind: String, pw: Boolean = false) = TextPrompt.of(Msg.TextRequest(1, "example.com", kind, pw))!!.phoneOk
        assertTrue(phone("text")); assertTrue(phone("search")); assertTrue(phone("url"))
        assertTrue(!phone("password", true)); assertTrue(!phone("email")); assertTrue(!phone("tel")); assertTrue(!phone("text", true))
    }

    @Test fun oneKeyboardRequestAtATimeAndACancelFreesIt() {
        val g = TextGate(60_000)
        assertTrue(g.tryBegin(1, 0))
        assertTrue(!g.tryBegin(2, 1_000))          // busy
        g.end(2); assertTrue(!g.tryBegin(3, 2_000)) // another request's end does not free it
        g.end(1)                                    // cancelled or done
        assertTrue(g.tryBegin(4, 3_000))
        assertTrue(!g.tryBegin(5, 4_000))
        assertTrue(g.tryBegin(6, 70_000))           // a request that never closed lapses
        g.clear(); assertTrue(g.tryBegin(7, 70_001))
    }
}
