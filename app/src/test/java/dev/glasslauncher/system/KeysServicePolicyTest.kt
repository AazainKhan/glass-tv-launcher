package dev.glasslauncher.system

import dev.glasslauncher.system.KeysServicePolicy.Action
import dev.glasslauncher.system.KeysServicePolicy.State
import org.junit.Assert.assertEquals
import org.junit.Test

/** P93: the remote-keys service per install way: APK asks, root / secure-settings keep it on, a manual off is followed. */
class KeysServicePolicyTest {
    private fun state(
        setting: Boolean = true, service: Boolean = false, mode: WriteMode = WriteMode.Root, version: Long = 10,
        last: Long? = 10, boot: Boolean = false, card: Boolean = false,
    ) = State(setting, service, mode, version, last, boot, card)

    @Test fun modeDecision() {
        assertEquals(WriteMode.SecureSettings, KeysServicePolicy.mode(hasSecureWrite = true, hasRoot = true))
        assertEquals(WriteMode.SecureSettings, KeysServicePolicy.mode(hasSecureWrite = true, hasRoot = false))
        assertEquals(WriteMode.Root, KeysServicePolicy.mode(hasSecureWrite = false, hasRoot = true))
        assertEquals(WriteMode.None, KeysServicePolicy.mode(hasSecureWrite = false, hasRoot = false))
    }

    @Test fun aBootIsAChangedBootCounterNotANewProcess() {
        assertEquals(true, KeysServicePolicy.isBoot(bootCount = 8, lastBootCount = 7))
        assertEquals("same boot, process restarted", false, KeysServicePolicy.isBoot(bootCount = 7, lastBootCount = 7))
        assertEquals("first run, nothing to compare", false, KeysServicePolicy.isBoot(bootCount = 7, lastBootCount = null))
        assertEquals("no counter", false, KeysServicePolicy.isBoot(bootCount = -1, lastBootCount = 7))
    }

    @Test fun settingOffMeansNothingIsDone() {
        for (mode in WriteMode.entries) {
            assertEquals(Action.Nothing, KeysServicePolicy.decide(state(setting = false, service = false, mode = mode)))
        }
    }

    @Test fun serviceOnIsRecorded() {
        assertEquals(Action.RecordOn, KeysServicePolicy.decide(state(service = true)))
        assertEquals(Action.RecordOn, KeysServicePolicy.decide(state(service = true, mode = WriteMode.None)))
    }

    @Test fun rootOrSecureSettingsReEnableAfterAnUpdate() {
        for (mode in listOf(WriteMode.Root, WriteMode.SecureSettings)) {
            assertEquals("new version", Action.Enable, KeysServicePolicy.decide(state(mode = mode, version = 11, last = 10)))
            assertEquals("never seen on", Action.Enable, KeysServicePolicy.decide(state(mode = mode, last = null)))
        }
    }

    @Test fun aReinstallOfTheSameVersionCodeIsStillAnUpdate() {
        // versionCode never changes between builds; the install time does.
        assertEquals(Action.Enable, KeysServicePolicy.decide(state(version = 2_000, last = 1_000)))
    }

    @Test fun atBootItIsReEnabledEvenOnTheSameVersion() {
        assertEquals(Action.Enable, KeysServicePolicy.decide(state(boot = true)))
    }

    @Test fun offOnTheSameVersionAfterBeingSeenOnIsTheUserAndGlassFollows() {
        for (mode in listOf(WriteMode.Root, WriteMode.SecureSettings)) {
            assertEquals(Action.UserTurnedOff, KeysServicePolicy.decide(state(mode = mode, version = 10, last = 10)))
        }
    }

    @Test fun anApkInstallAsksOnceAndNeverEnables() {
        assertEquals(Action.ShowCard, KeysServicePolicy.decide(state(mode = WriteMode.None)))
        assertEquals("after the card", Action.Nothing, KeysServicePolicy.decide(state(mode = WriteMode.None, card = true)))
        // Never an Enable or UserTurnedOff for the no-write mode, whatever the version history.
        for (last in listOf(null, 9L, 10L)) for (boot in listOf(false, true)) {
            val a = KeysServicePolicy.decide(state(mode = WriteMode.None, last = last, boot = boot, card = true))
            assertEquals(Action.Nothing, a)
        }
    }
}
