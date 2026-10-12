package dev.glasslauncher.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.glass.Blur
import dev.glasslauncher.glass.ImageGate.Level
import dev.glasslauncher.shots.TV
import dev.glasslauncher.shots.shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P81: a logo hero is never enlarged past 1.25x its own pixels; icon-sized art is no logo at all. A golden per level. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class LogoHeroGateTest {
    /** A brand on its colour: a bold white "N"-ish block mark with a bar, [w]x[h], so ink and edge colour are clear. */
    private fun logoArt(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        c.drawColor(Color.rgb(24, 70, 160))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val u = w / 10f
        c.drawRect(3.0f * u, 0.30f * h, 3.9f * u, 0.70f * h, p)
        c.drawRect(6.1f * u, 0.30f * h, 7.0f * u, 0.70f * h, p)
        c.drawRect(3.0f * u, 0.30f * h, 7.0f * u, 0.44f * h, p)
    }

    @Test fun aRasterLogoOnA1080pHeroIsNeverEnlarged() {
        // P108: Nuvio's banner is a 320x180 PNG whose wordmark is ~260 px wide. Laid out on a 1920x1080 hero at its
        // own pixels (nativeFactor 1, limit 1): drawn no larger than it is, so the screen shows it 1:1.
        val plan = LogoHero.plan(inkW = 260, inkH = 70, w = 1920, h = 1080, nativeFactor = 1f, limit = 1f)
        assertTrue("scale ${plan.scale} must not exceed 1", plan.scale <= 1f)
        assertEquals(Level.Soft, plan.level)
        // The old 1280x720 hero drew it at 1.25x its pixels, and the screen then stretched the hero 1.5x more.
        val old = LogoHero.plan(inkW = 780, inkH = 210, w = 1280, h = 720, nativeFactor = 3f)
        assertTrue("old on-screen enlargement ${old.scale * 3 * 1.5f} was over 1.8x", old.scale * 3 * 1.5f > 1.8f)
    }

    @Test fun planIsPure() {
        // Native 1280 px art, logo ink 400 px, drawn at 1:1 to 666 px: 1.67x wanted from native: soft... unless native is big.
        assertEquals(Level.Sharp, LogoHero.plan(inkW = 700, inkH = 300, w = 1280, h = 720, nativeFactor = 1f).level)
        assertEquals(Level.Soft, LogoHero.plan(inkW = 300, inkH = 150, w = 1280, h = 720, nativeFactor = 1f).level)
        assertEquals(Level.Plain, LogoHero.plan(inkW = 60, inkH = 60, w = 1280, h = 720, nativeFactor = 1f).level)
        assertEquals("vectors scale cleanly", Level.Sharp, LogoHero.plan(inkW = 100, inkH = 50, w = 1280, h = 720, nativeFactor = null).level)
        // A 320 px banner enlarged to 960 (factor 3): the logo may be drawn at 1.25/3 of the art pixels.
        val p = LogoHero.plan(inkW = 480, inkH = 200, w = 1280, h = 720, nativeFactor = 3f)
        assertEquals(Level.Soft, p.level)
        assertEquals(1.25f / 3f, p.scale, 0.001f)
    }

    private fun composeCpu(art: Bitmap, factor: Float?): Bitmap {
        Blur.forceCpu = true
        try { return LogoHero.compose(art, 1280, 720, factor) } finally { Blur.forceCpu = false }
    }

    @Test fun sharpLevelDrawsTheLogoAtItsWantedSize() {
        composeCpu(logoArt(1280, 720), 1f).captureRoboImage(shot("gate-hero-sharp"))
    }

    @Test fun softLevelKeepsTheLogoSharpAndSmallerOnAColourField() {
        // A 320x180 raster banner enlarged into a 960 px bitmap (factor 3).
        val out = composeCpu(logoArt(960, 540), 3f)
        out.captureRoboImage(shot("gate-hero-soft"))
        // The logo's ink is narrower than the 52% a sharp hero would give it.
        val inkCols = (0 until out.width).filter { x -> (0 until out.height step 4).any { y -> Color.red(out.getPixel(x, y)) > 235 && Color.green(out.getPixel(x, y)) > 235 } }
        assertTrue("ink spans ${inkCols.size} px", inkCols.isNotEmpty() && inkCols.last() - inkCols.first() < 1280 * 0.40)
    }

    @Test fun plainLevelKeepsTheLogoAtMost1p25xItsNativePixelsOnAGradient() {
        // 64 px art with 1:1 pixels: the logo's ink is ~26 px, icon-sized.
        val plan = LogoHero.plan(inkW = 30, inkH = 30, w = 1280, h = 720, nativeFactor = 1f)
        assertEquals(Level.Plain, plan.level)
        assertEquals("never enlarged past the limit", 1.25f, plan.scale, 0.001f)
        val out = composeCpu(logoArt(64, 64), 1f)
        out.captureRoboImage(shot("gate-hero-plain"))
        // The mark is there (the app keeps its identity), small, and centred on the gradient.
        val cols = (0 until out.width).filter { x -> (0 until out.height).any { y -> Color.red(out.getPixel(x, y)) > 200 && Color.green(out.getPixel(x, y)) > 200 } }
        assertTrue("logo drawn", cols.isNotEmpty())
        assertTrue("small: ${cols.size} px of ink columns", cols.last() - cols.first() < 64)
        assertEquals("centred", 640f, (cols.first() + cols.last()) / 2f, 4f)
    }
}
