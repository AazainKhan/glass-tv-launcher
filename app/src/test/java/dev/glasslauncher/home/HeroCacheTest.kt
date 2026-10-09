package dev.glasslauncher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HeroCacheTest {
    @Test fun keepsOnlyTheMostRecentlyUsed() {
        val c = HeroCache<String>(max = 2)
        c["a"] = "A"; c["b"] = "B"
        c["a"] // touch a: b is now the eldest
        c["c"] = "C"
        assertNotNull(c["a"]); assertNull(c["b"]); assertNotNull(c["c"])
        assertEquals(2, c.size)
    }

    @Test fun trimEmptiesIt() {
        val c = HeroCache<String>(max = 2)
        c["a"] = "A"
        c.trim()
        assertEquals(0, c.size)
    }
}
