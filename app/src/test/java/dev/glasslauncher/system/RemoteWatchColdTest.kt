package dev.glasslauncher.system

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** The remote watch in a process the system started for one broadcast (P209): what was seen before is read back, what is unknown shows nothing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RemoteWatchColdTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("remote_watch", Context.MODE_PRIVATE)
    private fun bootAt() = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

    @Before fun freshProcess() {
        prefs.edit().clear().commit()
        RemoteWatch.resetForTest()
    }

    private fun acl(action: String): Intent {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:02")
        Shadows.shadowOf(device).setName("Fire TV Remote")
        return Intent(action).putExtra(BluetoothDevice.EXTRA_DEVICE, device)
    }

    private fun seen(goneAgoMs: Long, boot: Long = bootAt()) =
        prefs.edit().putLong("boot", boot).putLong("gone", System.currentTimeMillis() - goneAgoMs).putBoolean("offered", false).commit()

    /** The manifest receiver is the only way a Bluetooth link reaches the remote watch now (P209 removed its runtime one). */
    @Test fun theManifestReceiverFeedsTheRemoteWatch() {
        AclReceiver().onReceive(context, acl(BluetoothDevice.ACTION_ACL_DISCONNECTED))
        assertTrue("the remote's disconnect reached RemoteWatch", prefs.getLong("gone", 0L) > 0L)
    }

    @Test fun aDisconnectIsWrittenDownForTheNextProcess() {
        RemoteWatch.onAcl(context, acl(BluetoothDevice.ACTION_ACL_DISCONNECTED))
        assertTrue("when it went", prefs.getLong("gone", 0L) > 0L)
    }

    @Test fun aRemoteGoneForAWhileGetsItsCardWhenHomeComesUpInAColdProcess() {
        seen(goneAgoMs = 60_000)
        RemoteWatch.evaluate(context)
        assertTrue(RemoteWatch.card.value)
        assertTrue("and it is not offered twice", prefs.getBoolean("offered", false))
    }

    @Test fun aRemoteGoneForLessThanTheThresholdIsGivenTime() {
        seen(goneAgoMs = 5_000)
        RemoteWatch.evaluate(context)
        assertFalse(RemoteWatch.card.value)
    }

    @Test fun nothingKnownShowsNoCard() {
        RemoteWatch.evaluate(context)
        assertFalse(RemoteWatch.card.value)
    }

    @Test fun stateFromAnotherBootIsUnknownUntilAFreshObservation() {
        seen(goneAgoMs = 60_000, boot = bootAt() - 3_600_000)
        RemoteWatch.evaluate(context)
        assertFalse(RemoteWatch.card.value)
    }

    @Test fun theRemoteComingBackClearsTheRememberedDisconnect() {
        seen(goneAgoMs = 60_000)
        RemoteWatch.onAcl(context, acl(BluetoothDevice.ACTION_ACL_CONNECTED))
        RemoteWatch.resetForTest()
        RemoteWatch.evaluate(context)
        assertEquals(0L, prefs.getLong("gone", -1L))
        assertFalse(RemoteWatch.card.value)
    }
}
