package dev.glasslauncher.browserlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Identical in both modules: the two sides must agree on every constant and on what decode accepts. */
class LinkTest {
    private fun roundTrip(m: Msg): Msg? = m.fields().let { f -> Msg.decode(m.what) { f[it] } }

    @Test fun constantsArePinned() {
        assertEquals("dev.glasslauncher.permission.BROWSER", Link.PERMISSION)
        assertEquals("com.glasslauncher.browser.BrowserActivity", Link.BROWSER_ACTIVITY)
        assertEquals("dev.glasslauncher.MainActivity", Link.GLASS_ACTIVITY)
        assertEquals(listOf(1, 2, 3, 10, 11, 12), listOf(Link.What.DOWNLOAD, Link.What.TEXT_REQUEST, Link.What.CLOSED, Link.What.TEXT_RESULT, Link.What.SHOW_GLASS, Link.What.NOTICE))
        assertEquals(setOf("url", "name", "mime", "referrer"), Msg.Download("u", "n", "m", "r").fields().keys)
        assertEquals(setOf("id", "host", "hint", "password"), Msg.TextRequest(1, "h", "text", false).fields().keys)
        assertEquals(setOf("id", "text"), Msg.TextResult(1, "t").fields().keys)
    }

    @Test fun messagesRoundTrip() {
        for (m in listOf(
            Msg.Download("https://example.com/a.zip", "a.zip", "application/zip", "example.com"),
            Msg.TextRequest(42, "example.com", "password", true), Msg.TextRequest(7, "", "address", false),
            Msg.Closed, Msg.TextResult(42, "héllo \"wörld\"\n"), Msg.ShowGlass, Msg.Notice("Downloading a.zip"),
        )) assertEquals(m, roundTrip(m))
    }

    @Test fun decodeRejectsBadInput() {
        assertNull(Msg.decode(99) { null })
        assertNull(Msg.decode(Link.What.DOWNLOAD) { if (it == "url") "https://x.com/a" else null })
        assertNull(Msg.decode(Link.What.TEXT_REQUEST) { mapOf("id" to "1", "host" to "a.com", "hint" to "script", "password" to "false")[it] })
        assertNull(Msg.decode(Link.What.TEXT_REQUEST) { mapOf("id" to "x", "host" to "a.com", "hint" to "text", "password" to "false")[it] })
        assertNull(Msg.decode(Link.What.TEXT_REQUEST) { mapOf("id" to "1", "host" to "a.com", "hint" to "text", "password" to "maybe")[it] })
        assertNull(Msg.decode(Link.What.TEXT_RESULT) { mapOf("id" to "1", "text" to "x".repeat(Link.MAX_TEXT + 1))[it] })
        assertNull(Msg.decode(Link.What.DOWNLOAD) { mapOf("url" to "https://x.com/" + "a".repeat(Link.MAX_URL), "name" to "n", "mime" to "m", "referrer" to "r")[it] })
    }

    @Test fun httpsHostAcceptsOnlyPlainHttpsUrls() {
        assertEquals("example.com", Link.httpsHost("https://Example.com/a?b=1#c"))
        assertEquals("a.b.example.com", Link.httpsHost("HTTPS://a.b.example.com:8443/x"))
        for (bad in listOf(null, "", "http://example.com", "javascript:alert(1)", "intent://x#Intent;end", "file:///etc/hosts", "https://", "https:///x",
            "https://exa mple.com", "https://example.com/\u0000", "https://-bad.com", "blob:https://example.com/1", "data:text/plain,x", "https://" + "a".repeat(Link.MAX_URL))) {
            assertNull(bad, Link.httpsHost(bad))
        }
    }

    @Test fun labelHostIsABareHostName() {
        assertEquals("example.com", Link.labelHost("Example.com"))
        for (bad in listOf("", "a b", "evil.com: Enter your password", "a/b", "x:80", "-a", "a\n")) assertNull(bad, Link.labelHost(bad))
    }

    @Test fun aMessageIsTrustedOnlyFromTheRightUidWithPermissionAndSignature() {
        assertTrue(Link.trusted(10050, 10050, true, true))
        assertFalse(Link.trusted(10051, 10050, true, true))
        assertFalse(Link.trusted(10050, null, true, true))
        assertFalse(Link.trusted(10050, 10050, false, true))
        assertFalse(Link.trusted(10050, 10050, true, false))
    }

    /** Link.kt is copied into both modules; nothing else keeps them equal, so this does. */
    @Test fun bothCopiesOfLinkKtAreIdentical() {
        val rel = "src/main/java/dev/glasslauncher/browserlink/Link.kt"
        val own = java.io.File(rel).readText()
        for (other in listOf("../app/$rel", "../browser/$rel").map(::File).filter { it.exists() }) assertEquals(other.path, own, other.readText())
    }
}
