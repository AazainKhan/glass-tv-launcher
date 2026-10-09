package dev.glasslauncher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG contrast of the palette's text and focus tokens on the pages they sit on, in both appearances. */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    // The light page is the pastel-washed backdrop (bright, faintly hued); the dark page its charcoal.
    private val lightPage = Color(0xFFE6E8F2)
    private val darkPage = Color(0xFF1C1E26)

    private fun check(name: String, fg: Color, bg: Color, min: Float) {
        val r = ratio(fg.compositeOver(bg), bg)
        assertTrue("$name: ${"%.2f".format(r)}:1, needs $min:1", r >= min)
    }

    @Test fun textReadsInBothAppearances() {
        for ((p, page) in listOf(Palette(light = true) to lightPage, Palette(light = false) to darkPage)) {
            val name = if (p.light) "light" else "dark"
            check("$name primary", p.primary, page, 7f)
            check("$name secondary", p.secondary, page, 4.5f)
            check("$name faint", p.faint, page, 3f)
            check("$name text on focus", p.onFocusFill, p.focusFill, 7f)
            check("$name danger on the page", p.danger, page, 4.5f)
            check("$name accent dot", p.accent, page, 3f)
        }
    }

    // The white focus capsule must stand out from a light page: its ring carries the contrast.
    @Test fun focusStandsOutOnALightPage() {
        val p = Palette(light = true)
        check("light focus ring", p.focusRing, lightPage, 3f)
        val row = p.rowFill.compositeOver(lightPage)
        assertTrue("light rows should be lighter than the page, not darker", row.luminance() >= lightPage.luminance())
    }
}
