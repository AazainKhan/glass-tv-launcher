package dev.glasslauncher.data

import dev.glasslauncher.system.RemoteButtons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingProfilesTest {
    private val pad = ProfileKind.Pad
    private val cfg = LauncherConfig(
        remoteButtons = mapOf(RemoteButtons.padKey("Xbox", "a") to "glass:home", RemoteButtons.padKey("Other", "a") to "none", "app1" to "none"),
        controllerSticks = mapOf("Xbox" to ControllerSticks(left = StickTuning(sensX = 1.5f))),
    )

    @Test fun savingCapturesOnlyThatControllersChangedButtons() {
        val saved = MappingProfiles.save(cfg, pad, "Xbox", "Racing")!!
        val p = MappingProfiles.of(saved, pad).single()
        assertEquals(mapOf("a" to "glass:home"), p.buttons)
        assertEquals(1.5f, p.sticks!!.left.sensX, 0f)
        assertEquals("Racing", saved.activeProfile["Xbox"])
    }

    @Test fun loadingOntoAnotherControllerTouchesOnlyThatOne() {
        val p = MappingProfile("P", mapOf("b" to "glass:switcher"))
        val out = MappingProfiles.load(cfg, pad, "Other", p)
        assertEquals("glass:switcher", out.remoteButtons[RemoteButtons.padKey("Other", "b")])
        assertNull("a button the profile doesn't list returns to default", out.remoteButtons[RemoteButtons.padKey("Other", "a")])
        assertEquals("glass:home", out.remoteButtons[RemoteButtons.padKey("Xbox", "a")])
        assertEquals("none", out.remoteButtons["app1"])
    }

    @Test fun aRemoteProfileUsesTheSharedKeys() {
        val saved = MappingProfiles.save(cfg, ProfileKind.Remote, "Fire Remote", "Mine")!!
        assertEquals(mapOf("app1" to "none"), MappingProfiles.of(saved, ProfileKind.Remote).single().buttons)
        val reset = MappingProfiles.load(saved, ProfileKind.Remote, "Fire Remote", MappingProfile("Empty"))
        assertNull(reset.remoteButtons["app1"])
    }

    @Test fun theSameNameReplacesAndNamesAreCleaned() {
        val one = MappingProfiles.save(cfg, pad, "Xbox", "Racing")!!
        val two = MappingProfiles.save(one.copy(remoteButtons = emptyMap()), pad, "Xbox", "  racing ")!!
        assertEquals(1, MappingProfiles.of(two, pad).size)
        assertTrue(MappingProfiles.of(two, pad).single().buttons.isEmpty())
        assertNull(MappingProfiles.save(cfg, pad, "Xbox", "   "))
        assertEquals(MappingProfiles.MAX_NAME, MappingProfiles.clean("x".repeat(40))!!.length)
    }

    @Test fun theListIsCappedAndDeleteForgetsTheActiveMark() {
        var c = cfg
        repeat(MappingProfiles.MAX_PER_KIND) { c = MappingProfiles.save(c, pad, "Xbox", "P$it")!! }
        assertNull(MappingProfiles.save(c, pad, "Xbox", "One more"))
        val gone = MappingProfiles.delete(c, pad, "P${MappingProfiles.MAX_PER_KIND - 1}")
        assertEquals(MappingProfiles.MAX_PER_KIND - 1, MappingProfiles.of(gone, pad).size)
        assertTrue(gone.activeProfile.isEmpty())
    }
}
