package dev.glasslauncher.glass

import java.util.concurrent.ConcurrentHashMap

/**
 * The one image-quality gate for every full-screen, backdrop or shelf picture (P79, P81): a picture is never
 * stretched more than [LIMIT] times (on its shown part), and is never a placeholder or an icon-sized asset
 * blown up. What to do instead, in order:
 *  (a) a larger variant of the same source ([Variants]);
 *  (b) [Level.Soft]: a blurred, colour-rich backdrop made from the art (baked), the art itself shown sharp at its own
 *      size where it is shown at all;
 *  (c) [Level.Plain]: no usable art, a clean gradient from the app's colour, or the theme's default backdrop.
 * Decisions are cached per URL, so a picture is judged once and never flips between states.
 */
object ImageGate {
    enum class Level { Sharp, Soft, Plain }

    /** The most a picture may be enlarged to fill the screen. */
    const val LIMIT = 1.25f
    /** A source with a shorter side than this is an icon, not backdrop art: never enlarged, never shown stretched. */
    const val ICON_SIDE = 200

    /** How much the part of the art that shows has to grow to fill [targetW]x[targetH]: it is cropped to the screen's shape. */
    fun scaleNeeded(srcW: Int, srcH: Int, targetW: Int = 1920, targetH: Int = 1080): Float {
        if (srcW <= 0 || srcH <= 0) return Float.MAX_VALUE
        val cropW = if (srcH.toLong() * targetW >= srcW.toLong() * targetH) srcW.toFloat() else srcH * targetW / targetH.toFloat()
        return targetW / cropW
    }

    fun level(srcW: Int, srcH: Int, targetW: Int = 1920, targetH: Int = 1080, placeholder: Boolean = false, limit: Float = LIMIT): Level = when {
        placeholder || srcW <= 0 || srcH <= 0 -> Level.Plain
        minOf(srcW, srcH) < ICON_SIDE -> Level.Plain
        scaleNeeded(srcW, srcH, targetW, targetH) <= limit -> Level.Sharp
        else -> Level.Soft
    }

    /** URLs known to be a stock or placeholder picture rather than the title's own art (lower case substrings). */
    private val PLACEHOLDER_HINTS = listOf(
        "placeholder", "no_image", "no-image", "noimage", "image_not_available", "default_poster", "default-poster",
        "default_backdrop", "default-backdrop", "/missing.", "blank.png", "tv_ps_banner",
    )

    fun isPlaceholder(url: String?): Boolean = url != null && url.lowercase().let { u -> PLACEHOLDER_HINTS.any { it in u } }

    private val decisions = ConcurrentHashMap<String, Level>()

    /**
     * The decision for [url] at its decoded [srcW]x[srcH]: made once and kept (the same URL never changes level
     * between calls, so a slide that was soft isn't sharp the next time round, and nothing flashes).
     */
    fun decide(url: String, srcW: Int, srcH: Int, targetW: Int = 1920, targetH: Int = 1080): Level =
        decisions.getOrPut("$url@${targetW}x$targetH") { level(srcW, srcH, targetW, targetH, placeholder = isPlaceholder(url)) }

    /** Tests only. */
    internal fun forget() = decisions.clear()
}

/**
 * Larger variants of the same picture, from the same source, worth trying before softening (P79, P81). Each is
 * only a candidate: it is used when it comes back, and the original otherwise.
 */
object Variants {
    /** Candidate URLs for [url], largest first, ending with [url] itself. */
    fun candidates(url: String): List<String> = buildList {
        SpotifyArt.large(url)?.let { add(it) }
        tmdbOriginal(url)?.let { add(it) }
        add(url)
    }

    private val TMDB = Regex("""^(https://image\.tmdb\.org/t/p/)(w\d{3,4}|h\d{3,4})(/.*)$""")

    /** TMDB serves any picture at its original size under /t/p/original (the sized variants top out at 1280). */
    fun tmdbOriginal(url: String): String? = TMDB.matchEntire(url)?.let { "${it.groupValues[1]}original${it.groupValues[3]}" }
}
