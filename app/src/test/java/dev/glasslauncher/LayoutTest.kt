package dev.glasslauncher

import android.content.ComponentName
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.data.Folder
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.appKey
import dev.glasslauncher.data.folderKey
import dev.glasslauncher.home.GridItem
import dev.glasslauncher.home.HomeModel
import dev.glasslauncher.system.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {

    private fun app(pkg: String) = AppEntry(pkg, pkg.substringAfterLast('.'), ComponentName(pkg, "$pkg.Main"))
    private val apps = (1..10).map { app("com.example.app$it") }

    @Test fun dockIsCappedAtSixAndSkipsMissingApps() {
        val dock = listOf("missing") + apps.take(8).map { it.packageName }
        val layout = HomeModel.buildLayout(apps, LauncherConfig(dock = dock))
        assertEquals(6, layout.dock.size)
        assertEquals("com.example.app1", layout.dock.first().packageName)
    }

    @Test fun dockAppsAreNotRepeatedInTheGrid() {
        val layout = HomeModel.buildLayout(apps, LauncherConfig(dock = listOf("com.example.app1")))
        assertFalse(layout.grid.any { it.key == appKey("com.example.app1") })
        assertEquals(9, layout.grid.size)
    }

    @Test fun hiddenAppsDisappearEverywhere() {
        val cfg = LauncherConfig(dock = listOf("com.example.app2"), hidden = setOf("com.example.app2", "com.example.app3"))
        val layout = HomeModel.buildLayout(apps, cfg)
        assertTrue(layout.dock.isEmpty())
        assertEquals(8, layout.grid.size)
    }

    @Test fun foldersCollectTheirAppsAndEmptyFoldersVanish() {
        val cfg = LauncherConfig(
            folders = listOf(
                Folder("a", "Movies", listOf("com.example.app4", "com.example.app5")),
                Folder("b", "Empty", listOf("not.installed")),
            ),
        )
        val layout = HomeModel.buildLayout(apps, cfg)
        val folders = layout.grid.filterIsInstance<GridItem.FolderItem>()
        assertEquals(listOf("a"), folders.map { it.folder.id })
        assertEquals(2, folders.single().apps.size)
        assertFalse(layout.grid.any { it.key == appKey("com.example.app4") })
    }

    @Test fun savedOrderComesFirstThenEverythingElse() {
        val cfg = LauncherConfig(
            order = listOf(appKey("com.example.app9"), folderKey("a"), "app:gone"),
            folders = listOf(Folder("a", "F", listOf("com.example.app1"))),
        )
        val keys = HomeModel.buildLayout(apps, cfg).grid.map { it.key }
        assertEquals(listOf(appKey("com.example.app9"), folderKey("a")), keys.take(2))
        assertEquals(10, keys.size)
    }

    @Test fun versionComparison() {
        assertTrue(Updater.isNewer("0.3.0", "0.2.9"))
        assertTrue(Updater.isNewer("1.0", "0.9.9"))
        assertFalse(Updater.isNewer("0.2.0", "0.2.0"))
        assertFalse(Updater.isNewer("0.1.9", "0.2.0"))
    }
}
