package dev.glasslauncher.system

import org.junit.Assert.assertEquals
import org.junit.Test

class AmazonStoreTest {
    @Test fun plainIdentifiersPass() {
        assertEquals("l_apps", AmazonStore.safeExtra("l_apps", "x"))
        assertEquals("appstore.v2-a", AmazonStore.safeExtra("appstore.v2-a", "x"))
    }

    @Test fun shellMetacharactersFallBackToTheDefault() {
        assertEquals("l_apps", AmazonStore.safeExtra("x'; reboot; '", "l_apps"))
        assertEquals("appstore", AmazonStore.safeExtra("a b", "appstore"))
        assertEquals("appstore", AmazonStore.safeExtra("\$(id)", "appstore"))
        assertEquals("appstore", AmazonStore.safeExtra("", "appstore"))
        assertEquals("appstore", AmazonStore.safeExtra(null, "appstore"))
        assertEquals("appstore", AmazonStore.safeExtra("a".repeat(65), "appstore"))
    }
}
