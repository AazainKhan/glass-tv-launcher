package dev.glasslauncher.featured

/**
 * Holding a D-pad key (P158): the focus moves on every key repeat, but the heavy things that follow the focused item (the hero
 * backdrop bake, the title and synopsis crossfade, the neighbour prefetch) must not run per item. They follow the settled item: the
 * focus one, once the repeats have stopped for [SETTLE_MS]. A single press is not a hold: it settles at once. Pure, so the rule is
 * unit-tested with a simulated held key; Home feeds it the repeat clock.
 */
object HoldSettle {
    /** A key is held while repeats arrive less than this far apart (Fire's repeat is fixed: 400 ms to the first repeat, then 50 ms; it is not re-timed, there is no momentum and no skipping: one item per tick). */
    const val HOLD_WINDOW_MS = 600L
    /** After the last focus move the hero settles on the focused item this much later (tvOS's restartable settle timer, about 0.35 s, rom/research/tvos-fast-scroll.md). */
    const val SETTLE_MS = 350L

    /** True while a held key is still repeating: the last repeat event was less than [HOLD_WINDOW_MS] ago. */
    fun holding(sinceLastRepeatMs: Long): Boolean = sinceLastRepeatMs in 0 until HOLD_WINDOW_MS

    /** How long the settled item waits before following a focus change made now: nothing for a press, [SETTLE_MS] for a hold. */
    fun delayMs(sinceLastRepeatMs: Long): Long = if (holding(sinceLastRepeatMs)) SETTLE_MS else 0L
}
