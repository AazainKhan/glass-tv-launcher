package dev.glasslauncher.ui

import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.ui.TileShadow.FOCUS_SCALE
import dev.glasslauncher.ui.TileShadow.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The tile shadow is two baked bitmaps (soft focus shadow, tight contact shadow) whose shapes and blur are
 * stated in "u", tvOS 27's 1080p pixels with a tile 250 u wide. These tests read the baked alpha back and
 * check it against the Gaussian edge profile, and check where [TileShadow.destRect] puts the bitmaps.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TileShadowTest {
    /** Standard normal CDF (Abramowitz and Stegun 7.1.26, error under 1.5e-7). */
    private fun phi(x: Double): Double {
        val z = abs(x) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * z)
        val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
        val erf = 1.0 - poly * exp(-z * z)
        return if (x >= 0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
    }

    /** A baked bitmap's alpha, 0..1, sampled at continuous pixel coordinates (pixel i is centred at i + 0.5). */
    private class Alpha(image: ImageBitmap) {
        val w = image.width
        val h = image.height
        private val pixels = IntArray(w * h).also { image.asAndroidBitmap().getPixels(it, 0, w, 0, 0, w, h) }
        private fun at(x: Int, y: Int) = Color.alpha(pixels[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]) / 255f
        fun sample(x: Float, y: Float): Float {
            val fx = x - 0.5f
            val fy = y - 0.5f
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val tx = fx - x0
            val ty = fy - y0
            val top = at(x0, y0) * (1 - tx) + at(x0 + 1, y0) * tx
            val bottom = at(x0, y0 + 1) * (1 - tx) + at(x0 + 1, y0 + 1) * tx
            return top * (1 - ty) + bottom * ty
        }
    }

    private fun image(kind: Kind) = when (kind) {
        Kind.Focus -> TileShadow.focus
        Kind.Contact -> TileShadow.contact
        Kind.Glow -> TileShadow.glow
    }

    /** Bitmap pixels per u. The bitmap spans the core plus a margin on every side, so this follows from its size. */
    private fun pxPerU(kind: Kind, a: Alpha) = a.w / (kind.coreW + 2 * kind.margin)

    /** Alpha d u below the core's bottom edge, on the vertical centre line. */
    private fun below(kind: Kind, a: Alpha, d: Float): Float {
        val p = pxPerU(kind, a)
        return a.sample(a.w / 2f, (kind.margin + kind.coreH + d) * p)
    }

    /** Alpha d u past the core's right edge, on the horizontal centre line. */
    private fun rightOf(kind: Kind, a: Alpha, d: Float): Float {
        val p = pxPerU(kind, a)
        return a.sample((kind.margin + kind.coreW + d) * p, a.h / 2f)
    }

    private fun assertProfile(kind: Kind, offsets: List<Float>, tolerance: Float) {
        val a = Alpha(image(kind))
        for (d in offsets) {
            val want = (1 - phi(d / kind.sigma.toDouble())).toFloat()
            assertEquals("$kind, ${d} u below the core's bottom edge", want, below(kind, a, d), tolerance)
            assertEquals("$kind, ${d} u past the core's right edge", want, rightOf(kind, a, d), tolerance)
        }
    }

    @Test fun focusShadowFollowsAGaussianOfSigma25() =
        assertProfile(Kind.Focus, listOf(-25f, 0f, 25f, 50f, 75f), 0.04f)

    @Test fun contactShadowFollowsAGaussianOfSigma2_5() =
        assertProfile(Kind.Contact, listOf(-2.5f, 0f, 2.5f, 5f, 10f), 0.05f)

    @Test fun glowFollowsAGaussianOfSigma5() =
        assertProfile(Kind.Glow, listOf(-5f, 0f, 5f, 10f, 15f), 0.05f)

    @Test fun theGlowIsBakedWhiteSoItTakesTheTilesColourWhenTinted() {
        val a = image(Kind.Glow).asAndroidBitmap()
        val centre = a.getPixel(a.width / 2, a.height / 2)
        assertEquals("centre pixel ${Integer.toHexString(centre)}", 0xFFFFFFFF.toInt(), centre)
        assertEquals("250 x 150 u core with a 20 u margin", (250f + 40f) * (150f + 40f), a.width * a.height * Kind.Glow.unitsPerPx * Kind.Glow.unitsPerPx, 1f)
    }

    @Test fun theCoreIsBakedAtFullStrength() {
        for (kind in Kind.entries) {
            val a = Alpha(image(kind))
            // Baked at full alpha: the peak strength is applied when drawing.
            assertTrue("$kind core centre ${a.sample(a.w / 2f, a.h / 2f)}", a.sample(a.w / 2f, a.h / 2f) > 0.98f)
        }
    }

    @Test fun cornersAreRoundedByTheStatedRadius() {
        // Brute-force the Gaussian over the rounded rectangle at the core's bounding-box corner and compare.
        for (kind in Kind.entries) {
            val a = Alpha(image(kind))
            val p = pxPerU(kind, a)
            val got = a.sample((kind.margin + kind.coreW) * p, (kind.margin + kind.coreH) * p)
            val want = roundedRectBlurAt(kind, kind.coreW / 2, kind.coreH / 2)
            assertEquals("$kind at the bounding-box corner (a sharp corner would give 0.25)", want, got, 0.03f)
        }
    }

    /** The shape's Gaussian-weighted coverage at (x, y) u from the core's centre, by direct summation. */
    private fun roundedRectBlurAt(kind: Kind, x: Float, y: Float): Float {
        val step = kind.sigma / 12f
        val reach = (kind.sigma * 4f / step).toInt()
        var sum = 0.0
        var total = 0.0
        for (iy in -reach..reach) for (ix in -reach..reach) {
            val dx = ix * step
            val dy = iy * step
            val w = exp(-(dx * dx + dy * dy) / (2.0 * kind.sigma * kind.sigma))
            total += w
            val px = x + dx
            val py = y + dy
            val qx = abs(px) - (kind.coreW / 2 - kind.radius)
            val qy = abs(py) - (kind.coreH / 2 - kind.radius)
            val sd = hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - kind.radius
            if (sd <= 0f) sum += w
        }
        return (sum / total).toFloat()
    }

    @Test fun theHaloIsNeverClippedAtTheBitmapBorder() {
        for (kind in Kind.entries) {
            val a = Alpha(image(kind))
            val edges = mapOf(
                "left" to a.sample(0.5f, a.h / 2f),
                "right" to a.sample(a.w - 0.5f, a.h / 2f),
                "top" to a.sample(a.w / 2f, 0.5f),
                "bottom" to a.sample(a.w / 2f, a.h - 0.5f),
            )
            for ((name, alpha) in edges) assertTrue("$kind ${name} border alpha $alpha", alpha < 0.02f)
            // And the margin is what the geometry assumes it is.
            assertEquals("$kind width", kind.coreW + 2 * kind.margin, a.w * kind.unitsPerPx, 0.01f)
            assertEquals("$kind height", kind.coreH + 2 * kind.margin, a.h * kind.unitsPerPx, 0.01f)
        }
    }

    private fun Rect.deflate(by: Float) = Rect(left + by, top + by, right - by, bottom - by)

    /** u in the rect's own px: its width over the baked bitmap's width in u. */
    private fun Rect.pxPerU(kind: Kind) = width / (kind.coreW + 2 * kind.margin)

    @Test fun theFocusShadowsCoreIsTheScaledTileAndItReachesWellBeyondTheSides() {
        val r = TileShadow.destRect(Kind.Focus, 250f, 150f, FOCUS_SCALE, lift = 1f, k = 1f)
        val core = r.deflate(Kind.Focus.margin * r.pxPerU(Kind.Focus))
        assertEquals("centred horizontally", 125f, r.center.x, 1f)
        assertEquals("core width", 250f * FOCUS_SCALE, core.width, 1f)
        assertEquals("core height", 150f * FOCUS_SCALE, core.height, 1f)
        // The regression: the old core sat 74% as wide as the tile, inside it, so only a sliver of blur showed.
        assertTrue("core ${core.width} px is inset from the tile", core.width >= 250f * FOCUS_SCALE - 1f)
        assertTrue("left ${r.left}", r.left <= -60f)
        assertTrue("right ${r.right}", r.right >= 250f + 60f)
        // Fully focused, the shadow sits 40 u lower than the tile's centre.
        assertEquals("drop", 40f, core.center.y - 75f, 1f)
    }

    @Test fun theContactShadowsCoreIsTheRestingTileAndSitsSixUnitsLower() {
        val r = TileShadow.destRect(Kind.Contact, 250f, 150f, 1f, lift = 0f, k = 1f)
        val core = r.deflate(Kind.Contact.margin * r.pxPerU(Kind.Contact))
        assertEquals("centred horizontally", 125f, r.center.x, 1f)
        assertEquals("core width", 250f, core.width, 1f)
        assertEquals("core height", 150f, core.height, 1f)
        assertEquals("drop", 6f, core.center.y - 75f, 1f)
    }

    @Test fun shadowsScaleWithTheTileAndFollowItsAnimatedScale() {
        // A 500 px wide tile is k = 2 (two px per u); halfway through its focus animation the tile is 1.1x.
        val r = TileShadow.destRect(Kind.Focus, 500f, 300f, 1.1f, lift = 0.5f, k = 2f)
        val core = r.deflate(Kind.Focus.margin * r.pxPerU(Kind.Focus))
        assertEquals("core width", 500f * 1.1f, core.width, 1f)
        assertEquals("core height", 300f * 1.1f, core.height, 1f)
        // Half the 40 u drop, in px.
        assertEquals("drop", 40f * 0.5f * 2f, core.center.y - 150f, 1f)
    }

    @Test fun theGlowsCoreIsTheScaledTileAndSitsTenUnitsLowerWhateverTheFocus() {
        for (lift in listOf(0f, 0.5f, 1f)) {
            val r = TileShadow.destRect(Kind.Glow, 500f, 300f, 1.1f, lift, k = 2f)
            val core = r.deflate(Kind.Glow.margin * r.pxPerU(Kind.Glow))
            assertEquals("core width", 500f * 1.1f, core.width, 1f)
            assertEquals("core height", 300f * 1.1f, core.height, 1f)
            assertEquals("drop at lift $lift", 10f * 2f, core.center.y - 150f, 1f)
        }
    }

    private fun glowAlpha(argb: Long) = TileShadow.glowAlpha(androidx.compose.ui.graphics.Color(argb))

    @Test fun glowStrengthFollowsTheTilesSaturationAndBrightness() {
        assertEquals("white", 0f, glowAlpha(0xFFFFFFFF), 1e-4f)
        assertEquals("black", 0f, glowAlpha(0xFF000000), 1e-4f)
        assertTrue("mid grey ${glowAlpha(0xFF808080)}", glowAlpha(0xFF808080) < 0.02f)
        assertTrue("saturated cyan ${glowAlpha(0xFF00C8C8)}", glowAlpha(0xFF00C8C8) in 0.27f..0.30f)
        // 0.3 * s 0.844 * (v 0.251 / 0.4): dark but coloured tiles still glow, less.
        assertTrue("dark navy ${glowAlpha(0xFF0A1A40)}", glowAlpha(0xFF0A1A40) > 0.15f && glowAlpha(0xFF0A1A40) < 0.30f)
        // Never above the peak, whatever the colour.
        for (c in listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFA000, 0xFF123456))
            assertTrue("$c ${glowAlpha(c)}", glowAlpha(c) in 0f..Kind.Glow.peak)
    }

    @Test fun aTileOfAnotherAspectGetsAShadowCoreEqualToItself() {
        // The 16:9 shelf cards (150 x 84 dp) next to a 5:3 tile: the core follows the tile on both axes, and the
        // halo reaches past all four sides. (It was sized from the width alone: 6% too tall under a 16:9 card.)
        for ((w, h) in listOf(150f to 84f, 250f to 150f, 120f to 100f)) for (kind in Kind.entries) {
            val k = w / 250f
            val scale = if (kind == Kind.Focus) 1.2f else 1f
            val lift = if (kind == Kind.Focus) 1f else 0f
            val r = TileShadow.destRect(kind, w, h, scale, lift, k)
            val coreW = kind.coreW * r.width / (kind.coreW + 2 * kind.margin)
            val coreH = kind.coreH * r.height / (kind.coreH + 2 * kind.margin)
            assertEquals("$kind on $w x $h: core width", w * scale, coreW, 1f)
            assertEquals("$kind on $w x $h: core height", h * scale, coreH, 1f)
            assertTrue("$kind on $w x $h: $r doesn't reach past all four sides", r.left < 0f && r.top < 0f && r.right > w && r.bottom > h)
        }
    }

    @Test fun anEmptyTileHasNoShadowAndNothingThrows() {
        // A zero-width tile makes k = 0; the old maths divided by it and gave a NaN height, which the draw block can't round.
        val cases = listOf(listOf(0f, 0f, 0f), listOf(0f, 84f, 0f), listOf(150f, 0f, 0.6f), listOf(150f, 84f, 0f), listOf(Float.NaN, 84f, 0.6f), listOf(150f, 84f, Float.POSITIVE_INFINITY))
        for ((w, h, k) in cases) for (kind in Kind.entries) {
            val r = TileShadow.destRect(kind, w, h, 1.2f, 1f, k)
            assertTrue("$kind for $w x $h at k=$k: $r", r.isEmpty && r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite())
        }
    }

    @Test fun strengthsCrossFadeAndStayWithinTheirPeaks() {
        assertEquals(0.10f, TileShadow.alpha(Kind.Contact, 0f), 1e-4f)
        assertEquals(0f, TileShadow.alpha(Kind.Focus, 0f), 1e-4f)
        assertEquals(0.35f, TileShadow.alpha(Kind.Focus, 1f), 1e-4f)
        assertEquals(0f, TileShadow.alpha(Kind.Contact, 1f), 1e-4f)
        assertEquals(0.05f, TileShadow.alpha(Kind.Contact, 0.5f), 1e-4f)
        // The focus spring overshoots 1; a negative alpha would draw garbage.
        for (lift in listOf(1.07f, 1.3f, -0.05f)) for (kind in Kind.entries) {
            val alpha = TileShadow.alpha(kind, lift)
            assertTrue("$kind at lift $lift: $alpha", alpha in 0f..kind.peak)
        }
    }
}
