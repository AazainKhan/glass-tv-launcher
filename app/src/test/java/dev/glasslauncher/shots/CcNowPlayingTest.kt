package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.MainActivity
import dev.glasslauncher.widgets.NowPlaying
import dev.glasslauncher.widgets.NowPlayingFixture
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P136: Control Center with a Now Playing session: the card takes its own place under the grid and covers nothing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcNowPlayingTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    @After fun reset() {
        NowPlayingFixture.state = null
        dev.glasslauncher.sonos.AudioOutputs.volumeReader = { host -> dev.glasslauncher.sonos.SonosControl("http://$host:1400").getVolume() }
        hubField().set(null, null)
    }

    private fun hubField() = dev.glasslauncher.system.AirPlay::class.java.getDeclaredField("hubInstance").apply { isAccessible = true }

    private class Link : dev.glasslauncher.system.AirPlayLink {
        override fun target() = android.content.ComponentName("com.phairplay.firetv", "com.phairplay.service.ControlReceiver")
        override fun send(intent: android.content.Intent) = true
        override fun listen(onEvent: (dev.glasslauncher.system.AirPlayProtocol.Event) -> Unit, onPackages: () -> Unit) {}
    }

    private val track = NowPlaying(
        "Spider-Man", "Sony Pictures", null, "airplay", null, durationMs = 8_158_000, positionMs = 542_000,
        positionAt = android.os.SystemClock.elapsedRealtime(), speed = 0f, playing = false,
        controller = object : NowPlaying.Controls { override fun play() {}; override fun pause() {}; override fun next() {}; override fun previous() {} },
    )

    @Test fun cardUnderTheGrid() {
        NowPlayingFixture.state = MutableStateFlow(track)
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            // The card sits under the grid's last row, inside the panel, and covers no tile.
            val card = compose.onNodeWithTag("now-playing-card").fetchSemanticsNode().boundsInRoot
            val last = compose.onNodeWithTag("cc-tile:restart").fetchSemanticsNode().boundsInRoot
            val panel = compose.onNodeWithTag("control-center").fetchSemanticsNode().boundsInRoot
            check(card.top >= last.bottom) { "card ${card.top} overlaps the grid (${last.bottom})" }
            check(card.bottom <= panel.bottom) { "card ${card.bottom} outside the panel (${panel.bottom})" }
            compose.onRoot().captureRoboImage(shot("cc-now-playing"))
        }
    }

    /** Now Playing under the grid, then the AirPlay platter pushed from its pill: no Back row, the picker's rows, the volume row. */
    @Test fun platterWithTheCardOnTheGrid() {
        NowPlayingFixture.state = MutableStateFlow(track)
        // A PhairPlay that answers: the AirPlay pill shows, as on the stick.
        hubField().set(null, dev.glasslauncher.system.AirPlayHub(Link(), background = { it.run() }, main = { it.run() }))
        dev.glasslauncher.sonos.AudioOutputs.volumeReader = { 35 }
        TvHarness.setUp(config = { it.copy(audioOutput = dev.glasslauncher.data.AudioOutputConfig("10.0.0.5", "Kitchen")) })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            compose.press(Button.Down, Button.Down, Button.Right)
            check(compose.focused()?.startsWith("cc-tile:airplay") == true) { "focus ${compose.focused()}" }
            compose.press(Button.Select)
            compose.waitForTag("cc-airplay")
            compose.settle()
            compose.onRoot().captureRoboImage(shot("cc-airplay-platter"))
            // Back pops the platter into its pill; a second Back would close Control Center.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.settle()
            check(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("cc-airplay")).fetchSemanticsNodes().isEmpty()) { "platter still up after Back" }
        }
    }

    /** P141: audio routed to a Sonos speaker; the AirPlay control wears the speaker glyph (blue) and, as a pill, the room name. */
    @Test fun airPlayControlWhileRoutedToASpeaker() {
        hubField().set(null, dev.glasslauncher.system.AirPlayHub(Link(), background = { it.run() }, main = { it.run() }))
        TvHarness.setUp(config = { it.copy(audioOutput = dev.glasslauncher.data.AudioOutputConfig("10.0.0.5", "Roam 2")) })
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val runner = dev.glasslauncher.sonos.SonosRoutes::class.java.getDeclaredField("runner").apply { isAccessible = true }
        dev.glasslauncher.sonos.SonosRoutes.state(ctx) // creates the runner
        @Suppress("UNCHECKED_CAST")
        val flow = runner.get(dev.glasslauncher.sonos.SonosRoutes).let { r ->
            r.javaClass.getDeclaredField("_state").apply { isAccessible = true }.get(r) as MutableStateFlow<dev.glasslauncher.sonos.RouteState>
        }
        flow.value = dev.glasslauncher.sonos.RouteState.Playing
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForHome()
                compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
                compose.waitForTag("control-center")
                compose.settle()
                compose.onRoot().captureRoboImage(shot("cc-airplay-routed"))
            }
        } finally { flow.value = dev.glasslauncher.sonos.RouteState.Idle }
    }

    /** P148: the unified picker: This TV, Headphones (a connected pair), Speakers (a Bluetooth speaker), "Pair a device…" last. */
    @Test fun platterWithBluetoothDevices() {
        NowPlayingFixture.state = MutableStateFlow(track)
        hubField().set(null, dev.glasslauncher.system.AirPlayHub(Link(), background = { it.run() }, main = { it.run() }))
        dev.glasslauncher.system.BtOutputs.reader = {
            listOf(
                dev.glasslauncher.sonos.BtOutput("AA:01", "Pixel Buds", dev.glasslauncher.sonos.BtRole.Headphones, connected = true, battery = 72),
                dev.glasslauncher.sonos.BtOutput("AA:02", "JBL Flip", dev.glasslauncher.sonos.BtRole.Speaker, connected = false, battery = null),
            )
        }
        TvHarness.setUp()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForHome()
                compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
                compose.waitForTag("control-center")
                compose.settle()
                compose.press(Button.Down, Button.Down, Button.Right)
                check(compose.focused()?.startsWith("cc-tile:airplay") == true) { "focus ${compose.focused()}" }
                compose.press(Button.Select)
                compose.waitForTag("cc-airplay")
                compose.settle()
                for (tag in listOf("cc-audio-bt:AA:01", "cc-audio-bt:AA:02", "cc-audio-pair")) compose.onNodeWithTag(tag, useUnmergedTree = true).assertExists()
                compose.onRoot().captureRoboImage(shot("cc-output-picker"))
            }
        } finally { dev.glasslauncher.system.BtOutputs.reader = { emptyList() } }
    }

    /** P148: network speakers multi-select: two checked at once, each with its own volume row. */
    @Test fun platterWithTwoNetworkSpeakers() {
        NowPlayingFixture.state = MutableStateFlow(track)
        hubField().set(null, dev.glasslauncher.system.AirPlayHub(Link(), background = { it.run() }, main = { it.run() }))
        dev.glasslauncher.sonos.AudioOutputs.volumeReader = { 35 }
        TvHarness.setUp(config = { it.copy(audioOutputs = listOf(dev.glasslauncher.data.AudioOutputConfig("10.0.0.5", "Kitchen"), dev.glasslauncher.data.AudioOutputConfig("10.0.0.6", "Den"))) })
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            compose.press(Button.Down, Button.Down, Button.Right)
            check(compose.focused()?.startsWith("cc-tile:airplay") == true) { "focus ${compose.focused()}" }
            compose.press(Button.Select)
            compose.waitForTag("cc-airplay")
            compose.settle()
            check(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("cc-audio-volume")).fetchSemanticsNodes().size == 2) { "one volume row per checked speaker" }
            compose.onRoot().captureRoboImage(shot("cc-output-picker-two-speakers"))
        }
    }
}
