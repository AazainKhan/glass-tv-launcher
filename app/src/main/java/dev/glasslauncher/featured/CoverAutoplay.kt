package dev.glasslauncher.featured

/**
 * Spotify's resting Cover Flow preview (P85): it starts in the middle of the covers (covers on both sides) and, once
 * focus has rested, steps one cover at a time, back and forth (no sweep from the last cover to the first). Pure, so the
 * stepping is unit-tested; Home drives it with its own idle clock.
 */
object CoverAutoplay {
    /** Quiet time after the last key before the first step. */
    const val QUIET_MS = 4_000L
    /** Between steps once it is running. */
    const val STEP_MS = 3_500L

    /** The cover the preview starts on: the middle one. */
    fun startIndex(size: Int): Int = if (size <= 1) 0 else size / 2

    /** The next [index] and direction when stepping [dir] (+1 right, -1 left): reverses at either end. */
    fun next(index: Int, dir: Int, size: Int): Pair<Int, Int> {
        if (size <= 1) return 0 to 1
        var d = if (dir >= 0) 1 else -1
        val n = index + d
        if (n > size - 1 || n < 0) d = -d
        return (index + d).coerceIn(0, size - 1) to d
    }
}

/**
 * The Top Shelf's slideshow and Cover Flow autoplay stop after a minute with no input (P102): every step re-bakes a
 * backdrop, which was 14% of a core for the slideshow and 22-27% for Cover Flow, all night, with nobody watching. It
 * stays on the current item; any key resumes it. Pure, so the rule is unit-tested; Home reads its idle clock.
 */
object TopShelfIdle {
    /** No input for this long: autoplay holds on the current item. */
    const val PAUSE_AFTER_MS = 60_000L
    /** How often a paused autoplay looks for the next key (a cheap timer, never a bake). */
    const val POLL_MS = 2_000L

    fun paused(sinceInputMs: Long): Boolean = sinceInputMs >= PAUSE_AFTER_MS
}
