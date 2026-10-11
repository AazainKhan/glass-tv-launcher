package dev.glasslauncher.system

import org.junit.Assert.assertEquals
import org.junit.Test

/** P87: the remote-keys service is put back into the enabled list when an update leaves it off (pure part). */
class KeysServiceTest {
    private val svc = "dev.glasslauncher/dev.glasslauncher.system.RemoteKeysService"

    @Test fun addsItToAnEmptyOrMissingSetting() {
        assertEquals(svc, RootFeatures.withService(null, svc))
        assertEquals(svc, RootFeatures.withService("", svc))
        assertEquals(svc, RootFeatures.withService("null", svc))
    }

    @Test fun appendsToOthersAndNeverDuplicates() {
        assertEquals("a.b/c.D:$svc", RootFeatures.withService("a.b/c.D", svc))
        assertEquals("a.b/c.D:$svc", RootFeatures.withService("a.b/c.D:$svc", svc))
        assertEquals(svc, RootFeatures.withService(svc, svc))
    }
}
