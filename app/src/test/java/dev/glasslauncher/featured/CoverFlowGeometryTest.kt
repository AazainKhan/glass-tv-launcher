package dev.glasslauncher.featured

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CoverFlowGeometryTest {
    @Test fun theCentreCoverIsFlatFullSizeAndOnTop() {
        val c = CoverFlowGeometry.pose(0f)
        assertEquals(0f, c.x, 0f); assertEquals(0f, c.rotationY, 0f); assertEquals(1f, c.scale, 0f); assertEquals(1f, c.alpha, 0f)
        assertTrue(c.z > CoverFlowGeometry.pose(1f).z)
    }

    @Test fun neighboursAreTurnedAboutSixtyTwoDegreesSmallerAndMirrored() {
        val right = CoverFlowGeometry.pose(1f)
        val left = CoverFlowGeometry.pose(-1f)
        assertEquals(62f, abs(right.rotationY), 0.01f)
        assertEquals(0.8f, right.scale, 0.001f)
        assertEquals(right.x, -left.x, 1e-6f)
        assertEquals(right.rotationY, -left.rotationY, 1e-6f)
        // Each side leans its face to the centre: opposite signs either side.
        assertTrue(right.rotationY * left.rotationY < 0f)
    }

    @Test fun furtherCoversStackCloserAndNearerOnesAreOnTop() {
        var previous = CoverFlowGeometry.pose(1f)
        for (i in 2..4) {
            val p = CoverFlowGeometry.pose(i.toFloat())
            assertEquals("steps are even", CoverFlowGeometry.STEP, p.x - previous.x, 1e-5f)
            assertTrue("each further cover sits under the nearer one", p.z < previous.z)
            assertEquals(CoverFlowGeometry.SIDE_SCALE, p.scale, 1e-5f)
            previous = p
        }
    }

    @Test fun onlyFourNeighboursAreDrawnAndTheNextOneFadesOut() {
        assertEquals(1f, CoverFlowGeometry.pose(4f).alpha, 0f)
        assertEquals(0f, CoverFlowGeometry.pose(5f).alpha, 0f)
        assertTrue(CoverFlowGeometry.pose(4.5f).alpha in 0.1f..0.9f)
        assertTrue(!CoverFlowGeometry.visible(5.2f) && CoverFlowGeometry.visible(4.9f))
    }

    @Test fun everyValueIsContinuousSoNoCoverEverJumps() {
        // A tiny move of the position moves each value by a tiny amount, across the whole range.
        var d = -5.5f
        while (d < 5.5f) {
            val a = CoverFlowGeometry.pose(d)
            val b = CoverFlowGeometry.pose(d + 0.01f)
            assertTrue("x at $d", abs(a.x - b.x) < 0.01f)
            assertTrue("rotation at $d", abs(a.rotationY - b.rotationY) < 2f)
            assertTrue("scale at $d", abs(a.scale - b.scale) < 0.01f)
            assertTrue("alpha at $d", abs(a.alpha - b.alpha) < 0.02f)
            d += 0.01f
        }
    }

    @Test fun theBakedReflectionStacksAFadingMirrorUnderTheCover() {
        val cover = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also { b ->
            for (y in 0 until 100) for (x in 0 until 100) b.setPixel(x, y, if (y < 50) Color.RED else Color.BLUE)
        }
        val baked = CoverReflection.bake(cover)
        assertEquals(100, baked.width)
        assertEquals(130, baked.height)
        // The picture is unchanged on top.
        assertEquals(Color.RED, baked.getPixel(10, 10)); assertEquals(Color.BLUE, baked.getPixel(10, 90))
        // The mirror is the cover's bottom (blue) first, fading to nothing.
        val near = baked.getPixel(10, 102)
        val far = baked.getPixel(10, 128)
        assertTrue("near the cover it is the mirrored blue: $near", Color.blue(near) > Color.red(near) && Color.alpha(near) > 40)
        assertTrue("and it fades out: ${Color.alpha(near)} -> ${Color.alpha(far)}", Color.alpha(far) < Color.alpha(near) / 2)
    }

    @Test fun aNonSquareImageIsCroppedToACentredSquare() {
        val wide = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888).also { b ->
            for (y in 0 until 100) for (x in 0 until 300) b.setPixel(x, y, if (x in 100 until 200) Color.GREEN else Color.RED)
        }
        val square = CoverReflection.squared(wide)
        assertEquals(100, square.width); assertEquals(100, square.height)
        assertEquals(Color.GREEN, square.getPixel(50, 50))
        assertEquals(Color.GREEN, square.getPixel(2, 2))
    }
}
