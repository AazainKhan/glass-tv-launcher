package dev.glasslauncher.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkFlowTest {
    @Test fun aStagedCopyMustMatchTheVerifiedChecksum() {
        assertTrue(checksumMatches(null, "abc"))
        assertTrue(checksumMatches("ABC", "abc"))
        assertFalse(checksumMatches("abd", "abc"))
    }
}
