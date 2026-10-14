package dev.glasslauncher.featured

import kotlinx.coroutines.launch

/**
 * Holding a D-pad key (P158): the focus moves on every key repeat, but the heavy things that follow the focused item (the hero
 * backdrop bake, the title and synopsis crossfade, the neighbour prefetch) must not run per item. They follow the settled item: the
 * focus one, once the repeats have stopped for [SETTLE_MS]. A single press is not a hold: it settles at once. Pure, so the rule is
 * unit-tested with a simulated held key; Home feeds it the repeat clock.
 */
object HoldSettle {
    /** A key is held while its last repeat is less than this long ago: the settle time, far over the 50 ms between repeats (Fire's repeat is fixed: 400 ms to the first repeat, then 50 ms; it is not re-timed, there is no momentum and no skipping: one item per tick). */
    const val HOLD_WINDOW_MS = 350L
    /** After the last focus move the hero settles on the focused item this much later (tvOS's restartable settle timer, about 0.35 s, rom/research/tvos-fast-scroll.md). */
    const val SETTLE_MS = 350L

    /** True while a held key is still repeating: the last repeat event was less than [HOLD_WINDOW_MS] ago. */
    fun holding(sinceLastRepeatMs: Long): Boolean = sinceLastRepeatMs in 0 until HOLD_WINDOW_MS

    /** How long the settled item waits before following a focus change made now: nothing for a press, [SETTLE_MS] for a hold. */
    fun delayMs(sinceLastRepeatMs: Long): Long = if (holding(sinceLastRepeatMs)) SETTLE_MS else 0L
}

/**
 * The focused index a long row reports (P158): while a key is held it is not written on every tick (that recomposed Home and the
 * whole row per card), only once the key has rested for [settleMs], restarting on each move (tvOS's restartable settle timer). A
 * single press writes at once. [holding] says whether a key is being held right now.
 */
class SettleCommit(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val holding: () -> Boolean,
    private val settleMs: Long = HoldSettle.SETTLE_MS,
    private val commit: (Int) -> Unit,
) {
    private var job: kotlinx.coroutines.Job? = null

    fun move(index: Int) {
        job?.cancel()
        job = if (!holding()) { commit(index); null } else scope.launch { kotlinx.coroutines.delay(settleMs); commit(index) }
    }

    fun cancel() { job?.cancel(); job = null }
}
