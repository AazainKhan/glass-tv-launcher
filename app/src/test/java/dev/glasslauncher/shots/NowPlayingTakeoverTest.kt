package dev.glasslauncher.shots

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import dev.glasslauncher.widgets.NowPlaying
import dev.glasslauncher.widgets.NowPlayingFixture
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * P142: Home pressed with music already playing. The full-screen Top Shelf (the Cover Flow preview) must not show
 * and then be replaced by Now Playing: the target is decided before the first frame, and if it still changes
 * later, the two blend (a fade, not a cut).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class NowPlayingTakeoverTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private companion object { const val CUT_LUMA_STEP = 10f }

    @After fun reset() { NowPlayingFixture.state = null }

    private fun track(playing: Boolean) = NowPlaying(
        "Song", "Artist", "Album", "com.spotify.tv.android", cover(), durationMs = 200_000, positionMs = 30_000,
        positionAt = android.os.SystemClock.elapsedRealtime(), speed = 1f, playing = playing,
        controller = object : NowPlaying.Controls { override fun play() {}; override fun pause() {}; override fun next() {}; override fun previous() {} },
    )

    private fun cover(): Bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also { b ->
        for (y in 0 until 300) for (x in 0 until 300) b.setPixel(x, y, android.graphics.Color.rgb(200, 40 + x / 3, 120 + y / 6))
    }

    private class Frame(val ms: Long, val preview: Boolean, val hero: Boolean, val luma: Float, val diff: Float)

    private fun has(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun luma(b: Bitmap): FloatArray {
        val out = FloatArray(32 * 18)
        for (j in 0 until 18) for (i in 0 until 32) {
            val c = b.getPixel((i * b.width) / 32 + b.width / 64, (j * b.height) / 18 + b.height / 36)
            out[j * 32 + i] = 0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)
        }
        return out
    }

    /** Records from the moment [start] runs: tags present, mean luma, and the mean absolute change from the frame before. */
    private fun record(frames: Int, stepMs: Long, start: () -> Unit): List<Frame> {
        val out = ArrayList<Frame>()
        val shots = ArrayList<Bitmap>()
        compose.mainClock.autoAdvance = false
        try {
            var prev: FloatArray? = null
            start()
            repeat(frames) {
                if (it == 0) compose.mainClock.advanceTimeByFrame() else compose.mainClock.advanceTimeBy(stepMs)
                awaitBakes(); compose.waitForIdle()
                val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
                val l = luma(bmp)
                out += Frame(it * stepMs, has("cover-preview-host"), has("now-playing-hero"), l.average().toFloat(), l.indices.sumOf { i -> abs(l[i] - (prev ?: l)[i]).toDouble() }.toFloat() / l.size)
                shots += Bitmap.createScaledBitmap(bmp, bmp.width / 4, bmp.height / 4, true)
                prev = l
            }
        } finally { compose.mainClock.autoAdvance = true }
        println("strip: " + sheet("np-takeover", shots, stepMs, 8).absolutePath)
        println(out.joinToString("\n") { "t=${it.ms} preview=${it.preview} hero=${it.hero} luma=${"%.1f".format(it.luma)} diff=${"%.2f".format(it.diff)}" })
        return out
    }

    /** Home was away (stopped), music started meanwhile, Home resumes. */
    @Test fun resumeWithMusicPlayingNeverShowsTheCoverFlowFirst() {
        val flow = MutableStateFlow<NowPlaying?>(null)
        NowPlayingFixture.state = flow
        TvHarness.setUp(squareFeed = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome(); compose.settle()
            assertTrue("the Cover Flow shows at rest", has("cover-preview-host"))
            scenario.moveToState(Lifecycle.State.CREATED)
            flow.value = track(playing = true)
            val frames = record(40, 32) { scenario.moveToState(Lifecycle.State.RESUMED) }
            val cut = frames.indices.firstOrNull { frames[it].hero }
            assertTrue("Now Playing never arrives", cut != null)
            val flash = frames.take(cut!! + 1).count { it.preview && !it.hero }
            assertTrue("the Cover Flow shows for $flash frame(s) before Now Playing", flash == 0)
            assertTrue("Now Playing is not there on the first frame (first at ${cut * 32} ms)", cut == 0)
            // And the swap is a blend: no frame changes the picture by a cut's worth.
            val worst = frames.maxOf { it.diff }
            assertTrue("a frame changes the picture by $worst (mean luma step), a cut", worst < CUT_LUMA_STEP)
        }
    }
}
