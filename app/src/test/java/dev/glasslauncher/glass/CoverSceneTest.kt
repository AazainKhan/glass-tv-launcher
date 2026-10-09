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
}
