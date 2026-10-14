package dev.glasslauncher.featured

/**
 * Holding a D-pad key (P158): the focus moves on every key repeat, but the heavy things that follow the focused item (the hero
 * backdrop bake, the title and synopsis crossfade, the neighbour prefetch) must not run per item. They follow the settled item: the
 * focus one, once the repeats have stopped for [SETTLE_MS]. A single press is not a hold: it settles at once. Pure, so the rule is
 * unit-tested with a simulated held key; Home feeds it the repeat clock.
 */
object HoldSettle {
    /** A key is held while repeats arrive less than this far apart (Android's repeat is ~20 to 50 ms; the first gap is ~500 ms). */
    const val HOLD_WINDOW_MS = 600L
    /** After the last repeat the hero settles on the focused item this much later. */
    const val SETTLE_MS = 160L

    /** True while a held key is still repeating: the last repeat event was less than [HOLD_WINDOW_MS] ago. */
    fun holding(sinceLastRepeatMs: Long): Boolean = sinceLastRepeatMs in 0 until HOLD_WINDOW_MS

    /** The Cover Flow takes at most one step per this many ms from a held key; faster repeats are dropped (the spring would only be retargeted before it moved). */
    const val REPEAT_STEP_MS = 90L

    /** A key event steps the Cover Flow: always for a press, for a repeat only when [REPEAT_STEP_MS] has passed since the last step. */
    fun acceptStep(repeatCount: Int, sinceLastStepMs: Long): Boolean = repeatCount == 0 || sinceLastStepMs >= REPEAT_STEP_MS

    /** How long the settled item waits before following a focus change made now: nothing for a press, [SETTLE_MS] for a hold. */
    fun delayMs(sinceLastRepeatMs: Long): Long = if (holding(sinceLastRepeatMs)) SETTLE_MS else 0L
}
