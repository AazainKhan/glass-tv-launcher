package dev.glasslauncher.home

import org.junit.Assert.assertTrue
import org.junit.Test

/** P88: through a focus fade the content never sits on a fill it can't be read on (no washed-out cream). */
class CcFocusPhaseTest {
    @Test fun contentIsReadableAtEveryStepOfTheFade() {
        // Dark CC glass is 0.05-0.18 in luminance; lighter scenes (0.22) get a little less but are never washed out.
        for ((glass, want) in listOf(0.05f to 3f, 0.1f to 3f, 0.15f to 3f, 0.18f to 2.9f, 0.22f to 2.5f)) {
            var worst = Float.MAX_VALUE
            var a = 0f
            while (a <= 1f) { worst = minOf(worst, CcFocusPhase.chosen(a, glass)); a += 0.01f }
            assertTrue("glass $glass: worst contrast ${"%.2f".format(worst)} (want >= $want)", worst >= want)
        }
    }

    @Test fun theOldRuleWasUnreadableMidFade() {
        // The old rule flipped with focus, not the fill: leaving focus puts light content on a fill still ~0.5 white.
        val old = CcFocusPhase.contrast(alpha = 0.5f, glass = 0.1f, dark = false)
        assertTrue("old rule contrast ${"%.2f".format(old)}", old < 3f)
    }
}
