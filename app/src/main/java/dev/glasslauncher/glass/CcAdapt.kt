package dev.glasslauncher.glass

import kotlin.math.pow

/**
 * Control Center's one material (P188, replacing P137's two modes): every pixel of the baked sheet is held at or
 * below relative luminance [CAP], whatever is behind it, so white labels keep at least 4.5:1 (they get 4.77:1) on a
 * white app as on a dark one. There is no light-scene mode and no dark-ink variant: P137's light branch floored the
 * sheet at 0.55 for dark ink, and once the ink was made white (P173) it put white labels on near-white glass.
 * All of it is arithmetic on the small baked sheet, off the main thread; nothing is sampled while drawing.
 */
object CcAdapt {
    /** The glass never rises above this relative luminance: white text keeps 4.5:1 (it gets 4.77:1). */
    const val CAP = 0.05f

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

    /** P195: Apple's glass vibrancy (face_cm, rom/research/liquid-glass-recipe.md): gain and offset on the encoded value. */
    const val GAIN = 0.629f
    const val OFFSET = 0.235f
    /** CC's dark dim (x0.57) baked in, as Apple samples the dimmed backdrop. */
    const val DIM = 0.57f
    /** The regular-weight dark wash (0.41 black) over the vibrancy. */
    const val WASH = 0.41f

    /**
     * One sheet pixel (opaque ARGB) through Liquid Glass's bake (P195): the scene's luminance, dimmed, range-compressed by
     * Apple's gain/offset, under the 0.41 wash. Apple also boosts saturation x1.59; the user rejected colour casts (P188),
     * so the glass keeps the scene's brightness and detail only, neutral grey. Held at [CAP] so white text keeps 4.5:1.
     */
    /**
     * P206 (user: "more transparent, like the dock's glass"): candidate recipes, picked on the stick by the file
     * `files/cc-glass` (A, B or C; read at each backdrop bake, so moving to another scene re-bakes). A is the current
     * look. B: closer to the scene's own brightness (0.9 of it plus a little), no floor, cap 0.16 (white text 5.1:1).
     * C: B with a quarter of the scene's colour. Only the JVM's software canvas can't show the sheet, so the choice
     * is made from stick screenshots; the losing recipes go once the user has picked.
     */
    @Volatile var variant = "A"
    @Volatile var variantFile: java.io.File? = null
    fun readVariant() { variant = runCatching { variantFile?.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()?.takeIf { it in setOf("A", "B", "C") } ?: "A" }

    fun fit(argb: Int): Int {
        val v = toSrgb(relLuminance(argb)) / 255f * DIM
        if (variant != "A") {
            val cap = toSrgb(0.16f)
            val g = minOf(((0.9f * v + 0.06f) * 255f + 0.5f).toInt(), cap)
            if (variant == "B") return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            // C: a quarter of the scene's own colour around the same grey.
            val r = (argb shr 16 and 0xFF); val gr = (argb shr 8 and 0xFF); val b = (argb and 0xFF)
            val y = 0.2126f * r + 0.7152f * gr + 0.0722f * b
            fun ch(c: Int) = (g + 0.25f * (c - y) * (g / maxOf(y, 1f))).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(r) shl 16) or (ch(gr) shl 8) or ch(b)
        }
        val out = (GAIN * v + OFFSET) * (1f - WASH)
        val g = minOf((out * 255f + 0.5f).toInt(), toSrgb(CAP))
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }
}
