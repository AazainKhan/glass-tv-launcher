package dev.glasslauncher.glass

import kotlin.math.pow

/**
 * P137: Control Center's glass adapts to the luminance behind it, as tvOS's does. Over a light scene (a white app,
 * the YouTube Top Shelf) the sheet used to turn near-white under white labels, which vanished. The bake now picks
 * one of two modes from the scene's mean luminance and holds every sheet pixel to a band where that mode's labels
 * keep their contrast:
 *  - dark scene: white labels, glass capped at relative luminance [DARK_CAP] (about 10:1 against white, 3:1 for the system blue glyph);
 *  - light scene: dark labels, glass floored at [LIGHT_FLOOR] (about 9:1 against the dark ink, and 3:1 for the
 *    darkened accent glyphs).
 * All of it is arithmetic on the small baked sheet, off the main thread; nothing is sampled while drawing.
 */
object CcAdapt {
    /** A scene whose mean (sRGB-encoded) luminance is above this gets the light glass and dark labels. */
    const val LIGHT_SCENE = 0.50f
    /** Dark-scene glass never rises above this relative luminance, so white text keeps 4.5:1 (it gets 4.77:1). */
    const val DARK_CAP = 0.05f
    /**
     * The same cap in the Light theme, whose dark-scene glass is deliberately lighter than Dark's (P105: tiles at least
     * 1.5x brighter, ControlCenterMotionTest): white text still gets 7:1, the blue glyph about 2:1.
     */
    const val DARK_CAP_LIGHT_THEME = 0.09f
    /** Light-scene glass never falls below this, so dark ink and the deeper accent keep their contrast. */
    const val LIGHT_FLOOR = 0.55f
    /** Label ink over a light scene (Palette's own dark ink) and over a dark one. */
    const val INK_DARK = 0xFF0E1015.toInt()
    const val INK_LIGHT = 0xFFFFFFFF.toInt()
    /** How far the accent colours are darkened at full dark ink (Wi-Fi blue 0091FF -> about 0068B8). */
    const val ACCENT_DARKEN = 0.72f

    fun isLightScene(meanLuma: Float): Boolean = meanLuma > LIGHT_SCENE

    private val toLinear = FloatArray(256) { i -> val c = i / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f) }
    private fun toSrgb(l: Float): Int {
        val c = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.coerceAtLeast(0f).pow(1f / 2.4f) - 0.055f
        return (c * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** WCAG relative luminance of an opaque ARGB colour. */
    fun relLuminance(argb: Int): Float =
        0.2126f * toLinear[(argb shr 16) and 0xFF] + 0.7152f * toLinear[(argb shr 8) and 0xFF] + 0.0722f * toLinear[argb and 0xFF]

    /** WCAG contrast ratio between two relative luminances. */
    fun contrast(a: Float, b: Float): Float = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)

    /** Composite [fg] (ARGB, any alpha) over opaque [bg]. */
    fun over(fg: Int, bg: Int): Int {
        val a = ((fg ushr 24) and 0xFF) / 255f
        fun ch(s: Int) = (((fg shr s) and 0xFF) * a + ((bg shr s) and 0xFF) * (1f - a) + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** One sheet pixel (opaque ARGB) held to the mode's luminance band. */
    fun fit(argb: Int, lightScene: Boolean, darkCap: Float = DARK_CAP): Int {
        val y = relLuminance(argb)
        val r = toLinear[(argb shr 16) and 0xFF]; val g = toLinear[(argb shr 8) and 0xFF]; val b = toLinear[argb and 0xFF]
        if (!lightScene) {
            if (y <= darkCap) return argb
            val k = darkCap / y
            return (0xFF shl 24) or (toSrgb(r * k) shl 16) or (toSrgb(g * k) shl 8) or toSrgb(b * k)
        }
        if (y >= LIGHT_FLOOR) return argb
        val t = (LIGHT_FLOOR - y) / (1f - y)
        return (0xFF shl 24) or (toSrgb(r + t * (1f - r)) shl 16) or (toSrgb(g + t * (1f - g)) shl 8) or toSrgb(b + t * (1f - b))
    }
}
