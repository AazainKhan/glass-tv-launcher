package dev.glasslauncher.glass

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** The fill drawn before a tile's glass arrives is the colour the glass will settle to, so the swap is invisible. */
class GlassMatchTest {
    @Test fun matchesTintThenDimOverTheSceneBehind() {
        val c = GlassMatch.fill(Color(0.5f, 0.5f, 0.5f), Color.Black.copy(alpha = 0.06f), dim = 0.42f)
        assertEquals(0.5f * 0.94f * 0.58f, c.red, 0.01f)
        assertEquals(1f, c.alpha, 0f)
    }

    @Test fun aWhiteTintLightensIt() {
        val c = GlassMatch.fill(Color(0.2f, 0.2f, 0.2f), Color.White.copy(alpha = 0.5f), dim = 0f)
        assertEquals(0.6f, c.red, 0.01f)
    }
}
