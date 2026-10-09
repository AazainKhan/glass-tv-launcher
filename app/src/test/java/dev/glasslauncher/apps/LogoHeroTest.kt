package dev.glasslauncher.apps

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Amazon's real Fire TV icons laid out as tvOS logo heroes: one clean background, the logo at half width. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LogoHeroTest {
    private fun icon(name: String) = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("fixtures/icon-$name.png"))!!

    private fun near(a: Int, b: Int) = Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b)) < 40

    @Test fun backgroundIsTheIconsOwnColourEverywhereAroundTheLogo() {
        for (name in listOf("netflix", "spotify", "youtube")) {
            val art = icon(name)
            val bg = art.getPixel(3, 3)
            val hero = LogoHero.compose(art)
            val mid = (720 * LogoHero.CENTRE_Y).toInt()
            // Points around the logo (left/right of it, above, below) are all the plain background colour.
            for ((x, y) in listOf(60 to mid, 1220 to mid, 640 to 40, 640 to 560, 300 to mid, 980 to mid))
                assertTrue("$name: ${Integer.toHexString(hero.getPixel(x, y))} at ($x,$y) isn't the background ${Integer.toHexString(bg)}", near(hero.getPixel(x, y), bg))
            // And the logo itself is there in the middle band.
            assertTrue("$name: no logo", (300..980 step 10).any { x -> !near(hero.getPixel(x, mid), bg) })
        }
    }
}
