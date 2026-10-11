package dev.glasslauncher.glass

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A Cover Flow background (P33): the cover fills the screen's shape, glossy at the top, darker at the bottom. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CoverSceneTest {
    private fun lum(c: Int) = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3f
    private fun rowMean(b: Bitmap, y: Int) = (0 until b.width step 8).map { lum(b.getPixel(it, y)) }.average().toFloat()

    /** A square cover in three bands: red on top, mid grey in the middle, blue at the bottom. */
    private fun cover(): Bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until 640) for (x in 0 until 640) setPixel(x, y, when {
            y < 213 -> Color.rgb(200, 30, 30)
            y < 427 -> Color.rgb(128, 128, 128)
            else -> Color.rgb(30, 30, 200)
        })
    }

    @Test fun fillsTheScreenFromTheCoversMiddle() {
        val scene = coverScene(cover())
        assertEquals(CoverLook.W, scene.width); assertEquals(CoverLook.H, scene.height)
        // 16:9 from a square keeps its middle 56%: the grey band fills the centre, and the edges are the bands
        // above and below it, never letterboxed.
        val mid = scene.getPixel(scene.width / 2, scene.height / 2)
        assertEquals(128f, lum(mid), 12f)
        assertTrue("top edge shows the red band", Color.red(scene.getPixel(scene.width / 2, 2)) > Color.blue(scene.getPixel(scene.width / 2, 2)))
        assertTrue("bottom edge shows the blue band", Color.blue(scene.getPixel(scene.width / 2, scene.height - 3)) > Color.red(scene.getPixel(scene.width / 2, scene.height - 3)))
    }

    @Test fun glossyTopAndDarkerBottom() {
        // A flat grey cover: what the sheen and the shade add on their own.
        val grey = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(100, 100, 100)) }
        val scene = coverScene(grey)
        val top = rowMean(scene, 1); val middle = rowMean(scene, scene.height / 3); val bottom = rowMean(scene, scene.height - 2)
        assertTrue("sheen lifts the top ($top vs $middle)", top > middle + 10f)
        assertEquals("the middle is the cover itself", 100f, middle, 4f)
        assertTrue("shade darkens the bottom ($bottom)", bottom < 100f * (1f - CoverLook.SHADE) + 8f)
        assertTrue("never black", bottom > 30f)
    }

    @Test fun leavesTheCoverAlone() {
        val c = cover()
        coverScene(c)
        assertTrue(!c.isRecycled)
        assertEquals(Color.rgb(200, 30, 30), c.getPixel(10, 10))
    }

    // P79: a cover is too small to fill the screen sharp; the pure rule that says so.
    @Test fun smallArtNeedsTheSoftBackdrop() {
        assertTrue("a 640 cover on 1080p is 3x", needsSoftBackdrop(640, 640))
        assertTrue("a 1280x720 picture is 1.5x", needsSoftBackdrop(1280, 720))
        assertTrue("a 1500 px wide picture is 1.28x", needsSoftBackdrop(1500, 844))
        assertTrue("a 1920 px wide picture is 1.0x", !needsSoftBackdrop(1920, 1080))
        assertTrue("a 1600 px wide picture is 1.2x", !needsSoftBackdrop(1600, 900))
        // Only the part that shows counts: a tall portrait is cropped to its width.
        assertTrue("a 1920x2880 poster crops to 1920 wide: sharp", !needsSoftBackdrop(1920, 2880))
        assertTrue("nothing known: leave it", !needsSoftBackdrop(0, 0))
    }

    /** A busy, colourful 640 px "playlist cover": a grid of saturated tiles with a bright diagonal. */
    private fun busyCover(): Bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply {
        val palette = intArrayOf(Color.rgb(220, 60, 60), Color.rgb(240, 180, 40), Color.rgb(50, 160, 90), Color.rgb(60, 90, 210), Color.rgb(170, 70, 190))
        for (y in 0 until 640) for (x in 0 until 640) {
            val c = palette[((x / 64) + (y / 64) * 2) % palette.size]
            setPixel(x, y, if (kotlin.math.abs(x - y) < 18) Color.WHITE else c)
        }
    }

    /** How sharp a scene's edges are: the largest step between neighbouring pixels along its middle row. */
    private fun sharpest(b: Bitmap): Int {
        val y = b.height / 2
        return (1 until b.width).maxOf { x ->
            val a = b.getPixel(x - 1, y); val c = b.getPixel(x, y)
            maxOf(kotlin.math.abs(Color.red(a) - Color.red(c)), kotlin.math.abs(Color.green(a) - Color.green(c)), kotlin.math.abs(Color.blue(a) - Color.blue(c)))
        }
    }

    @Test fun theBackdropIsASoftColourFieldAndKeepsItsColour() {
        // Robolectric's RenderScript blurs nothing: measure the CPU path (the same sigma on the stick).
        Blur.forceCpu = true
        val before: Bitmap; val after: Bitmap
        try {
            before = coverScene(busyCover(), blurRadius = 3, saturation = 1f)
            after = coverScene(busyCover())
        } finally { Blur.forceCpu = false }
        java.io.File("build/p79").mkdirs()
        listOf("before" to before, "after" to after).forEach { (n, b) -> java.io.File("build/p79/cover-backdrop-$n.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        println("P79 sharpest step before=${sharpest(before)} after=${sharpest(after)}")
        assertTrue("edges are far softer: ${sharpest(before)} -> ${sharpest(after)}", sharpest(after) < sharpest(before) / 2)
        // Still colour-rich: not averaged to grey (the channel spread of the centre pixel).
        val px = after.getPixel(after.width / 2, after.height / 2)
        val spread = maxOf(Color.red(px), Color.green(px), Color.blue(px)) - minOf(Color.red(px), Color.green(px), Color.blue(px))
        assertTrue("colour survives the blur (spread $spread)", spread > 25)
    }
}
