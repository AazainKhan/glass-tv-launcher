package dev.glasslauncher.home

import dev.glasslauncher.data.Folder
import dev.glasslauncher.data.LauncherConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Rearrange Folder: an app moved onto another app's place in an open folder; the ones between shift by one. */
class FolderRearrangeTest {
    private val cfg = LauncherConfig(folders = listOf(Folder("f", "F", listOf("a", "b", "c", "d", "e")), Folder("g", "G", listOf("x", "y"))))
    private fun apps(c: LauncherConfig, id: String = "f") = c.folders.first { it.id == id }.apps

    @Test fun movesRightByOneSwapsNeighbours() =
        assertEquals(listOf("b", "a", "c", "d", "e"), apps(HomeModel.movedInFolder(cfg, "f", "a", "b")))

    @Test fun movesLeftByOne() =
        assertEquals(listOf("a", "c", "b", "d", "e"), apps(HomeModel.movedInFolder(cfg, "f", "c", "b")))

    @Test fun aRowDownShiftsTheAppsBetween() =
        assertEquals(listOf("b", "c", "d", "a", "e"), apps(HomeModel.movedInFolder(cfg, "f", "a", "d")))

    @Test fun aRowUpShiftsTheAppsBetween() =
        assertEquals(listOf("a", "e", "b", "c", "d"), apps(HomeModel.movedInFolder(cfg, "f", "e", "b")))

    @Test fun anotherFolderIsUntouched() =
        assertEquals(listOf("x", "y"), apps(HomeModel.movedInFolder(cfg, "f", "a", "e"), "g"))

    @Test fun noOpsReturnTheSameConfig() {
        assertSame(cfg, HomeModel.movedInFolder(cfg, "f", "a", "a"))
        assertSame(cfg, HomeModel.movedInFolder(cfg, "f", "a", "zzz"))
        assertSame(cfg, HomeModel.movedInFolder(cfg, "f", "x", "b")) // x lives in another folder
        assertSame(cfg, HomeModel.movedInFolder(cfg, "nope", "a", "b"))
    }

    @Test fun nothingIsLostOrDuplicated() {
        var c = cfg
        val order = listOf("a" to "e", "e" to "b", "c" to "a", "d" to "d", "b" to "c")
        for ((p, t) in order) c = HomeModel.movedInFolder(c, "f", p, t)
        assertEquals(listOf("a", "b", "c", "d", "e"), apps(c).sorted())
    }

    // ---- one D-pad step, resolved against the newest config ---------------------------------------

    private val six = LauncherConfig(folders = listOf(Folder("f", "F", listOf("a", "b", "c", "d", "e", "f2"))))
    private val installed = setOf("a", "b", "c", "d", "e", "f2", "x")
    private fun step(c: LauncherConfig, pkg: String, dx: Int, dy: Int) = HomeModel.movedInFolderBy(c, installed, "f", pkg, dx, dy, 3)

    @Test fun rightAndLeftMoveOnePlace() {
        assertEquals(listOf("b", "a", "c", "d", "e", "f2"), apps(step(six, "a", 1, 0)))
        assertEquals(listOf("a", "c", "b", "d", "e", "f2"), apps(step(six, "c", -1, 0)))
    }

    @Test fun downAndUpMoveARow() {
        assertEquals(listOf("b", "c", "d", "a", "e", "f2"), apps(step(six, "a", 0, 1)))
        assertEquals(listOf("a", "e", "b", "c", "d", "f2"), apps(step(six, "e", 0, -1)))
    }

    @Test fun edgesAreNoOps() {
        assertSame(six, step(six, "a", -1, 0))
        assertSame(six, step(six, "f2", 1, 0))
        assertSame(six, step(six, "a", 0, -1))
        assertSame(six, step(six, "d", 0, 1)) // already in the last row
    }

    @Test fun aShortLastRowTakesTheAppAtItsEnd() {
        val five = six.copy(folders = listOf(Folder("f", "F", listOf("a", "b", "c", "d", "e"))))
        // c is in the first row; the row below has d, e: moving down lands on e (the last), not off the end.
        assertEquals(listOf("a", "b", "d", "e", "c"), apps(step(five, "c", 0, 1)))
    }

    @Test fun twoQuickPressesWalkTwoPlacesNotBackAndForth() {
        // The second press is computed from the config the first one produced, so it carries on.
        val once = step(six, "a", 1, 0)
        assertEquals(listOf("b", "c", "a", "d", "e", "f2"), apps(step(once, "a", 1, 0)))
    }

    @Test fun hiddenAppsAndTheTrayAreSkipped() {
        val cfg = LauncherConfig(dock = listOf("b"), hidden = setOf("c"), folders = listOf(Folder("f", "F", listOf("a", "b", "c", "d", "e"))))
        // Shown in the folder: a, d, e. a moves right onto d's place: the shown order becomes d, a, e (hidden c and docked b sit where they were in the raw list).
        assertEquals(listOf("b", "c", "d", "a", "e"), apps(step(cfg, "a", 1, 0)))
        assertSame(cfg, step(cfg, "c", 1, 0)) // hidden: not shown, so not movable
    }
}
