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

    private fun acl(action: String, name: String): android.content.Intent {
        val device = android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:01")
        org.robolectric.Shadows.shadowOf(device).setName(name)
        return android.content.Intent(action).putExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, device)
    }

    @Test fun aBluetoothLinkBroadcastReachesTheManifestReceiverAndShowsABanner() {
        // No accessibility service is up here, so this is the fallback path: the process was started by the broadcast alone.
        dev.glasslauncher.system.AclReceiver().onReceive(RuntimeEnvironment.getApplication(), acl(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED, "Sony WH-1000XM5"))
        ShadowLooper.idleMainLooper()
        assertEquals("Sony WH-1000XM5: Connected", ShadowToast.getTextOfLatestToast())
    }

    @Test fun aLinkBroadcastForAnotherActionShowsNothing() {
        dev.glasslauncher.system.AclReceiver().onReceive(RuntimeEnvironment.getApplication(), acl(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED, "Other Buds"))
        ShadowLooper.idleMainLooper()
        assertEquals(null, ShadowToast.getLatestToast())
    }
}
