package dev.glasslauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatchTest {
    @Test fun lettersInOrderMatchAndWordStartsAndRunsScoreHigher() {
        assertTrue("mtrx finds The Matrix", FuzzyMatch.subsequence("mtrx", "The Matrix") > 0)
        assertEquals("a letter that is not there", 0, FuzzyMatch.subsequence("mtrz", "The Matrix"))
        assertEquals("out of order", 0, FuzzyMatch.subsequence("xtm", "The Matrix"))
        val spread = FuzzyMatch.subsequence("tmx", "The Matrix")
        val run = FuzzyMatch.subsequence("mat", "The Matrix")
        assertTrue("a run at a word start beats scattered letters ($run vs $spread)", run > spread)
        assertEquals("too short to guess from", 0, FuzzyMatch.subsequence("ma", "The Matrix"))
        assertEquals("letters spread across the whole title do not count", 0, FuzzyMatch.subsequence("tex", "The Long And Winding Road Matrix"))
    }

    @Test fun oneOrTwoTyposAndASwapAreForgiven() {
        assertTrue(FuzzyMatch.typo("matirx", "The Matrix") > 0)
        assertTrue(FuzzyMatch.typo("intersteller", "Interstellar") > 0)
        assertTrue("a missing letter", FuzzyMatch.typo("netflx", "Netflix") > 0)
        assertEquals("three letters allow one edit", 0, FuzzyMatch.typo("abc", "xyz"))
        assertEquals("far off", 0, FuzzyMatch.typo("matrix", "Casablanca"))
        assertEquals("distance counts a swap as one", 1, FuzzyMatch.distance("ab", "ba"))
        assertEquals(2, FuzzyMatch.distance("kitten", "sitten") + 1)
    }

    @Test fun accentsAndCaseAreFolded() {
        assertTrue(FuzzyMatch.typo("amelei", "Amélie") > 0)
        assertTrue(FuzzyMatch.subsequence("AMLE", "Amélie") > 0)
    }

    @Test fun aGuessNeverBeatsARealMatch() {
        val real = SearchIndex.score("net", "Netflix")
        val guess = SearchIndex.score("netflx", "Netflix")
        assertTrue("$guess < $real", guess in 1 until real)
        assertTrue(SearchIndex.score("matirx", "The Matrix") in 1..29)
        assertEquals(0, SearchIndex.score("zzzz", "The Matrix"))
    }
}
