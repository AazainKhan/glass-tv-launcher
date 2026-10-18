package dev.glasslauncher

import dev.glasslauncher.system.RemoteNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceToastsCcTest {
    private fun offer() {
        RemoteNotice.showDevice(RuntimeEnvironment.getApplication(), "Pad", "Connected", 1)
        ShadowLooper.idleMainLooper()
    }

    @Test fun aDeviceBannerIsDroppedWhileControlCenterIsOpen() {
        RemoteNotice.controlCenterOpen = true
        try { offer() } finally { RemoteNotice.controlCenterOpen = false }
        assertEquals(null, ShadowToast.getLatestToast())
    }

    @Test fun andShownAgainOnceItCloses() {
        offer()
        assertNotNull(ShadowToast.getLatestToast())
    }
}
