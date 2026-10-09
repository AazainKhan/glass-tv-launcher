package dev.glasslauncher.apps

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Each tile's glow colour is the mean of its bottom band, taken once when the tile is rendered and cached with it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TileGlowTest {
    private fun bitmap(paint: (Bitmap) -> Unit) = Bitmap.createBitmap(TileArt.WIDTH, TileArt.HEIGHT, Bitmap.Config.ARGB_8888).also(paint)

    private fun assertColour(want: Int, got: Int) {
        assertEquals("red of ${Integer.toHexString(got)}", Color.red(want).toFloat(), Color.red(got).toFloat(), 8f)
        assertEquals("green of ${Integer.toHexString(got)}", Color.green(want).toFloat(), Color.green(got).toFloat(), 8f)
        assertEquals("blue of ${Integer.toHexString(got)}", Color.blue(want).toFloat(), Color.blue(got).toFloat(), 8f)
        assertEquals("opaque", 255, Color.alpha(got))
    }

    @Test fun theGlowIsTheBottomBandsColourNotTheTilesTop() {
        val art = bitmap { b ->
            for (y in 0 until b.height) for (x in 0 until b.width) b.setPixel(x, y, if (y < b.height / 2) Color.RED else Color.BLUE)
        }
        assertColour(Color.BLUE, TileArt.glowColor(art))
    }

    @Test fun onlyTheBottomFifthCounts() {
        val art = bitmap { b ->
            for (y in 0 until b.height) for (x in 0 until b.width) b.setPixel(x, y, if (y < b.height * 0.8f - 2) Color.RED else Color.GREEN)
        }
        assertColour(Color.GREEN, TileArt.glowColor(art))
    }

    @Test fun theBandIsAveragedAcrossItsWidth() {
        val art = bitmap { b ->
            for (y in 0 until b.height) for (x in 0 until b.width) b.setPixel(x, y, if (x < b.width / 2) Color.rgb(200, 0, 0) else Color.rgb(0, 0, 100))
        }
        assertColour(Color.rgb(100, 0, 50), TileArt.glowColor(art))
    }

    @Test fun fullyTransparentPixelsAreSkipped() {
        val art = bitmap { b ->
            for (y in 0 until b.height) for (x in 0 until b.width) b.setPixel(x, y, if (x < b.width / 2) Color.TRANSPARENT else Color.rgb(255, 128, 0))
        }
        assertColour(Color.rgb(255, 128, 0), TileArt.glowColor(art))
        // An empty tile has no colour to glow in.
        assertEquals(0, Color.alpha(TileArt.glowColor(bitmap { })))
    }

    private fun spec(n: Int) = TileSpec(AppEntry("com.example.app$n", "App $n", ComponentName("com.example.app$n", "Main")), null, null)

    private fun art(): TileArt {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TileArt(context, IconPacks(context))
    }

    @Test fun theGlowIsMissingUntilTheTileHasLoaded() = runBlocking {
        val art = art()
        assertNull(art.peekGlow(spec(0)))
        art.load(spec(0))
        assertNotNull(art.peekGlow(spec(0)))
        assertNotNull(art.peek(spec(0)))
        assertNull("another tile's glow", art.peekGlow(spec(1)))
        Unit
    }

    @Test fun theGlowGoesWhenTheTileDoesOnTrim() = runBlocking {
        val art = art()
        val specs = (0 until 90).map(::spec)
        specs.forEach { art.load(it) }
        art.trim()
        val kept = specs.filter { art.peek(it) != null }
        // Trimmed to half the 16 MB cache, not just held under its limit.
        assertTrue("trim kept ${kept.size} of ${specs.size}", kept.isNotEmpty() && kept.size * TileArt.WIDTH * TileArt.HEIGHT * 4 <= 8 * 1024 * 1024)
        for (s in specs) assertEquals("${s.app.label}: the glow lives exactly as long as the tile", art.peek(s) != null, art.peekGlow(s) != null)
        Unit
    }
}
