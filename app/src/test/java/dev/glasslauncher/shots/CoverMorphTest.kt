package dev.glasslauncher.shots

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * P86: Up from a square-cover shelf's resting preview into the full Cover Flow is ONE morph: the preview's covers
 * grow and move into the full-screen pose, the same centre cover stays centred throughout, and the full flow's covers
 * take over exactly where they are (same pose), so there is no frame with two carousels. Down is the reverse.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CoverMorphTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private fun bounds(tag: String): Rect? =
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun run(button: Button, frames: Int): List<Pair<Rect?, Rect?>> {
        val out = ArrayList<Pair<Rect?, Rect?>>(frames)
        compose.mainClock.autoAdvance = false
        try {
            compose.onRoot().performKeyInput { pressKey(button.key) }
            repeat(frames) {
                if (it == 0) compose.mainClock.advanceTimeByFrame() else compose.mainClock.advanceTimeBy(16)
                out += bounds("preview-cover:4") to bounds("cover:4")
            }
        } finally { compose.mainClock.autoAdvance = true }
        return out
    }

    @Test fun upIsOneMorphWithTheCentreCoverStayingCentredAndDownReversesIt() {
        TvHarness.setUp(squareFeed = true)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.settle()
            // Let the shelf's covers show at rest (the preview), then press Up.
            val rest = bounds("preview-cover:4") ?: error("no preview at rest")
            val up = run(Button.Up, frames = 40)
            compose.settle()
            val full = bounds("cover:4") ?: error("no full-screen cover after Up")
            val rootCentre = compose.onRoot().fetchSemanticsNode().boundsInRoot.center.x
            // The same centre cover stays horizontally centred at every frame of the morph (the preview's, until it hands over).
            for ((i, f) in up.withIndex()) {
                val r = f.first ?: continue
                assertEquals("frame $i: the preview's centre cover stays centred", rootCentre, r.center.x, 3f)
            }
            // It grows monotonically toward the full pose (never shrinks, never jumps back).
            val widths = up.mapNotNull { it.first?.width }
            assertTrue("grows: $widths", widths.zipWithNext().all { (a, b) -> b >= a - 1f })
            assertTrue("starts at the resting size (${rest.width}) and ends at the full one (${full.width})", widths.first() < full.width * 0.8f)
            // At the end the full flow's centre cover is where the preview's was: same size and place, within a few px.
            val last = up.last().first
            if (last != null) {
                assertEquals("same width at the hand-over", full.width, last.width, 4f)
                assertEquals("same top at the hand-over", full.top, last.top, 4f)
            }
            // Down reverses: the preview's centre cover shrinks back to its resting rect.
            val down = run(Button.Down, frames = 40)
            compose.settle()
            val back = bounds("preview-cover:4") ?: error("no preview after Down")
            assertEquals("back at the resting size", rest.width, back.width, 3f)
            assertEquals("back at the resting place", rest.center.y, back.center.y, 3f)
            assertTrue("Down never jumps: ${down.mapNotNull { it.first?.width }}",
                down.mapNotNull { it.first?.width }.zipWithNext().all { (a, b) -> b <= a + 1f })
        }
    }
}

/** P86: focusing a known cover app goes straight to its soft scene and Cover Flow, never the app's flat hero first. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CoverArrivalTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    @Test fun aKnownCoverAppSkipsTheFlatHero() {
        TvHarness.setUp(squareFeed = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            compose.settle()
            // The app focus lands on first is the one we mark as a known cover app (as Spotify would be), then move
            // off it and back so the arrival is a fresh focus.
            val pkg = compose.focused()!!.removePrefix("app:")
            assertTrue("a dock app has focus: $pkg", compose.focused()!!.startsWith("app:"))
            val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            ctx.getSharedPreferences("home", 0).edit().putStringSet("coverApps", setOf(pkg)).commit()
            scenario.recreate()
            compose.waitForHome()
            var heroSeen = false
            var previewAt = -1
            compose.mainClock.autoAdvance = false
            try {
                for (i in 0 until 90) {   // ~1.4 s of 16 ms frames from the first frame
                    compose.mainClock.advanceTimeBy(16)
                    if (compose.onAllNodes(androidx.compose.ui.test.hasTestTag("top-shelf-app-hero:$pkg")).fetchSemanticsNodes().isNotEmpty()) heroSeen = true
                    if (previewAt < 0 && compose.onAllNodes(androidx.compose.ui.test.hasTestTag("cover-preview-host")).fetchSemanticsNodes().isNotEmpty()) previewAt = i
                }
            } finally { compose.mainClock.autoAdvance = true }
            assertTrue("the app's flat hero stage is skipped for a known cover app", !heroSeen)
            assertTrue("the Cover Flow preview is there well before the old 1.5 s dwell (frame $previewAt)", previewAt in 0..60)
        }
    }
}
