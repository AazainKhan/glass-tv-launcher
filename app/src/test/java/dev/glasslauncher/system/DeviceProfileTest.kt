package dev.glasslauncher.system

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfileTest {
    @Test fun memoryTuningIsOfferedOnlyOnALowRamFireTv() {
        assertEquals(true, DeviceProfile.memoryTuningApplies(isFire = true, totalRamMb = 1_700))
        assertEquals("a 4 GB Fire TV has room", false, DeviceProfile.memoryTuningApplies(isFire = true, totalRamMb = 3_900))
        assertEquals("a Google TV box is left alone", false, DeviceProfile.memoryTuningApplies(isFire = false, totalRamMb = 1_700))
        assertEquals("unknown RAM is left alone", false, DeviceProfile.memoryTuningApplies(isFire = true, totalRamMb = 0))
    }

    /** 2026-10-14: Fire Settings' direct-boot FallbackHome was taken for a stock Home and disabled; the stick hung at boot. */
    @Test fun aBootFallbackHomeIsNeverAStockHome() {
        val glass = "dev.glasslauncher"
        // Fire's and AOSP's settings FallbackHome: direct-boot aware, priority -1000.
        org.junit.Assert.assertFalse(DeviceProfile.isStockHome("com.amazon.tv.settings.v2", glass, system = true, directBootAware = true, priority = -1000))
        org.junit.Assert.assertFalse(DeviceProfile.isStockHome("com.android.settings", glass, system = true, directBootAware = true, priority = -1000))
        // Even declared like a normal Home, a settings or setup package is never disabled.
        org.junit.Assert.assertFalse(DeviceProfile.isStockHome("com.amazon.tv.settings.v2", glass, system = true, directBootAware = false, priority = 0))
        org.junit.Assert.assertFalse(DeviceProfile.isStockHome("com.google.android.setupwizard", glass, system = true, directBootAware = false, priority = 0))
        org.junit.Assert.assertFalse(DeviceProfile.isStockHome("com.android.provision", glass, system = true, directBootAware = false, priority = 0))
        // Any negative-priority or direct-boot handler is a fallback, whatever its name.
        org.junit.Assert.assertFalse(DeviceProfile.isStockHome("com.vendor.home", glass, system = true, directBootAware = true, priority = 0))
        org.junit.Assert.assertFalse(DeviceProfile.isStockHome("com.vendor.home", glass, system = true, directBootAware = false, priority = -1))
        // Glass itself and non-system apps never.
        org.junit.Assert.assertFalse(DeviceProfile.isStockHome(glass, glass, system = true, directBootAware = false, priority = 0))
        org.junit.Assert.assertFalse(DeviceProfile.isStockHome("com.other.launcher", glass, system = false, directBootAware = false, priority = 0))
        // The real stock launchers are.
        org.junit.Assert.assertTrue(DeviceProfile.isStockHome("com.amazon.tv.launcher", glass, system = true, directBootAware = false, priority = 0))
        org.junit.Assert.assertTrue(DeviceProfile.isStockHome("com.google.android.tvlauncher", glass, system = true, directBootAware = false, priority = 0))
    }
}
