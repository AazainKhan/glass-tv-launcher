package dev.glasslauncher.system

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Power Off's mode choice (I09): CEC when granted, sleep-only otherwise, unavailable with neither root nor the keys service. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PowerControlTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun neitherRootNorKeysServiceIsUnavailable() {
        assertEquals(PowerControl.Mode.Unavailable, PowerControl.mode(ctx, root = false, keysServiceOn = false))
    }

    @Test fun rootOrKeysServiceWithNoCecGrantSleepsOnly() {
        // Robolectric has no "hdmi_control" system service, so cecAvailable() is always false here.
        assertEquals(PowerControl.Mode.SleepOnly, PowerControl.mode(ctx, root = true, keysServiceOn = false))
        assertEquals(PowerControl.Mode.SleepOnly, PowerControl.mode(ctx, root = false, keysServiceOn = true))
    }

    @Test fun noCecServiceReportsFailureSoTheTileCanSayTheTvStaysOn() {
        var told = false
        kotlinx.coroutines.runBlocking { PowerControl.standby(ctx) { told = true } }
        org.junit.Assert.assertTrue(told)
    }
}
