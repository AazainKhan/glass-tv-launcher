package com.glasslauncher.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTest {
    private val N = "abc123"

    // Reading order: a row of three at the top, two links in a column under the middle one, one far to the right below.
    private val boxes = listOf(
        PageBox(0f, 0f, 100f, 20f), PageBox(120f, 0f, 100f, 20f), PageBox(240f, 0f, 100f, 20f),
        PageBox(120f, 60f, 100f, 20f), PageBox(120f, 100f, 100f, 20f), PageBox(600f, 100f, 100f, 20f),
    )

    @Test fun rightAndLeftStayInTheRow() {
        assertEquals(1, LinkNav.next(boxes, 0, Dir.Right))
        assertEquals(2, LinkNav.next(boxes, 1, Dir.Right))
        assertNull(LinkNav.next(boxes, 2, Dir.Right))
        assertEquals(0, LinkNav.next(boxes, 1, Dir.Left))
        assertEquals(4, LinkNav.next(boxes, 5, Dir.Left))
    }

    @Test fun downAndUpPreferTheAlignedTarget() {
        assertEquals(3, LinkNav.next(boxes, 1, Dir.Down))
        assertEquals(4, LinkNav.next(boxes, 3, Dir.Down))
        assertEquals(1, LinkNav.next(boxes, 3, Dir.Up))
        assertNull(LinkNav.next(boxes, 1, Dir.Up))
        assertNull(LinkNav.next(boxes, 4, Dir.Down))
        assertEquals(3, LinkNav.next(boxes, 0, Dir.Down))
    }

    @Test fun noCurrentOrBadIndexGivesNothing() {
        assertNull(LinkNav.next(boxes, -1, Dir.Down))
        assertNull(LinkNav.next(emptyList(), 0, Dir.Down))
    }

    @Test fun firstPicksTheFirstTargetInView() {
        assertEquals(0, LinkNav.first(boxes, 0f, 500f))
        assertEquals(3, LinkNav.first(boxes, 50f, 500f))
        assertEquals(0, LinkNav.first(boxes, 5000f, 6000f))
        assertNull(LinkNav.first(emptyList(), 0f, 100f))
    }

    private fun reply(body: String, nonce: String = N) = "\"$nonce~$body\""

    @Test fun scanParsesTheScriptsReply() {
        val scan = Scan.parse(reply("1;0,0,100,20,0|120,0,100,20,1;1280,720,0,300"), N)!!
        assertEquals(1, scan.current)
        assertEquals(2, scan.boxes.size)
        assertTrue(scan.boxes[1].text)
        assertEquals(300f, scan.viewTop)
        assertEquals(1020f, scan.viewBottom)
        assertEquals(0, Scan.parse(reply("-1;;1280,720,0,0"), N)!!.boxes.size)
        assertNull(Scan.parse(null, N)); assertNull(Scan.parse("null", N)); assertNull(Scan.parse("\"garbage\"", N))
    }

    @Test fun aForgedOrStaleReplyIsRefused() {
        val good = "0;0,0,100,20,0;1280,720,0,0"
        assertNull(Scan.parse("\"$good\"", N))                        // no marker at all
        assertNull(Scan.parse(reply(good, "zzz"), N))                  // a page guessing the marker
        assertNull(Scan.parse(reply(good, "abc12"), N))                // a prefix of the real one
        assertNull(Scan.parse(reply(good, "other"), N))                // reply left over from an earlier load
        assertEquals(null, Reply.textKind("\"T|password\"", N))
        assertEquals("password", Reply.textKind(reply("T|password"), N))
        assertEquals("text", Reply.textKind(reply("T|<script>alert(1)</script>"), N))   // unknown kinds are plain text
        assertNull(Reply.textKind(reply("C"), N))
    }

    @Test fun nanInfiniteAndInvalidRectsAreRefused() {
        val view = "1280,720,0,0"
        for (box in listOf("NaN,0,10,10,0", "0,Infinity,10,10,0", "0,0,-5,10,0", "0,0,10,0,0", "0,0,10,10,2", "0,0,10,10", "0,0,10,10,0,1", "1e30,0,10,10,0", "a,b,c,d,e")) {
            assertNull(box, Scan.parse(reply("0;$box;$view"), N))
        }
        assertNull(Scan.parse(reply("0;0,0,10,10,0;NaN,720,0,0"), N))
        assertNull(Scan.parse(reply("5;0,0,10,10,0;$view"), N))       // current index outside the list
        assertNull(Scan.parse(reply("x;0,0,10,10,0;$view"), N))
    }

    @Test fun moreThanTheCapIsRefused() {
        fun many(n: Int) = (0 until n).joinToString("|") { "0,${it * 30},10,10,0" }
        assertEquals(Scan.MAX_BOXES, Scan.parse(reply("-1;${many(Scan.MAX_BOXES)};1,1,0,0"), N)!!.boxes.size)
        assertNull(Scan.parse(reply("-1;${many(Scan.MAX_BOXES + 1)};1,1,0,0"), N))
    }

    @Test fun jsStringsRoundTripAndUnquoteIsStrict() {
        val s = "a \"quoted\" \\ line\nbreak é \u2028 \u0001"
        assertEquals(s, Js.unquote(Js.quote(s)))
        assertEquals("\"a \\\"quoted\\\" \\\\ line\\nbreak é \\u2028 \\u0001\"", Js.quote(s))
        assertEquals("x\u0001", Js.unquote("\"x\\u0001\""))
        assertEquals("a/b", Js.unquote("\"a\\/b\""))
        for (bad in listOf("\"\\u12\"", "\"\\u12\\n\"", "\"\\uZZZZ\"", "\"abc\\\"", "abc", "null", "\"", null)) assertNull(bad, Js.unquote(bad))
    }

    @Test fun theScriptCarriesItsNonceAndNoFixedGlobal() {
        val s = PageScript(N)
        assertTrue(s.inject().contains("var N='$N'"))
        assertFalse(s.inject().contains("%N%"))
        assertFalse(s.inject().contains("window.__gl)"))     // the old "already defined" early-out is gone
        assertTrue(s.scan().contains("__gl$N"))
        assertFalse(PageScript("ffff").scan() == s.scan())
        assertEquals("window['__glabc123'].setText(\"a\\\"b\")", s.setText("a\"b"))
    }

    private fun go(s: String) = (BrowserRules.address(s) as? Address.Go)?.url

    @Test fun addressesFollowTheUrlEntryRules() {
        assertEquals("https://example.com", go("example.com"))
        assertEquals("https://example.com/a?b=1", go("  https://example.com/a?b=1 "))
        assertEquals("https://localhost:8080", go("localhost:8080"))
        assertEquals("https://duckduckgo.com/?q=fire+tv+apps", go("fire tv apps"))
        assertEquals("https://duckduckgo.com/?q=weather", go("weather"))
        assertEquals("https://example.com/x", go("HTTPS://example.com/x"))
    }

    @Test fun httpAndOtherSchemesAreRefused() {
        assertEquals(Address.Refused(BrowserRules.NEEDS_HTTPS), BrowserRules.address("http://example.com"))
        for (bad in listOf("", "  ", "intent://scan/#Intent;scheme=zxing;end", "javascript:alert(1)", "file:///sdcard/x", "content://x/y",
            "market://details?id=a", "ftp://example.com", "data:text/html,hi", "custom-app://open", "INTENT:foo", "mailto:a@b.com", "https://")) {
            assertNull(bad, go(bad))
        }
    }

    @Test fun onlyHttpsPagesMayBeNavigatedTo() {
        assertTrue(BrowserRules.allowNavigation("https://example.com/x"))
        assertTrue(BrowserRules.allowNavigation("about:blank"))
        for (bad in listOf("http://example.com", "intent://x#Intent;end", "market://details", "file:///etc/hosts", "javascript:1", "tel:123", "content://a/b")) {
            assertFalse(bad, BrowserRules.allowNavigation(bad))
        }
    }

    @Test fun glassOnlyOpensHttpsUrls() {
        assertEquals("https://example.com/a", BrowserRules.openable("https://example.com/a"))
        for (bad in listOf(null, "", "http://example.com", "intent://x#Intent;end", "javascript:alert(1)", "https://exa mple.com")) assertNull(bad, BrowserRules.openable(bad))
    }

    @Test fun pointerAcceleratesWhileHeldAndCaps() {
        assertEquals(10, PointerSpeed.step(0))
        assertTrue(PointerSpeed.step(5) > PointerSpeed.step(1))
        assertEquals(60, PointerSpeed.step(500))
    }
}
