package dev.glasslauncher.system

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.system.AirPlayCodePlacement.Where
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The trust path through the real registration (not the fake link), and where the pairing code shows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AirPlayTrustTest {
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val state get() = Intent(AirPlayProtocol.ACTION_STATE).putExtra("protocol", 2).putExtra("running", true)

    @Test fun theRealRegistrationRequiresTheControlPermissionForEveryEvent() {
        AirPlay.register(app) { }
        val all = shadowOf(app).registeredReceivers
        val events = AirPlayProtocol.EVENT_ACTIONS.toSet()
        // The app's own start-up hub may have registered one too: every receiver that listens for these events needs the permission.
        val listening = all.filter { r -> r.intentFilter.actionsIterator().asSequence().any { it in events } }
        assertTrue(listening.isNotEmpty())
        listening.forEach {
            assertEquals(AirPlayProtocol.PERMISSION, it.broadcastPermission)
            assertEquals(events, it.intentFilter.actionsIterator().asSequence().toSet())
            assertTrue("exported to PhairPlay, guarded by the permission", it.flags and android.content.Context.RECEIVER_EXPORTED != 0)
        }
    }

    @Test fun anEventSentWithThePermissionReachesTheRealReceiverAndDecodes() {
        // Robolectric does not enforce the sender's permission (Android does, from the registration checked above),
        // so this proves the delivery and decode path through the real receiver, not the enforcement.
        val got = mutableListOf<AirPlayProtocol.Event>()
        AirPlay.register(app) { got += it }
        app.sendBroadcast(state, AirPlayProtocol.PERMISSION)
        app.sendBroadcast(Intent("android.intent.action.VIEW"), AirPlayProtocol.PERMISSION)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, got.size)
        assertTrue(got.single() is AirPlayProtocol.Event.State)
    }

    @Test fun anotherPackageIsIgnoredWhereTheSystemNamesTheSender() {
        val got = mutableListOf<AirPlayProtocol.Event>()
        fun receiver(from: String?) = object : AirPlayEventReceiver({ got += it }) { override fun origin() = from }
        receiver("com.evil.app").onReceive(app, state)
        assertTrue(got.isEmpty())
        receiver("com.phairplay.firetv").onReceive(app, state)
        receiver("com.phairplay.googletv").onReceive(app, state)
        receiver(null).onReceive(app, state)
        assertEquals("PhairPlay's packages, and a system that can't say (before Android 14)", 3, got.size)
        assertFalse(AirPlay.trusted("com.phairplay.firetv.evil"))
    }

    @Test fun theCodeGoesWhereItCanBeSeen() {
        val d = { c: String?, front: Boolean, text: Boolean, banner: Boolean -> AirPlayCodePlacement.decide(c, front, text, banner) }
        assertEquals(Where.None, d(null, true, false, true))
        assertEquals(Where.GlassCard, d("1", true, false, false))
        assertEquals("a text field open: queued behind it, no focus stolen", Where.Queued, d("1", true, true, true))
        assertEquals("another app in front, service on: a banner", Where.Banner, d("1", false, false, true))
        assertEquals("service off: the Glass card only", Where.GlassCard, d("1", false, false, false))
        assertEquals("AirPlay code 4821", AirPlayCodePlacement.bannerText("4821"))
    }
}
