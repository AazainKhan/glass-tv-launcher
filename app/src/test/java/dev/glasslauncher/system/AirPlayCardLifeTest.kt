package dev.glasslauncher.system

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.system.AirPlayProtocol.Event
import dev.glasslauncher.system.AirPlayProtocol.SessionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AirPlayCardLifeTest {
    @Test fun cardExpiresAfterTheTtlIfNoCloseArrives() {
        val c = AirPlayCardLife()
        c.onEvent(Event.PairingCode("1234", 1_000), now = 100)
        assertTrue(c.visible(1_099))
        assertEquals(1L, c.remaining(1_099))
        assertFalse(c.visible(1_100))
        assertNull(c.remaining(1_100))
    }

    @Test fun closeEventDismissesAtOnce() {
        val c = AirPlayCardLife()
        c.onEvent(Event.PairingCode("1234", 1_000), 0)
        c.onEvent(Event.PairingCode(null), 10)
        assertFalse(c.visible(10))
    }

    @Test fun anIdleStateClearsAStaleCard() {
        val c = AirPlayCardLife()
        c.onEvent(Event.PairingCode("1234", 1_000), 0)
        c.onState(Event.State(running = true, sender = null, protocol = 2, settings = null))
        assertTrue(c.visible(5))
        c.onState(Event.State(running = false, sender = null, protocol = 2, settings = null))
        assertFalse(c.visible(5))
    }

    @Test fun ttlComesFromTheEventAndFallsBackWhenMissingOrBad() {
        val d = AirPlayProtocol.decode(Intent(AirPlayProtocol.ACTION_PAIRING_CODE).putExtra("code", "9").putExtra("ttl_ms", 5_000L)) as Event.PairingCode
        assertEquals(5_000L, d.ttlMs)
        val m = AirPlayProtocol.decode(Intent(AirPlayProtocol.ACTION_PAIRING_CODE).putExtra("code", "9")) as Event.PairingCode
        assertEquals(AirPlayProtocol.DEFAULT_PAIRING_TTL_MS, m.ttlMs)
    }

    @Test fun aSecondStartedReplacesTheSessionAndOnlyItsOwnEndClearsIt() {
        val l = AirPlaySessionLife()
        l.onEvent(Event.Session(true, "Mac", SessionKind.Unknown))
        l.onEvent(Event.Session(true, "Mac", SessionKind.Mirror))
        assertEquals(SessionKind.Mirror, l.current!!.kind)
        l.onEvent(Event.Session(false, "Mac", SessionKind.Photo))
        assertEquals("a photo ending leaves the mirror", SessionKind.Mirror, l.current!!.kind)
        l.onEvent(Event.Session(false, "Mac", SessionKind.Mirror))
        assertNull(l.current)
    }

    @Test fun nameLimitFollowsWhatPhairPlayReports() {
        val s = (AirPlayProtocol.decode(Intent(AirPlayProtocol.ACTION_STATE).putExtra("protocol", 2).putExtra("display_name_max", 40)) as Event.State).settings!!
        assertEquals(40, s.displayNameMax)
        val t = android.content.ComponentName("p", "c")
        AirPlayProtocol.encodeSet(t, AirPlayProtocol.SettingKey.DisplayName, "x".repeat(40), s.displayNameMax)
        try { AirPlayProtocol.encodeSet(t, AirPlayProtocol.SettingKey.DisplayName, "x".repeat(41), s.displayNameMax); throw AssertionError() } catch (_: IllegalArgumentException) {}
    }

    @Test fun aStoppedReceiverClearsTheSession() {
        val l = AirPlaySessionLife()
        l.onEvent(Event.Session(true, "Mac", SessionKind.Mirror))
        l.onState(Event.State(running = true, sender = null, protocol = 2, settings = null))
        assertEquals(SessionKind.Mirror, l.current!!.kind)
        l.onState(Event.State(running = false, sender = null, protocol = 2, settings = null))
        assertNull(l.current)
    }
}
