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

    @Test fun theScreenDoesNotMatterButAConnectedRemoteStopsEverything() {
        assertEquals("screen off still pairs: a stick with no remote idles to sleep", Action.Bond, RemotePairPolicy.decide(ad, state.copy(screenOn = false)))
        assertEquals(Action.Ignore, RemotePairPolicy.decide(ad, state.copy(remoteConnected = true)))
        assertTrue(RemotePairPolicy.shouldScan(remoteConnected = false))
        assertFalse(RemotePairPolicy.shouldScan(remoteConnected = true))
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

    // The Fire remote's ADV_IND, byte for byte from the btsnoop (public address 18:0B:1B:51:D1:B8, name "AR"):
    // flags 0x05, HID 0x1812, appearance 0x0180, complete name, manufacturer 0x0171 (product 0x0427).
    private val remoteAdvert = "0201050303121803198001030941520aff71010427027d000000".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun theRemotesAdvertParsesAndIsDiscoverableThroughTheLimitedFlag() {
        val p = AdvParser.parse(remoteAdvert)
        assertEquals(0x05, p.flags)
        assertEquals(setOf(0x1812), p.uuids16)
        assertEquals(setOf(0x0171), p.companyIds)
        assertEquals(0x0180, p.appearance)
        assertEquals(26, remoteAdvert.size)
        assertEquals("limited discoverable counts", true, AdvParser.discoverable(p.flags))
        assertEquals("general discoverable counts", true, AdvParser.discoverable(0x06))
        assertEquals("BR/EDR-not-supported alone is not discoverable", false, AdvParser.discoverable(0x04))
        assertEquals(false, AdvParser.discoverable(null))
    }

    @Test fun aTruncatedAdvertParsesWhatItCanAndNeverThrows() {
        assertEquals(0x05, AdvParser.parse(remoteAdvert.copyOf(5)).flags)
        assertEquals(0x0180, AdvParser.parse(remoteAdvert.copyOf(11)).appearance)
        assertEquals(null, AdvParser.parse(byteArrayOf(0x09, 0x19, 0x00)).appearance)
        assertEquals(null, AdvParser.parse(ByteArray(0)).flags)
    }

    @Test fun theRemoteInLimitedModeIsBondedAndOtherHidDevicesAreNot() {
        val p = AdvParser.parse(remoteAdvert)
        val remote = Advert("AA:BB:CC:DD:EE:01", amazon = true, hid = true, discoverable = AdvParser.discoverable(p.flags), rssi = -50, appearance = p.appearance)
        assertEquals(Action.Bond, RemotePairPolicy.decide(remote, state))
        assertEquals("a keyboard's appearance", Action.Ignore, RemotePairPolicy.decide(remote.copy(appearance = 0x03C1), state))
        assertEquals("no appearance advertised still bonds on Amazon + HID", Action.Bond, RemotePairPolicy.decide(remote.copy(appearance = null), state))
    }

    @Test fun silentBondedRemoteGetsTheBatteryHintOncePerTenMinutes() {
        fun hint(bonded: Boolean = true, connected: Boolean = false, screen: Boolean = true, home: Boolean = true, scanning: Long = 100_000, silent: Long = 100_000, since: Long = 1_000_000) =
            RemotePairPolicy.batteryHint(bonded, connected, screen, home, scanning, silent, since)
        assertEquals(true, hint())
        assertEquals("not bonded: nothing to say about batteries", false, hint(bonded = false))
        assertEquals("connected", false, hint(connected = true))
        assertEquals("screen off", false, hint(screen = false))
        assertEquals("a movie is in front: the remote sleeping is normal", false, hint(home = false))
        assertEquals("just started scanning", false, hint(scanning = 30_000))
        assertEquals("it advertised a moment ago", false, hint(silent = 10_000))
        assertEquals("said it a minute ago", false, hint(since = 60_000))
    }
}

class RemoteStatusTest {
    @Test fun signalWordsNotNumbers() {
        assertEquals("Strong", RemoteStatus.signal(-50))
        assertEquals("Good", RemoteStatus.signal(-70))
        assertEquals("Weak", RemoteStatus.signal(-90))
        assertEquals(null, RemoteStatus.signal(null))
        assertEquals("a stored 0 is unknown", null, RemoteStatus.signal(0))
    }

    @Test fun agoIsCoarseAndNeverNegative() {
        val now = 1_000_000_000L
        assertEquals(null, RemoteStatus.ago(0, now))
        assertEquals("just now", RemoteStatus.ago(now - 20_000, now))
        assertEquals("3 min ago", RemoteStatus.ago(now - 190_000, now))
        assertEquals("2 h ago", RemoteStatus.ago(now - 2 * 3_600_000L - 5_000, now))
        assertEquals("4 d ago", RemoteStatus.ago(now - 4 * 86_400_000L, now))
        assertEquals("a clock that went back", "just now", RemoteStatus.ago(now + 5_000, now))
    }

    @Test fun theLineSaysWhatIsKnown() {
        val now = 10_000_000L
        assertEquals("Connected · Signal Strong · Battery 80%", RemoteStatus.line(true, -50, now, 80, now))
        assertEquals("Connected", RemoteStatus.line(true, null, 0, -1, now))
        assertEquals("Last seen 3 min ago · Signal Good", RemoteStatus.line(false, -70, now - 190_000, -1, now))
        assertEquals("Not seen yet", RemoteStatus.line(false, null, 0, -1, now))
        assertEquals("a battery of 101 is not a percent", "Not seen yet", RemoteStatus.line(false, null, 0, 101, now))
    }
}
