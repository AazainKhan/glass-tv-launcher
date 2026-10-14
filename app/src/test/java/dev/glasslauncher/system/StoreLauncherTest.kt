package dev.glasslauncher.system

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreLauncherTest {
    @Test fun fireTriesItsOwnStoreFirstAndEverythingElseTriesMarket() {
        assertEquals(listOf("amzn://apps/android?p=com.x", "market://details?id=com.x"), StoreLauncher.appLinks(true, "com.x"))
        assertEquals(listOf("market://details?id=com.x", "amzn://apps/android?p=com.x"), StoreLauncher.appLinks(false, "com.x"))
        assertEquals("amzn://apps/android?s=", StoreLauncher.homeLinks(true).first())
        assertEquals("market://search?q=", StoreLauncher.homeLinks(false).first())
    }
}
