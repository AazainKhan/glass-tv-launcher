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
}
