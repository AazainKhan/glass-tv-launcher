package dev.glasslauncher.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tray stays on screen at every text size (it ran off the bottom at 1.3× and 1.4×). */
class MetricsTest {
    private val w = 960.dp
    private val h = 540.dp

    @Test fun trayFitsOnScreenAtEveryTextSize() {
        for (scale in listOf(1f, 1.15f, 1.3f, 1.4f)) {
            val m = Metrics(scale)
            val bottom = m.trayTop(w, h) + m.trayHeight(w)
            assertTrue("at ${scale}x the tray ends at $bottom on a $h screen", bottom <= h - 16.dp)
        }
    }

    @Test fun defaultSizeKeepsTvosPlacement() {
        assertEquals(384f, Metrics(1f).trayTop(w, h).value, 0.5f)
    }

    // tvOS 27's tile radius is 30 px on a 250 px tile = 15 dp, and it grows with Text Size like the tile.
    @Test fun tileRadiusIs15dpTimesTextScale() {
        for (scale in listOf(1f, 1.3f)) assertEquals(15f * scale, Metrics(scale).tileRadius.value, 0.001f)
    }
}
