package dev.glasslauncher.shots

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import dev.glasslauncher.data.BackgroundMode
import dev.glasslauncher.data.Wallpaper
import dev.glasslauncher.data.WallpaperKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.pow

/**
 * P24: over light art the status pill is milky glass that separates from the art by its edge (a 1 dp rim and a
 * soft shadow), not by a darker fill; over dark art it is unchanged. Replaces the P18 "fill is darker than the
 * art" rule (that rule had no test of its own, only the status-pill-backgrounds strip).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class StatusPillLightTest {

    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private class Scene(val full: Bitmap, val pill: Rect, val clock: Rect, val gear: Rect, val density: Float)

    private fun capture(wp: Wallpaper): Scene {
        TvHarness.setUp(config = { it.copy(background = BackgroundMode.Wallpaper, wallpaperDark = wp, wallpaperLight = wp) })
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome(); compose.settle()
            val full = compose.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
            fun bounds(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
            val d = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
            return Scene(full, bounds("status-pill"), bounds("clock"), bounds("status-gear"), d)
        }
    }

    private fun lum(c: Int): Float {
        fun f(v: Int): Float { val s = v / 255f; return if (s <= 0.03928f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f) }
        return 0.2126f * f(android.graphics.Color.red(c)) + 0.7152f * f(android.graphics.Color.green(c)) + 0.0722f * f(android.graphics.Color.blue(c))
    }
    private fun grey(c: Int) = 0.299f * android.graphics.Color.red(c) + 0.587f * android.graphics.Color.green(c) + 0.114f * android.graphics.Color.blue(c)

    private fun mean(b: Bitmap, l: Int, t: Int, r: Int, bt: Int, skip: List<Rect> = emptyList()): Float {
        var sum = 0f; var n = 0
        for (y in t.coerceAtLeast(0) until bt.coerceAtMost(b.height)) for (x in l.coerceAtLeast(0) until r.coerceAtMost(b.width)) {
            if (skip.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom }) continue
            sum += grey(b.getPixel(x, y)); n++
        }
        check(n > 0) { "empty region $l,$t,$r,$bt" }
        return sum / n
    }

    private class Measure(val body: Float, val bottom: Float, val middle: Float, val gearDisc: Float, val edge: Float, val artBeside: Float, val contrast: Float)

    private fun measure(s: Scene): Measure {
        val d = s.density
        val p = s.pill
        val h = p.height
        // Straight part of the capsule only (the round ends mix in the art), clear of the clock text and the gear.
        val l = (p.left + h / 2).toInt(); val r = (p.right - h / 2).toInt()
        val skip = listOf(Rect(s.clock.left - 2, p.top, s.clock.right + 2, p.bottom), Rect(s.gear.left - 2, p.top, p.right, p.bottom))
        val inset = d.toInt() + 1
        val top = p.top.toInt() + inset; val bot = p.bottom.toInt() - inset
        val span = (bot - top).toFloat()
        val bottomBand = mean(s.full, l, (bot - span * 0.2f).toInt(), r, bot, skip)
        val middleBand = mean(s.full, l, (top + span * 0.4f).toInt(), r, (top + span * 0.6f).toInt(), skip)
        val body = mean(s.full, l, top, r, bot, skip)
        // The gear disc: a ring of the gear's box away from its glyph (the disc's corners are outside the circle, so use a mid ring).
        val gc = s.gear
        val gx = gc.center.x.toInt(); val gy = gc.center.y.toInt(); val rad = gc.width / 2
        var gs = 0f; var gn = 0
        for (y in gc.top.toInt()..gc.bottom.toInt()) for (x in gc.left.toInt()..gc.right.toInt()) {
            val dist = kotlin.math.hypot((x - gx).toFloat(), (y - gy).toFloat())
            if (dist > rad * 0.78f && dist < rad * 0.95f) { gs += grey(s.full.getPixel(x, y)); gn++ }
        }
        val disc = gs / gn
        // The edge: from the rim (1 dp inside) to 5 dp outside the bottom edge, against the art 12-18 dp below.
        val edge = mean(s.full, l, (p.bottom - d).toInt(), r, (p.bottom + 5 * d).toInt())
        val art = mean(s.full, l, (p.bottom + 12 * d).toInt(), r, (p.bottom + 18 * d).toInt())
        // Text: its colour is palette.primary in light (0xFF0E1015) on the body's colour.
        val bodyLum = lum(android.graphics.Color.rgb(body.toInt(), body.toInt(), body.toInt()))
        val textLum = lum(0xFF0E1015.toInt())
        val contrast = (bodyLum + 0.05f) / (textLum + 0.05f)
        return Measure(body, bottomBand, middleBand, disc, edge, art, contrast)
    }

    private fun solid(name: String, argb: Int): Wallpaper {
        val f = java.io.File.createTempFile(name, ".png")
        val b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888); b.eraseColor(argb)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Wallpaper(WallpaperKind.File, f.absolutePath)
    }

    private fun lightScenes() = listOf(
        "white" to solid("white", android.graphics.Color.WHITE),
        "light preset" to Wallpaper(WallpaperKind.Preset, "dawn"),
    )

    @Test fun lightArtGetsMilkyGlassWithAnEdge() {
        val problems = mutableListOf<String>()
        for ((name, wp) in lightScenes()) {
            val s = capture(wp)
            val m = measure(s)
            println("pill[$name] body=${m.body} bottom=${m.bottom} middle=${m.middle} disc=${m.gearDisc} edge=${m.edge} art=${m.artBeside} contrast=${m.contrast}")
            if (m.body < 225f) problems += "$name: body mean ${m.body} < 225"
            if (m.middle - m.bottom > 6f) problems += "$name: bottom band ${m.bottom} is ${m.middle - m.bottom} darker than the middle ${m.middle} (max 6)"
            if (m.body - m.gearDisc > 10f) problems += "$name: gear disc ${m.gearDisc} is ${m.body - m.gearDisc} darker than the body ${m.body} (max 10)"
            if (m.artBeside - m.edge < 12f) problems += "$name: edge ${m.edge} differs from the art ${m.artBeside} by only ${m.artBeside - m.edge} (min 12)"
            if (m.contrast < 4.5f) problems += "$name: text contrast ${m.contrast} < 4.5"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Dark art is unchanged: the crop's pixels are written to build/pill-crops so a before/after hash comparison can prove it. */
    @Test fun darkPresetCropIsWritten() {
        val s = capture(Wallpaper(WallpaperKind.Preset, "aurora"))
        val p = s.pill
        val crop = Bitmap.createBitmap(s.full, (p.left - 40).toInt(), (p.top - 40).toInt(), (p.width + 80).toInt(), (p.height + 80).toInt())
        val out = java.io.File("build/pill-crops/dark.png").apply { parentFile?.mkdirs() }
        out.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("pill-crop-md5 " + java.security.MessageDigest.getInstance("MD5").digest(out.readBytes()).joinToString("") { "%02x".format(it) })
    }
}
