package dev.glasslauncher.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeLogicTest {
    @Test fun ladderIsWidestFirst() {
        assertEquals(ProbeFormat(48000, 2), ProbeLogic.LADDER.first())
        assertEquals(ProbeFormat(16000, 1), ProbeLogic.LADDER.last())
    }

    @Test fun verdicts() {
        assertEquals("CAP 16k/mono ENFORCED", ProbeLogic.verdict(ProbeFormat(16000, 1), null))
        assertEquals("FULL FORMAT ALLOWED (48k stereo)", ProbeLogic.verdict(ProbeFormat(48000, 2), null))
        assertEquals("FULL FORMAT ALLOWED (44k stereo)", ProbeLogic.verdict(ProbeFormat(44100, 2), null))
        assertTrue(ProbeLogic.verdict(ProbeFormat(48000, 1), null).startsWith("PARTIAL"))
        assertEquals("REGISTRATION REFUSED: nope", ProbeLogic.verdict(null, "nope"))
    }

    @Test fun usagesDefaultToMediaOnly() {
        assertEquals(listOf(1), ProbeLogic.parseUsages(null))
        assertEquals(listOf(1, 14, 0), ProbeLogic.parseUsages("game, UNKNOWN,bogus,game"))
    }

    @Test fun levelSilenceAndSignal() {
        assertTrue(ProbeLogic.level(ShortArray(8), 8).silent)
        val l = ProbeLogic.level(shortArrayOf(1000, -2000, 0, 0), 4)
        assertEquals(2000, l.peak)
        assertEquals(Math.sqrt(5_000_000.0 / 4), l.rms, 1e-6)
        val l2 = ProbeLogic.level(shortArrayOf(3000), 1, l)
        assertEquals(3000, l2.peak)
        assertEquals(5L, l2.samples)
    }
}
