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
}
