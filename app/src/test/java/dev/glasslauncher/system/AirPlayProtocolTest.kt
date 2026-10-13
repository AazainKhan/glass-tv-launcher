package dev.glasslauncher.system

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.system.AirPlayProtocol.ErrorCode
import dev.glasslauncher.system.AirPlayProtocol.Event
import dev.glasslauncher.system.AirPlayProtocol.SessionKind
import dev.glasslauncher.system.AirPlayProtocol.SettingKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The wire format shared with the PhairPlay patch: encode and decode, and the old-PhairPlay fallback. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AirPlayProtocolTest {
    private val target = ComponentName("com.phairplay.firetv", "com.phairplay.service.ControlReceiver")

    private fun v2State() = Intent(AirPlayProtocol.ACTION_STATE)
        .putExtra("protocol", 2).putExtra("running", true).putExtra("sender", "Mac")
        .putExtra("display_name", "Living Room").putExtra("pin_auth", true).putExtra("high_resolution", true)
        .putExtra("mirror_audio", false).putExtra("start_on_boot", true)

    @Test fun setEncodesTheKeyAndOneTypedExtra() {
        val b = AirPlayProtocol.encodeSet(target, SettingKey.PinAuth, true)
        assertEquals(AirPlayProtocol.ACTION_SET, b.action)
        assertEquals(target, b.component)
        assertEquals("pin_auth", b.getStringExtra("key"))
        assertTrue(b.getBooleanExtra("bool", false))
        assertFalse(b.hasExtra("string"))
        val s = AirPlayProtocol.encodeSet(target, SettingKey.DisplayName, "Den")
        assertEquals("Den", s.getStringExtra("string"))
        assertFalse(s.hasExtra("bool"))
    }

    @Test fun setRefusesTheWrongTypeOrAnOverlongName() {
        assertThrowsIllegal { AirPlayProtocol.encodeSet(target, SettingKey.PinAuth, "yes") }
        assertThrowsIllegal { AirPlayProtocol.encodeSet(target, SettingKey.DisplayName, 3) }
        assertThrowsIllegal { AirPlayProtocol.encodeSet(target, SettingKey.DisplayName, "x".repeat(64)) }
    }

    @Test fun everySettingKeyHasADistinctWireName() {
        assertEquals(SettingKey.entries.size, SettingKey.entries.map { it.wire }.toSet().size)
    }

    @Test fun transportCarriesTheCommand() {
        val i = AirPlayProtocol.encodeTransport(target, AirPlayProtocol.Transport.Pause)
        assertEquals(AirPlayProtocol.ACTION_TRANSPORT, i.action)
        assertEquals("pause", i.getStringExtra("command"))
    }

    @Test fun v2StateDecodesEverySetting() {
        val e = AirPlayProtocol.decode(v2State()) as Event.State
        assertEquals(2, e.protocol)
        assertTrue(e.supportsControl && e.running)
        assertEquals("Mac", e.sender)
        val s = e.settings!!
        assertEquals("Living Room", s.displayName)
        assertTrue(s.pinAuth && s.highResolution && s.startOnBoot)
        assertFalse(s.mirrorAudio)
        assertTrue("absent keys keep PhairPlay's defaults", s.airPlayEnabled && s.miracastEnabled)
    }

    @Test fun stateWithoutProtocolIsTheOldV1() {
        val e = AirPlayProtocol.decode(Intent(AirPlayProtocol.ACTION_STATE).putExtra("running", true)) as Event.State
        assertEquals(1, e.protocol)
        assertFalse(e.supportsControl)
        assertNull(e.settings)
        assertTrue(e.running)
    }

    @Test fun unknownSettingExtrasInStateAreIgnored() {
        val e = AirPlayProtocol.decode(v2State().putExtra("future_flag", true).putExtra("another", 7)) as Event.State
        assertEquals("Living Room", e.settings!!.displayName)
    }

    @Test fun sessionDecodesKindAndTreatsUnknownKindAsUnknown() {
        val s = AirPlayProtocol.decode(
            Intent(AirPlayProtocol.ACTION_SESSION).putExtra("state", "started").putExtra("sender", "iPhone").putExtra("kind", "mirror"),
        ) as Event.Session
        assertEquals(Event.Session(true, "iPhone", SessionKind.Mirror), s)
        val odd = AirPlayProtocol.decode(Intent(AirPlayProtocol.ACTION_SESSION).putExtra("state", "ended").putExtra("kind", "hologram")) as Event.Session
        assertEquals(Event.Session(false, null, SessionKind.Unknown), odd)
    }

    @Test fun pairingCodeShowsAndDismisses() {
        assertEquals(Event.PairingCode("4821"), AirPlayProtocol.decode(Intent(AirPlayProtocol.ACTION_PAIRING_CODE).putExtra("shown", true).putExtra("code", "4821")))
        assertEquals(Event.PairingCode(null), AirPlayProtocol.decode(Intent(AirPlayProtocol.ACTION_PAIRING_CODE).putExtra("shown", false)))
    }

    @Test fun nowPlayingMapsUnknownPositionToNull() {
        val e = AirPlayProtocol.decode(
            Intent(AirPlayProtocol.ACTION_NOW_PLAYING).putExtra("active", true).putExtra("playing", true).putExtra("title", "Song")
                .putExtra("artist", "Band").putExtra("position_ms", -1L).putExtra("duration_ms", 215_000L),
        ) as Event.NowPlaying
        assertEquals("Song", e.title)
        assertNull(e.positionMs)
        assertEquals(215_000L, e.durationMs)
        assertFalse(e.hasArtwork)
    }

    @Test fun errorForAnUnknownKeyKeepsTheKey() {
        val e = AirPlayProtocol.decode(
            Intent(AirPlayProtocol.ACTION_ERROR).putExtra("action", AirPlayProtocol.ACTION_SET).putExtra("code", "unknown_key").putExtra("key", "warp_drive"),
        ) as Event.Error
        assertEquals(ErrorCode.UnknownKey, e.code)
        assertEquals("warp_drive", e.key)
        val other = AirPlayProtocol.decode(Intent(AirPlayProtocol.ACTION_ERROR).putExtra("code", "meltdown")) as Event.Error
        assertEquals(ErrorCode.Other, other.code)
    }

    @Test fun otherIntentsAreNotEvents() {
        assertNull(AirPlayProtocol.decode(Intent("android.intent.action.VIEW")))
        assertNull(AirPlayProtocol.decode(Intent()))
    }

    @Test fun v1StateStillDrivesTheToggleButBlocksSettingsAndTransport() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val old = AirPlay.stateOf(AirPlayProtocol.decode(Intent(AirPlayProtocol.ACTION_STATE).putExtra("running", true))!!)!!
        assertTrue(old.on)
        assertFalse(AirPlay.setSetting(ctx, target, old, SettingKey.PinAuth, true))
        assertFalse(AirPlay.transport(ctx, target, old, AirPlayProtocol.Transport.Play))
        val new = AirPlay.stateOf(AirPlayProtocol.decode(v2State())!!)!!
        assertTrue(AirPlay.setSetting(ctx, target, new, SettingKey.PinAuth, false))
        assertNull(AirPlay.stateOf(Event.PairingCode(null)))
    }

    private fun assertThrowsIllegal(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        throw AssertionError("expected IllegalArgumentException")
    }
}
