package dev.glasslauncher

import android.bluetooth.BluetoothClass
import dev.glasslauncher.system.BannerQueue
import dev.glasslauncher.system.DeviceKind
import dev.glasslauncher.system.DeviceToasts
import dev.glasslauncher.system.Notice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceToastsTest {
    @Test fun detailNamesTheStateAndABatteryOnlyWhenKnown() {
        assertEquals("Connected", DeviceToasts.detail(true, null))
        assertEquals("Connected", DeviceToasts.detail(true, -1))
        assertEquals("Connected · 80%", DeviceToasts.detail(true, 80))
        assertEquals("Disconnected", DeviceToasts.detail(false, 80))
    }

    @Test fun kindsFollowTheBluetoothClass() {
        val av = BluetoothClass.Device.Major.AUDIO_VIDEO
        assertEquals(DeviceKind.Headphones, DeviceToasts.kindOf(av, BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, "WH-1000XM5"))
        assertEquals(DeviceKind.Speaker, DeviceToasts.kindOf(av, BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, "JBL Flip"))
        assertEquals(DeviceKind.Gamepad, DeviceToasts.kindOf(BluetoothClass.Device.Major.PERIPHERAL, 0x0508, "Xbox Wireless Controller"))
        assertEquals(DeviceKind.Keyboard, DeviceToasts.kindOf(BluetoothClass.Device.Major.PERIPHERAL, 0x0540, "Keys"))
        assertEquals(DeviceKind.Remote, DeviceToasts.kindOf(BluetoothClass.Device.Major.PERIPHERAL, 0x0500, "Some Remote"))
        assertEquals(DeviceKind.Remote, DeviceToasts.kindOf(null, null, "Amazon Fire TV Remote"))
        assertEquals(DeviceKind.Other, DeviceToasts.kindOf(null, null, "Thing"))
    }

    @Test fun nameless_virtual_and_address_only_devices_are_not_shown() {
        assertFalse(DeviceToasts.worthShowing(null))
        assertFalse(DeviceToasts.worthShowing(" "))
        assertFalse(DeviceToasts.worthShowing("AA:BB:CC:DD:EE:FF"))
        assertFalse(DeviceToasts.worthShowing("Virtual", virtual = true))
        assertTrue(DeviceToasts.worthShowing("Xbox Wireless Controller"))
    }

    @Test fun theSameEventTwiceWithinTheWindowIsOneBanner() {
        val g = DeviceToasts.Gate(4_000)
        assertTrue(g.allow("Pad", true, 1_000))
        assertFalse("a repeat", g.allow("Pad", true, 2_000))
        assertTrue("the opposite state is news", g.allow("Pad", false, 2_500))
        assertTrue("after the window", g.allow("Pad", false, 7_000))
        assertTrue("another device", g.allow("Buds", true, 7_100))
    }

    @Test fun aDeviceThatConnectsThenDisconnectsIsTwoBannersNotOne() {
        val q = BannerQueue()
        assertTrue(q.offer(Notice("Pad", 3_500, false, "Connected", 1)) is BannerQueue.Step.Show)
        assertTrue("same words extends", q.offer(Notice("Pad", 3_500, false, "Connected", 1)) is BannerQueue.Step.Extend)
        assertTrue("other state queues", q.offer(Notice("Pad", 3_500, false, "Disconnected", 1)) is BannerQueue.Step.Wait)
        assertEquals(1, q.waiting.size)
    }
}
