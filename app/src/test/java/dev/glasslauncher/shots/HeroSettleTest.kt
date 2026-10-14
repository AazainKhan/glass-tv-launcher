package dev.glasslauncher.shots

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.featured.HeroSettle
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P158 dock regression: a press (the dock route's autoplay step or key) recomposes Home once for the focus change and nothing more:
 * the settled-hero machinery writes no state outside a hold. A hold recomposes once more, when the repeats rest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class HeroSettleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aPressRecomposesOncePerFocusChangeAndAHoldOncePlusTheSettle() {
        var focus by mutableIntStateOf(0)
        var held by androidx.compose.runtime.mutableStateOf(false)
        var recompositions = 0
        var shownNow = -1
        compose.setContent {
            recompositions++
            val settle = remember { HeroSettle(0) }
            shownNow = settle.shown(held, focus)
            if (held) LaunchedEffect(focus) { delay(50); settle.settle(focus) }
        }
        compose.waitForIdle()
        val start = recompositions
        repeat(5) { compose.runOnIdle { focus++ }; compose.waitForIdle() }
        assertEquals("five presses, five recompositions, none extra", start + 5, recompositions)
        assertEquals(5, shownNow)

        // A hold: 4 quick focus changes with the key held show the settled index, then one settle recomposition.
        compose.runOnIdle { held = true }; compose.waitForIdle()
        val heldStart = recompositions
        repeat(4) { compose.runOnIdle { focus++ }; compose.waitForIdle() }
        assertEquals("during the hold the hero stays on the last settled item", 5, shownNow)
        compose.runOnIdle { held = false }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals("after the repeats rest it follows the focus", 9, shownNow)
        val during = recompositions - heldStart
        org.junit.Assert.assertTrue("4 focus changes, the toggle back and the settle (they can share a frame), never two per tick: $during", during in 5..6)
    }

    @Test fun outsideAHoldNothingIsWrittenOrRead() {
        val s = HeroSettle(0)
        assertEquals(3, s.shown(held = false, focus = 3))
        assertEquals(0, s.tick)
        assertEquals("a hold keeps the last shown", 3, s.shown(held = true, focus = 7))
        s.settle(7)
        assertEquals(1, s.tick)
        assertEquals(7, s.shown(held = true, focus = 9))
    }
}
