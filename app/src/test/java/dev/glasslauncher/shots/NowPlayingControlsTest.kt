package dev.glasslauncher.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Palette
import dev.glasslauncher.widgets.Like
import dev.glasslauncher.widgets.NowPlaying
import dev.glasslauncher.widgets.NowPlayingCard
import dev.glasslauncher.widgets.NowPlayingClock
import dev.glasslauncher.widgets.NowPlayingHero
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P145: Now Playing's seek scrubber and like heart, in Control Center's card and on Home's hero: the four combinations
 * (seek + heart, heart only, seek only, neither) in dark and light, the focused scrubber, and the key handling.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
@OptIn(ExperimentalTestApi::class)
class NowPlayingControlsTest {
    @get:Rule val compose = createComposeRule()

    @After fun reset() { NowPlayingClock.now = { android.os.SystemClock.elapsedRealtime() } }

    private class Spy : NowPlaying.Controls {
        val seeks = ArrayList<Long>()
        val likes = ArrayList<String>()
        override fun play() {}; override fun pause() {}; override fun next() {}; override fun previous() {}
        override fun seekTo(positionMs: Long) { seeks += positionMs }
        override fun setHeart(liked: Boolean) { likes += "heart $liked" }
    }

    private fun item(spy: Spy, canSeek: Boolean, like: Like, key: String = "Song") = NowPlaying(
        key, "Artist", "Album", "com.spotify.tv.android", null, durationMs = 214_000, positionMs = 62_000,
        positionAt = android.os.SystemClock.elapsedRealtime(), speed = 1f, playing = false, controller = spy, canSeek = canSeek, like = like,
    )

    private val variants = listOf(
        "both" to (true to Like.Heart(true)),
        "heart" to (false to Like.Heart(false)),
        "seek" to (true to Like.None),
        "neither" to (false to Like.None),
    )

    private var shown by mutableStateOf<NowPlaying?>(null)
    private var lightTheme by mutableStateOf(false)

    private fun card(item: NowPlaying, light: Boolean) {
        shown = item; lightTheme = light
        if (contentSet) return
        contentSet = true
        compose.setContent {
            CompositionLocalProvider(LocalPalette provides Palette(light = lightTheme)) {
                Box(Modifier.size(420.dp, 140.dp).background(if (lightTheme) Color(0xFFD8DCE4) else Color(0xFF30333C)).padding(16.dp).testTag("shot")) {
                    shown?.let { NowPlayingCard(it, 388.dp, 1f) }
                }
            }
        }
    }
    private var contentSet = false

    @Test fun cardVariants() {
        // Dark only: with no Control Center sheet in a bare test, the card's glass is always dark, so a light palette would be unreadable.
        for (light in listOf(false)) for ((name, v) in variants) {
            card(item(Spy(), v.first, v.second), light)
            compose.waitForIdle()
            compose.onNodeWithTag("shot").captureRoboImage(shot("np-controls-card-$name-${if (light) "light" else "dark"}"))
        }
    }

    @Test fun cardScrubberFocused() {
        card(item(Spy(), true, Like.Heart(true)), light = false)
        compose.onNodeWithTag("np-scrubber").requestFocus()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("shot").captureRoboImage(shot("np-controls-card-scrubber-focused"))
    }

    private var heroLight by mutableStateOf(false)
    private fun hero(item: NowPlaying, onLight: Boolean = false) {
        shown = item; heroLight = onLight
        if (contentSet) return
        contentSet = true
        compose.setContent {
            Box(Modifier.size(780.dp, 250.dp).background(if (heroLight) Color(0xFFE6D9C4) else Color(0xFF2E2230)).padding(24.dp).testTag("shot")) {
                shown?.let { NowPlayingHero(it, heroLight, remember { FocusRequester() }, remember { FocusRequester() }) }
            }
        }
    }

    @Test fun heroVariants() {
        for ((name, v) in variants) {
            hero(item(Spy(), v.first, v.second))
            compose.waitForIdle()
            compose.onNodeWithTag("shot").captureRoboImage(shot("np-controls-hero-$name"))
        }
        hero(item(Spy(), true, Like.Heart(true)), onLight = true)
        compose.waitForIdle()
        compose.onNodeWithTag("shot").captureRoboImage(shot("np-controls-hero-both-onlight"))
    }

    @Test fun heroScrubberFocused() {
        hero(item(Spy(), true, Like.Heart(false)))
        compose.onNodeWithTag("np-scrubber").requestFocus()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("shot").captureRoboImage(shot("np-controls-hero-scrubber-focused"))
    }

    // --- behaviour ---
    @Test fun leftAndRightSeekTenSecondsAndAHoldIsCoalesced() {
        var t = 1_000L
        NowPlayingClock.now = { t }
        val spy = Spy()
        card(item(spy, true, Like.None), light = false)
        compose.onNodeWithTag("np-scrubber").requestFocus()
        compose.onNodeWithTag("np-scrubber").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        assertEquals(listOf(72_000L), spy.seeks) // 62 s paused, +10 s, sent at once
        // A held key: 30 Hz repeats for a second. Few seeks reach the app, the last target is not lost.
        repeat(30) {
            t += 33
            compose.onNodeWithTag("np-scrubber").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        }
        compose.mainClock.advanceTimeBy(400); t += 400
        compose.waitForIdle()
        assertTrue("seeks ${spy.seeks}", spy.seeks.size in 3..8)
        assertEquals("clamped to the end", 214_000L, spy.seeks.last())
        compose.onNodeWithTag("np-scrubber").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionLeft) }
    }

    @Test fun noScrubberWithoutSeekSupportAndNoHeartWithoutLike() {
        card(item(Spy(), false, Like.None), light = false)
        compose.onAllNodesWithTagCount("np-scrubber", 0)
    }

    @Test fun focusMovesToPlayWhenTheScrubberDisappears() {
        var state by mutableStateOf(item(Spy(), true, Like.Heart(false)))
        compose.setContent {
            CompositionLocalProvider(LocalPalette provides Palette(light = false)) { NowPlayingCard(state, 388.dp, 1f) }
        }
        compose.onNodeWithTag("np-scrubber").requestFocus()
        compose.onNodeWithTag("np-scrubber").assertIsFocused()
        state = item(Spy(), false, Like.Heart(false))
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.isFocused()).assertExists()
    }

    /** P147: the cover arriving later fades in over the placeholder: no cut, never see-through, the art alone at the end. */
    @Test fun coverFadesInOverThePlaceholder() {
        val spy = Spy()
        val red = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.rgb(255, 0, 0)) }
        shown = item(spy, false, Like.None)
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.size(780.dp, 250.dp).background(Color(0xFF2E2230)).padding(24.dp).testTag("shot")) { shown?.let { NowPlayingHero(it, false, remember { FocusRequester() }, remember { FocusRequester() }) } } }
        compose.mainClock.advanceTimeBy(100)
        fun redAtCover(): Int {
            val b = compose.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
            return android.graphics.Color.red(b.getPixel(30, 30)) // inside the 200 dp art slot, off the note glyph
        }
        val before = redAtCover()
        shown = item(spy, false, Like.None).copy(art = red)
        val reds = ArrayList<Int>()
        repeat(24) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle(); reds += redAtCover() }
        assertTrue("monotonic (never see-through, never snaps back): $reds", reds.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("a blend, not a cut: $reds", reds.count { it > before + 8 && it < 247 } >= 3)
        assertEquals("art alone at the end: $reds", 255, reds.last())
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String, n: Int) =
        assertEquals(n, onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size)
}
