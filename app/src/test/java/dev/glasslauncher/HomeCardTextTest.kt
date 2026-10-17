package dev.glasslauncher

import dev.glasslauncher.system.HomeSetup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCardTextTest {
    @Test fun fireTvIsShownTheFireLauncherCommand() {
        assertTrue(HomeSetup.homeCardText(true).contains("com.amazon.tv.launcher"))
    }

    @Test fun anyOtherDeviceNeverSeesAFireCommand() {
        val t = HomeSetup.homeCardText(false)
        assertFalse(t.contains("amazon", ignoreCase = true))
        assertFalse(t.contains("adb"))
        assertTrue(t.contains("Home Button"))
    }
}
