package dev.glasslauncher.shots

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.sqrt

/** One frame of a tray app-hero swap: the tray glass's and the backdrop's mean colour, and how far each has moved. */
data class HeroSwapFrame(val ms: Long, val glass: FloatArray, val backdrop: FloatArray, var glassProgress: Float = 0f, var backdropProgress: Float = 0f)

private const val STEP_MS = 16L
private const val FRAMES = 60

/** Mean colour of the window-pixel band [x0, x1] x [y0, y1] of [b], taken at [k] bitmap pixels per window pixel. */
private fun mean(b: Bitmap, k: Float, x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
    val sum = FloatArray(3); var n = 0
    for (y in (y0 * k).toInt()..(y1 * k).toInt().coerceAtLeast((y0 * k).toInt()))
        for (x in (x0 * k).toInt()..(x1 * k).toInt().coerceAtLeast((x0 * k).toInt())) {
            val c = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
            sum[0] += (c shr 16 and 0xFF); sum[1] += (c shr 8 and 0xFF); sum[2] += (c and 0xFF); n++
        }
    return FloatArray(3) { sum[it] / n }
}

private fun dist(a: FloatArray, b: FloatArray) = sqrt((0..2).sumOf { ((a[it] - b[it]) * (a[it] - b[it])).toDouble() }).toFloat()

/** Fraction of the way from [from] to [to] that [c] has travelled (projection onto the line between them). */
private fun progress(c: FloatArray, from: FloatArray, to: FloatArray): Float {
    val d = dist(from, to); if (d < 1e-3f) return 1f
    return (0..2).sumOf { ((c[it] - from[it]) * (to[it] - from[it])).toDouble() }.toFloat() / (d * d)
}

/**
 * Focus moves from one tray app to the next: the frames after the press, with the tray glass's mean colour (a band
 * inside the tray's top edge, above the icons) and the backdrop's (a band just above the tray). Both are measured
 * against the colours before the press and the colours once the swap has landed.
 */
fun ComposeTestRule.trayHeroSwap(shots: MutableList<Bitmap>? = null): List<HeroSwapFrame> {
    // Warm the hero cache (baking runs on real threads): visit the next app and come back.
    press(Button.Right); settle(); press(Button.Left); settle()
    val tray = onAllNodes(hasTestTag("tray"), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
    val full0 = onRoot().captureToImage().asAndroidBitmap()
    val k0 = full0.width / onRoot().fetchSemanticsNode().boundsInRoot.width
    fun measure(b: Bitmap): Pair<FloatArray, FloatArray> {
        val k = b.width / onRoot().fetchSemanticsNode().boundsInRoot.width
        val glass = mean(b, k, tray.left + tray.width * 0.30f, tray.top + 3f, tray.left + tray.width * 0.70f, tray.top + 9f)
        val back = mean(b, k, tray.left + tray.width * 0.30f, tray.top - 60f, tray.left + tray.width * 0.70f, tray.top - 20f)
        return glass to back
    }
    val (g0, b0) = measure(full0)
    val out = ArrayList<HeroSwapFrame>()
    val bitmaps = frames(Button.Right, FRAMES, STEP_MS, scale = 1f, onFrame = { Thread.sleep(15) })
    bitmaps.forEachIndexed { i, b ->
        val (g, bk) = measure(b)
        out += HeroSwapFrame(i * STEP_MS, g, bk)
        shots?.add(Bitmap.createScaledBitmap(b, b.width / 4, b.height / 4, true))
    }
    val g1 = out.last().glass; val b1 = out.last().backdrop
    println("tray-hero-swap: glass ${g0.toList()} -> ${g1.toList()}, backdrop ${b0.toList()} -> ${b1.toList()}")
    out.forEach { it.glassProgress = progress(it.glass, g0, g1); it.backdropProgress = progress(it.backdrop, b0, b1) }
    check(k0 > 0)
    return out
}

/** The glass-vs-background progress of each frame, as a table. */
fun List<HeroSwapFrame>.table(): String = joinToString("\n") {
    "%4d ms  glass %5.2f  backdrop %5.2f".format(it.ms, it.glassProgress, it.backdropProgress)
}

/**
 * P23: switching focus between tray apps swaps Home's backdrop to the new app's hero; the tray glass must dissolve
 * on the same clock, not change colour before the background does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class TrayHeroSwapTest {

    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    @Test fun trayGlassDissolvesWithTheBackground() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.settle()
            val f = compose.trayHeroSwap()
            println("tray-hero-swap per frame:\n" + f.table())
            val g = f.map { it.glass }; val b = f.map { it.backdrop }
            assertTrue("the backdrop barely changes between the two apps' heroes; the test would prove nothing",
                dist(b.first(), b.last()) > 6f && dist(g.first(), g.last()) > 3f)
            // The background must visibly move during the capture (else nothing was measured).
            assertTrue("backdrop never left its old colour:\n" + f.table(), f.any { it.backdropProgress in 0.1f..0.9f })
            val bad = f.filter { it.glassProgress >= 0.8f && it.backdropProgress < 0.4f }
            assertTrue("glass is at the new colour while the background is not:\n" + f.table(), bad.isEmpty())
            val off = f.filter { kotlin.math.abs(it.glassProgress - it.backdropProgress) > 0.2f }
            assertTrue("glass progress strays > 20% from the background's:\n" + f.table(), off.isEmpty())
        }
    }
}
