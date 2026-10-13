package dev.glasslauncher.widgets

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.system.AirPlayTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AirPlayNowPlayingTest {
    @Test fun unknownsRenderAsNoProgressNoArtAndASenderTitle() {
        val np = airPlayNowPlaying(AirPlayTrack(null, null, null, true, null, null), "iPhone", now = 5) { }
        assertEquals("AirPlay from iPhone", np.title)
        assertEquals(0, np.durationMs)
        assertNull(np.art)
    }

    @Test fun playPauseCallsBackWithWhatToDoAndSkipDoesNothing() {
        val log = mutableListOf<Boolean>()
        val playing = airPlayNowPlaying(AirPlayTrack("Song", "Band", null, true, 1_000, 60_000), null, 0) { log += it }
        playing.playPause()
        val paused = airPlayNowPlaying(AirPlayTrack("Song", "Band", null, false, 1_000, 60_000), null, 0) { log += it }
        paused.playPause()
        playing.next(); playing.previous()
        assertEquals(listOf(false, true), log)
        assertEquals("Song", playing.title); assertEquals(60_000, playing.durationMs)
    }
}
