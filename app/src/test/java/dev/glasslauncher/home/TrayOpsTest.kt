package dev.glasslauncher.home

import android.content.ComponentName
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.data.Folder
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.appKey
import dev.glasslauncher.data.folderKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** A full tray (six) never refuses an app: the new one goes in and the rightmost app moves down to the grid. */
class TrayOpsTest {

    private fun app(n: Int) = AppEntry("com.example.app$n", "app$n", ComponentName("com.example.app$n", "com.example.app$n.Main"))
    private val apps = (1..12).map(::app)
    private fun p(n: Int) = "com.example.app$n"
    private fun k(n: Int) = appKey(p(n))

    private fun layoutOf(cfg: LauncherConfig, list: List<AppEntry> = apps) = HomeModel.buildLayout(list, cfg)
    private fun tray(cfg: LauncherConfig, list: List<AppEntry> = apps) = layoutOf(cfg, list).dock.map { it.packageName }
    private fun grid(cfg: LauncherConfig, list: List<AppEntry> = apps) = layoutOf(cfg, list).grid.map { it.key }

    private val full = LauncherConfig(dock = (1..6).map(::p), order = (7..12).map(::k))

    // ---- menu: Move to > App Dock ------------------------------------------------------------

    @Test fun menuAddToFullTraySwapsWithTheSlotTheAppCameFrom() {
        val next = HomeModel.addedToTray(full, apps, p(9))
        assertEquals(listOf(1, 2, 3, 4, 5, 9).map(::p), tray(next))
        // app6 (the old rightmost) takes app9's former grid slot.
        assertEquals(listOf(7, 8, 6, 10, 11, 12).map(::k), grid(next))
    }

    @Test fun menuAddFromAFolderSendsTheDisplacedAppToTheFirstGridSlot() {
        val cfg = full.copy(folders = listOf(Folder("f", "F", listOf(p(9), p(10)))), order = listOf(k(7), folderKey("f"), k(8)))
        val next = HomeModel.addedToTray(cfg, apps, p(9))
        assertEquals(listOf(1, 2, 3, 4, 5, 9).map(::p), tray(next))
        assertEquals(listOf(k(6), k(7), folderKey("f"), k(8), k(11), k(12)), grid(next))
        assertEquals(listOf(p(10)), layoutOf(next).grid.filterIsInstance<GridItem.FolderItem>().single().apps.map { it.packageName })
    }

    @Test fun menuAddWhenTheTrayHasRoomAppendsAtTheEnd() {
        // Three installed apps leave the tray room.
        val few = apps.take(3)
        val cfg = LauncherConfig(folders = listOf(Folder("f", "F", listOf(p(3)))), order = listOf(folderKey("f")))
        assertEquals(listOf(p(1), p(2)), tray(cfg, few))
        val next = HomeModel.addedToTray(cfg, few, p(3))
        assertEquals(listOf(p(1), p(2), p(3)), tray(next, few))
    }

    @Test fun menuAddOfAnAppAlreadyInTheTrayIsANoOp() {
        assertEquals(full, HomeModel.addedToTray(full, apps, p(3)))
    }

    @Test fun aTrayFilledFromTheGridCountsAsFullToo() {
        // Only two apps chosen: the shown tray still holds six, so the rightmost shown app makes way.
        val cfg = LauncherConfig(dock = listOf(p(5), p(1)))
        assertEquals(listOf(5, 1, 2, 3, 4, 6).map(::p), tray(cfg))
        val next = HomeModel.addedToTray(cfg, apps, p(9))
        assertEquals(listOf(5, 1, 2, 3, 4, 9).map(::p), tray(next))
        assertEquals(listOf(7, 8, 6, 10, 11, 12).map(::k), grid(next))
    }

    // ---- move mode ---------------------------------------------------------------------------

    @Test fun moveUpIntoAFullTrayInsertsAtPAndDisplacesTheRightmostToTheFormerSlot() {
        val next = HomeModel.addedToTray(full, apps, p(9), 2)
        assertEquals(listOf(1, 2, 9, 3, 4, 5).map(::p), tray(next))
        assertEquals(listOf(7, 8, 6, 10, 11, 12).map(::k), grid(next))
    }

    @Test fun moveUpAtTheLastPositionReplacesTheRightmost() {
        val next = HomeModel.addedToTray(full, apps, p(7), 5)
        assertEquals(listOf(1, 2, 3, 4, 5, 7).map(::p), tray(next))
        assertEquals(listOf(6, 8, 9, 10, 11, 12).map(::k), grid(next))
    }

    @Test fun moveUpIntoATrayWithRoomJustGrows() {
        val few = apps.take(4)
        val cfg = LauncherConfig(folders = listOf(Folder("f", "F", listOf(p(4)))), order = listOf(folderKey("f")))
        val next = HomeModel.addedToTray(cfg, few, p(4), 1)
        assertEquals(listOf(1, 4, 2, 3).map(::p), tray(next, few))
    }

    @Test fun positionIsClampedIntoTheTray() {
        val next = HomeModel.addedToTray(full, apps, p(8), 99)
        assertEquals(p(8), tray(next).last())
        assertEquals(6, tray(next).size)
    }

    // ---- atomicity: two ops back to back, no layout recompute between them ------------------------

    @Test fun twoBackToBackOpsBothTakeEffect() {
        val once = HomeModel.addedToTray(full, apps, p(9), 0)
        val twice = HomeModel.addedToTray(once, apps, p(8), 1)
        assertEquals(listOf(9, 8, 1, 2, 3, 4).map(::p), tray(twice))
        assertEquals(listOf(7, 5, 6, 10, 11, 12).map(::k), grid(twice))
        val out = HomeModel.removedFromTray(twice, apps, p(9))
        assertTrue(p(9) !in tray(out))
        assertEquals(listOf(7, 8, 1, 2, 3, 4).map(::p), tray(out))
    }

    // ---- invariants over random configs ------------------------------------------------------

    @Test fun invariantsHoldForRandomConfigs() {
        val rnd = Random(24)
        var roomCases = 0
        var fullCases = 0
        repeat(600) { iter ->
            // Alternate big installs (tray always filled to six) with small ones (tray has room).
            val installed = if (iter % 2 == 0) apps else apps.take(rnd.nextInt(3, 6))
            val pool = installed.map { it.packageName }.shuffled(rnd)
            val hidden = pool.take(rnd.nextInt(0, 3)).toSet()
            val rest = pool.filter { it !in hidden }
            val dock = rest.take(rnd.nextInt(0, 3)) + hidden.take(rnd.nextInt(0, 2))
            val inFolder = rest.drop(dock.size).take(rnd.nextInt(0, 3)) + hidden.drop(1).take(1)
            val folders = if (inFolder.isEmpty()) emptyList() else listOf(Folder("f", "F", inFolder))
            val loose = rest.filter { it !in dock && it !in inFolder }
            // Hidden apps' keys sit among the visible ones in the grid order.
            val order = (loose.map(::appKey) + hidden.map(::appKey) + folders.map { folderKey(it.id) }).shuffled(rnd)
            val cfg = LauncherConfig(dock = dock, order = order, folders = folders, hidden = hidden)
            val before = HomeModel.buildLayout(installed, cfg)
            val candidates = (before.grid.filterIsInstance<GridItem.App>().map { it.app.packageName } + inFolder.filter { it !in hidden }).distinct()
            if (candidates.isEmpty()) return@repeat
            if (before.dock.size < 6) roomCases++ else fullCases++
            val pkg = candidates.random(rnd)
            val ops = listOf(
                HomeModel.addedToTray(cfg, installed, pkg),
                HomeModel.addedToTray(cfg, installed, pkg, rnd.nextInt(0, 6)),
            )
            fun visible(l: HomeLayout) = l.dock.map { it.packageName } + l.grid.flatMap {
                when (it) { is GridItem.App -> listOf(it.app.packageName); is GridItem.FolderItem -> it.apps.map { a -> a.packageName } }
            }
            for (next in ops) {
                val after = HomeModel.buildLayout(installed, next)
                assertEquals(visible(before).sorted(), visible(after).sorted())
                assertEquals(visible(after).distinct().size, visible(after).size)
                assertTrue(after.dock.size <= 6)
                val expectedSize = minOf(6, before.dock.size + 1)
                assertEquals(expectedSize, after.dock.size.coerceAtMost(expectedSize))
                assertEquals(1, after.dock.count { it.packageName == pkg })
                // Other grid items keep their relative order (the displaced app may be new in it).
                val others = before.grid.map { it.key }.filter { it != appKey(pkg) }
                assertEquals(others.filter { it in after.grid.map { g -> g.key } }, after.grid.map { it.key }.filter { it in others })
                // Hidden apps stay hidden, and their order keys keep their relative order.
                assertTrue(hidden.none { h -> h in visible(after) })
                assertEquals(cfg.hidden, next.hidden)
                val hiddenKeys = hidden.map(::appKey).toSet()
                assertEquals(cfg.order.filter { it in hiddenKeys }, next.order.filter { it in hiddenKeys })
                // Re-adding what is already in the tray changes nothing.
                assertEquals(next, HomeModel.addedToTray(next, installed, pkg))
            }
        }
        assertTrue("has-room cases reached: $roomCases", roomCases > 20)
        assertTrue("full cases reached: $fullCases", fullCases > 20)
    }
}
