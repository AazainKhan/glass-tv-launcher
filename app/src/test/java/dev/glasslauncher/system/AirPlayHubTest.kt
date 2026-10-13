package dev.glasslauncher.system

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.system.AirPlayPageModel.Kind
import dev.glasslauncher.system.AirPlayProtocol.ACTION_ENABLE
import dev.glasslauncher.system.AirPlayProtocol.ACTION_NOW_PLAYING
import dev.glasslauncher.system.AirPlayProtocol.ACTION_PAIRING_CODE
import dev.glasslauncher.system.AirPlayProtocol.ACTION_QUERY
import dev.glasslauncher.system.AirPlayProtocol.ACTION_SESSION
import dev.glasslauncher.system.AirPlayProtocol.ACTION_SET
import dev.glasslauncher.system.AirPlayProtocol.ACTION_STATE
import dev.glasslauncher.system.AirPlayProtocol.ACTION_TRANSPORT
import dev.glasslauncher.system.AirPlayProtocol.Event
import dev.glasslauncher.system.AirPlayProtocol.SettingKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A fake PhairPlay: the test hands the hub broadcasts exactly as PhairPlay would, and reads what the hub sent back. */
private class FakeLink : AirPlayLink {
    var target: ComponentName? = ComponentName("com.phairplay.firetv", "com.phairplay.service.ControlReceiver")
    val sent = mutableListOf<Intent>()
    private var listener: ((Event) -> Unit)? = null
    override fun target() = target
    override fun send(intent: Intent): Boolean { sent += intent; return true }
    override fun listen(onEvent: (Event) -> Unit) { listener = onEvent }
    fun deliver(intent: Intent) { AirPlayProtocol.decode(intent)?.let { listener!!(it) } }
    fun actions() = sent.map { it.action }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AirPlayHubTest {
    private var now = 1_000L
    private val link = FakeLink()
    private val hub = AirPlayHub(link) { now }

    private fun state(protocol: Int? = 2, running: Boolean = true, name: String = "Den", pin: Boolean = false, high: Boolean = false, audio: Boolean = true, sender: String? = null) =
        Intent(ACTION_STATE).putExtra("running", running).putExtra("sender", sender).apply {
            if (protocol != null) putExtra("protocol", protocol)
            if (protocol != null && protocol >= 2) {
                putExtra("display_name", name).putExtra("pin_auth", pin).putExtra("high_resolution", high).putExtra("mirror_audio", audio)
                putExtra("display_name_max", 20)
            }
        }

    @Test fun withoutPhairPlayThePageSaysNotSetUpWithTheHint() {
        link.target = null
        hub.start()
        assertEquals(AirPlayAvailability.Absent, hub.ui.value.availability)
        assertEquals(Kind.NotSetUp, AirPlayPageModel.of(hub.ui.value).kind)
        assertTrue(AirPlayPageModel.HINT_NOT_SET_UP.contains("scripts/phairplay install"))
        assertTrue("nothing to send to", link.sent.isEmpty())
        assertFalse(hub.setSetting(SettingKey.PinAuth, true))
    }

    @Test fun startAsksForStateAndWaitsForTheAnswer() {
        hub.start()
        assertEquals(listOf(ACTION_QUERY), link.actions())
        assertEquals(AirPlayAvailability.Waiting, hub.ui.value.availability)
        assertEquals(Kind.Waiting, AirPlayPageModel.of(hub.ui.value).kind)
    }

    @Test fun everyResumeAsksAgainAndNoticesAnInstall() {
        link.target = null
        hub.start()
        link.target = ComponentName("com.phairplay.firetv", "x")
        hub.resume()
        assertEquals(AirPlayAvailability.Waiting, hub.ui.value.availability)
        hub.resume()
        assertEquals(listOf(ACTION_QUERY, ACTION_QUERY), link.actions())
    }

    @Test fun v2StateFillsEveryRowFromTheBroadcast() {
        hub.start()
        link.deliver(state(pin = true, high = true, audio = false, sender = "Mac"))
        val p = AirPlayPageModel.of(hub.ui.value)
        assertEquals(Kind.Full, p.kind)
        assertTrue(p.on && p.requireCode && p.highQuality)
        assertFalse(p.mirrorAudio)
        assertEquals("Den", p.name)
        assertEquals(20, p.nameMax)
        assertEquals("Mac", p.sender)
        assertEquals("High 1440p", AirPlayPageModel.qualityValue(p.highQuality))
        link.deliver(state(pin = false, high = false, name = ""))
        assertEquals("a new STATE replaces every value", "TV Name", AirPlayPageModel.nameValue(AirPlayPageModel.of(hub.ui.value).name))
    }

    @Test fun v1StateGivesOnlyTheToggle() {
        hub.start()
        link.deliver(state(protocol = null, running = true))
        val p = AirPlayPageModel.of(hub.ui.value)
        assertEquals(Kind.ToggleOnly, p.kind)
        assertTrue(p.on)
        assertFalse("no SET to an old PhairPlay", hub.setSetting(SettingKey.PinAuth, true))
        hub.setOn(false)
        assertEquals(listOf(ACTION_QUERY, AirPlayProtocol.ACTION_DISABLE), link.actions())
    }

    @Test fun rowsSendSetWithTheRightKeyAndType() {
        hub.start()
        link.deliver(state())
        assertTrue(hub.setSetting(SettingKey.HighResolution, true))
        assertTrue(hub.setSetting(SettingKey.DisplayName, "Lounge"))
        hub.setOn(true)
        val (q, a, b, c) = link.sent
        assertEquals(ACTION_QUERY, q.action)
        assertEquals(ACTION_SET, a.action); assertEquals("high_resolution", a.getStringExtra("key")); assertTrue(a.getBooleanExtra("bool", false))
        assertEquals("Lounge", b.getStringExtra("string"))
        assertEquals(ACTION_ENABLE, c.action)
        assertEquals("the page keeps no copy: nothing changed until STATE says so", false, AirPlayPageModel.of(hub.ui.value).highQuality)
    }

    @Test fun aNameOverTheReportedLimitIsNotSent() {
        hub.start()
        link.deliver(state())
        assertFalse(hub.setSetting(SettingKey.DisplayName, "x".repeat(21)))
        assertEquals(listOf(ACTION_QUERY), link.actions())
    }

    @Test fun anErrorEventIsCounted() {
        hub.start()
        link.deliver(Intent(AirPlayProtocol.ACTION_ERROR).putExtra("code", "unknown_key").putExtra("key", "x"))
        assertEquals(1, hub.ui.value.errors)
    }

    @Test fun pairingCodeShowsDismissesAndExpires() {
        hub.start()
        link.deliver(state())
        link.deliver(Intent(ACTION_PAIRING_CODE).putExtra("shown", true).putExtra("code", "4821").putExtra("ttl_ms", 5_000L))
        assertEquals("4821", hub.ui.value.pairingCode)
        now += 4_999
        hub.tick()
        assertEquals("4821", hub.ui.value.pairingCode)
        now += 2
        hub.tick()
        assertNull("the TTL ends it with no close event", hub.ui.value.pairingCode)

        link.deliver(Intent(ACTION_PAIRING_CODE).putExtra("shown", true).putExtra("code", "1111"))
        link.deliver(Intent(ACTION_PAIRING_CODE).putExtra("shown", false))
        assertNull("the close event ends it", hub.ui.value.pairingCode)
    }

    @Test fun backOnAnOldCardDoesNotDismissANewerCode() {
        hub.start()
        link.deliver(Intent(ACTION_PAIRING_CODE).putExtra("code", "1111"))
        link.deliver(Intent(ACTION_PAIRING_CODE).putExtra("code", "2222"))
        hub.dismissPairing("1111")
        assertEquals("2222", hub.ui.value.pairingCode)
        hub.dismissPairing("2222")
        assertNull(hub.ui.value.pairingCode)
    }

    @Test fun aStoppedReceiverDropsTheCodeTheSessionAndTheTrack() {
        hub.start()
        link.deliver(state())
        link.deliver(Intent(ACTION_PAIRING_CODE).putExtra("code", "1234"))
        link.deliver(Intent(ACTION_SESSION).putExtra("state", "started").putExtra("kind", "audio"))
        link.deliver(Intent(ACTION_NOW_PLAYING).putExtra("active", true).putExtra("playing", true).putExtra("title", "Song"))
        assertTrue(hub.ui.value.audioPlaying)
        link.deliver(state(running = false))
        assertNull(hub.ui.value.pairingCode)
        assertNull(hub.ui.value.sessionKind)
        assertNull(hub.ui.value.track)
    }

    @Test fun sessionEndClearsTheTrack() {
        hub.start()
        link.deliver(state())
        link.deliver(Intent(ACTION_SESSION).putExtra("state", "started").putExtra("kind", "audio").putExtra("sender", "iPhone"))
        link.deliver(Intent(ACTION_NOW_PLAYING).putExtra("active", true).putExtra("playing", true).putExtra("title", "Song"))
        link.deliver(Intent(ACTION_SESSION).putExtra("state", "ended").putExtra("kind", "audio"))
        assertNull(hub.ui.value.track)
    }

    @Test fun nowPlayingTakesTitleAndArtistAndLeavesUnknownsNull() {
        hub.start()
        link.deliver(state())
        link.deliver(
            Intent(ACTION_NOW_PLAYING).putExtra("active", true).putExtra("playing", true).putExtra("title", "Song").putExtra("artist", "Band")
                .putExtra("position_ms", -1L).putExtra("duration_ms", -1L).putExtra("has_artwork", true),
        )
        val t = hub.ui.value.track!!
        assertEquals("Song", t.title); assertEquals("Band", t.artist)
        assertNull(t.positionMs); assertNull(t.durationMs)
    }

    @Test fun playPauseSendsTransportAndFlipsTheIconUntilTheTrackChanges() {
        hub.start()
        link.deliver(state())
        link.deliver(Intent(ACTION_NOW_PLAYING).putExtra("active", true).putExtra("playing", true).putExtra("title", "A"))
        hub.playPause()
        assertEquals("pause", link.sent.last().getStringExtra("command"))
        assertEquals(ACTION_TRANSPORT, link.sent.last().action)
        assertFalse(hub.ui.value.track!!.playing)
        hub.playPause()
        assertEquals("play", link.sent.last().getStringExtra("command"))
        hub.playPause()
        link.deliver(Intent(ACTION_NOW_PLAYING).putExtra("active", true).putExtra("playing", true).putExtra("title", "B"))
        assertTrue("a new track is playing", hub.ui.value.track!!.playing)
    }

    @Test fun playPauseIsNothingToAnOldPhairPlay() {
        hub.start()
        link.deliver(state(protocol = null))
        link.deliver(Intent(ACTION_NOW_PLAYING).putExtra("active", true).putExtra("title", "A"))
        hub.playPause()
        assertEquals(listOf(ACTION_QUERY), link.actions())
    }

    @Test fun untrustedTextIsCleanedBeforeItIsDrawn() {
        hub.start()
        link.deliver(state(sender = "Eve‮gnp.exe​\u0007  Mac", name = "A\u0000B\n C"))
        val ui = hub.ui.value
        assertEquals("Evegnp.exe Mac", ui.sender)
        assertEquals("AB C", ui.settings!!.displayName)
        link.deliver(Intent(ACTION_PAIRING_CODE).putExtra("code", "12‮34 <b>5"))
        assertEquals("12345", hub.ui.value.pairingCode)
    }

    @Test fun cleaningCapsLengthAndDropsEmpties() {
        assertEquals(5, AirPlayText.clean("x".repeat(50), 5)!!.length)
        assertNull(AirPlayText.clean("​ ‮", 10))
        assertNull(AirPlayText.clean(null, 10))
        assertEquals("a b", AirPlayText.clean("  a \t\n b  ", 10))
        assertEquals("😀😀", AirPlayText.clean("😀😀😀", 2))
        assertNull(AirPlayText.code("abc"))
        assertEquals("12345678", AirPlayText.code("1234567890"))
    }
}
