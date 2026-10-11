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
