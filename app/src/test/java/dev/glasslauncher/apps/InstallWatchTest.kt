package dev.glasslauncher.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallWatchTest {
    @Test fun aTileShowsTheMostAdvancedSessionOfItsPackage() {
        val out = InstallWatch.statesFor(listOf(
            InstallSession(1, "p.a", "A", 0.2f), InstallSession(2, "p.a", "A", 0.7f), InstallSession(3, "p.b", "B", 0f),
        ))
        assertEquals(0.7f, out.getValue("p.a").progress)
        assertEquals("a queued install has a badge with no progress yet", 0f, out.getValue("p.b").progress)
        assertEquals(2, out.size)
    }

    @Test fun noSessionsNoBadges() = assertEquals(emptyMap<String, InstallState>(), InstallWatch.statesFor(emptyList()))

    @Test fun theEndingCapsuleNamesTheStorageCase() {
        assertEquals("Plex updated", InstallWatch.finishNotice("Plex", true, 5_000_000_000))
        assertEquals("Not enough storage to update Plex.", InstallWatch.finishNotice("Plex", false, 100L * 1024 * 1024))
        assertEquals("Couldn't update Plex.", InstallWatch.finishNotice("Plex", false, 2_000_000_000))
        assertEquals("free space unknown: the plain failure", "Couldn't update Plex.", InstallWatch.finishNotice("Plex", false, null))
    }
}
