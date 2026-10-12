package dev.glasslauncher.system

import dev.glasslauncher.system.RemotePairPolicy.Action
import dev.glasslauncher.system.RemotePairPolicy.Advert
import dev.glasslauncher.system.RemotePairPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePairPolicyTest {
    private val ad = Advert("AA:BB:CC:DD:EE:FF", amazon = true, hid = true, discoverable = true)
    private val state = State(bonded = emptySet(), remoteConnected = false, screenOn = true, nowMs = 1_000_000L)

    @Test fun anUnbondedDiscoverableRemoteIsBonded() = assertEquals(Action.Bond, RemotePairPolicy.decide(ad, state))

    @Test fun aBondedRemoteIsOnlyConnectedNeverUnbonded() {
        val s = state.copy(bonded = setOf(ad.address))
        assertEquals(Action.Connect, RemotePairPolicy.decide(ad, s))
        // Even after one or two failures: no removeBond on sight.
        assertEquals(Action.Connect, RemotePairPolicy.decide(ad, s.copy(connectFailures = 2)))
    }

    @Test fun aRemoteThatKeepsFailingToConnectIsRebondedOncePerTwoMinutes() {
        val s = state.copy(bonded = setOf(ad.address), connectFailures = RemotePairPolicy.REBOND_AFTER_FAILURES)
        assertEquals(Action.Rebond, RemotePairPolicy.decide(ad, s))
        assertEquals(Action.Connect, RemotePairPolicy.decide(ad, s.copy(lastRebondMs = s.nowMs - 30_000)))
        assertEquals(Action.Rebond, RemotePairPolicy.decide(ad, s.copy(lastRebondMs = s.nowMs - RemotePairPolicy.REBOND_EVERY_MS)))
    }

    @Test fun nothingHappensWhileTheScreenIsOffOrARemoteIsConnected() {
        assertEquals(Action.Ignore, RemotePairPolicy.decide(ad, state.copy(screenOn = false)))
        assertEquals(Action.Ignore, RemotePairPolicy.decide(ad, state.copy(remoteConnected = true)))
        assertFalse(RemotePairPolicy.shouldScan(screenOn = false, remoteConnected = false))
        assertFalse(RemotePairPolicy.shouldScan(screenOn = true, remoteConnected = true))
        assertTrue(RemotePairPolicy.shouldScan(screenOn = true, remoteConnected = false))
    }

    @Test fun aSleepingRemoteReconnectAdvertisingIsNotPairingMode() {
        // Not discoverable (a bonded remote waking up): never bonds, never rebonds.
        val reconnecting = ad.copy(discoverable = false)
        assertEquals(Action.Ignore, RemotePairPolicy.decide(reconnecting, state))
        assertEquals(Action.Ignore, RemotePairPolicy.decide(reconnecting, state.copy(bonded = setOf(ad.address), connectFailures = 9)))
    }

    @Test fun onlyAmazonHidAdvertisementsCount() {
        assertEquals(Action.Ignore, RemotePairPolicy.decide(ad.copy(amazon = false), state))
        assertEquals(Action.Ignore, RemotePairPolicy.decide(ad.copy(hid = false), state))
    }

    @Test fun aNearUnbondedRemoteThatIsNotDiscoverableIsAStaleBond() {
        val waking = ad.copy(discoverable = false, rssi = -50)
        assertEquals(Action.StaleBond, RemotePairPolicy.decide(waking, state))
        assertEquals("a far one is a neighbour's", Action.Ignore, RemotePairPolicy.decide(waking.copy(rssi = -80), state))
        assertEquals("we hold a bond with another remote", Action.Ignore, RemotePairPolicy.decide(waking, state.copy(haveBondedRemote = true)))
        assertEquals("a bonded one reconnecting is the system's job", Action.Ignore, RemotePairPolicy.decide(waking, state.copy(bonded = setOf(ad.address))))
        assertEquals("a discoverable one is bonded as before", Action.Bond, RemotePairPolicy.decide(ad.copy(rssi = -50), state))
    }

    @Test fun theConnectedToastNeedsAGapOfThirtySeconds() {
        assertEquals("never seen it go", false, RemotePairPolicy.reconnectToast(0L, 100_000L))
        assertEquals("a blip", false, RemotePairPolicy.reconnectToast(100_000L, 110_000L))
        assertEquals("just under", false, RemotePairPolicy.reconnectToast(100_000L, 129_999L))
        assertEquals("30 s", true, RemotePairPolicy.reconnectToast(100_000L, 130_000L))
    }

    @Test fun anInputDeviceOrARemoteNameIsARemote() {
        assertEquals(true, RemotePairPolicy.looksLikeRemote(0x0500, null))
        assertEquals(true, RemotePairPolicy.looksLikeRemote(null, "Fire TV Remote"))
        assertEquals(true, RemotePairPolicy.looksLikeRemote(null, "AR"))
        assertEquals(false, RemotePairPolicy.looksLikeRemote(0x0200, "Phone"))
        assertEquals(false, RemotePairPolicy.looksLikeRemote(null, null))
    }
}
