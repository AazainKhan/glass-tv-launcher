package dev.glasslauncher.featured

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.shots.TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A card's title scrolls to show all of it while the card is focused and the title doesn't fit; otherwise it never moves. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CardTitleTest {
    @get:Rule val compose = createComposeRule()

    private var focused by mutableStateOf(false)

    private fun show(title: String, reduceMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(dev.glasslauncher.ui.LocalUiPrefs provides dev.glasslauncher.ui.LocalUiPrefs.current.copy(reduceMotion = reduceMotion)) {
                Box(Modifier.background(Color.Black).width(150.dp)) { CardTitle(title, focused = focused, modifier = Modifier.width(150.dp).testTag("title")) }
            }
        }
        compose.mainClock.advanceTimeBy(100)
    }

    private fun shot(): Bitmap = compose.onNodeWithTag("title").captureToImage().asAndroidBitmap()

    private fun same(a: Bitmap, b: Bitmap): Boolean {
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) return false
        return true
    }

    @Test fun aLongTitleScrollsOnlyWhileFocused() {
        show("The Extraordinarily Long Title of a Very Popular Series: Season Three")
        val resting = shot()
        compose.mainClock.advanceTimeBy(3_000)
        assertTrue("an unfocused title must not move", same(resting, shot()))

        focused = true
        // The capture lets the focused layout settle (the ellipsis gives way to the unclipped line); then the 1 s opening pause.
        shot()
        compose.mainClock.advanceTimeBy(200)
        val start = shot()
        compose.mainClock.advanceTimeBy(600)
        assertTrue("the marquee must wait about a second before it starts", same(start, shot()))
        compose.mainClock.advanceTimeBy(900)
        assertFalse("a focused, truncated title should be scrolling", same(start, shot()))

        focused = false
        compose.mainClock.advanceTimeBy(200)
        val back = shot()
        compose.mainClock.advanceTimeBy(3_000)
        assertTrue("it stops when focus leaves", same(back, shot()))
        assertTrue("and goes back to how it rested (ellipsis)", same(resting, back))
    }

    @Test fun aTitleThatFitsNeverMovesAndLooksTheSameFocused() {
        show("Short")
        val resting = shot()
        focused = true
        compose.mainClock.advanceTimeBy(300)
        val start = shot()
        compose.mainClock.advanceTimeBy(5_000)
        assertTrue("a title that fits stays put", same(start, shot()))
        assertTrue("and is drawn exactly as unfocused (centred, not shifted)", same(resting, start))
    }

    @Test fun reduceMotionKeepsTheEllipsis() {
        show("The Extraordinarily Long Title of a Very Popular Series: Season Three", reduceMotion = true)
        val resting = shot()
        focused = true
        compose.mainClock.advanceTimeBy(5_000)
        assertTrue("with Reduce Motion a focused title stays as it rested", same(resting, shot()))
    }

    @Test fun aLongTitleScrollsTwiceThenRestsWithItsEllipsis() {
        show("The Extraordinarily Long Title of a Very Popular Series: Season Three")
        val resting = shot()
        focused = true
        shot() // lets the focused layout settle, as above
        compose.mainClock.advanceTimeBy(200)
        val start = shot()
        compose.mainClock.advanceTimeBy(2_300)
        assertFalse("a focused long title scrolls", same(start, shot()))
        // Two passes of a few seconds each: long over by a minute, and then Home must be idle with the ellipsis back.
        compose.mainClock.advanceTimeBy(60_000)
        assertTrue("after its passes it rests as it was, with its ellipsis", same(resting, shot()))
    }

    @Test fun thePassesTimeCoversThePausesAndBothPasses() {
        // 300 px title, 64 px gap, 72 px/s: two passes of ~5.06 s, two 1 s pauses, the margin.
        assertEquals(12_411.0, titleMarqueeMillis(300f, 64f, 72f).toDouble(), 2.0)
    }
}
