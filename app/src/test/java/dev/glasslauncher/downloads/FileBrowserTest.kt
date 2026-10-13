package dev.glasslauncher.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileBrowserTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, age: Long, text: String = "x") =
        tmp.newFile(name).also { it.writeText(text); it.setLastModified(1_700_000_000_000 - age) }

    @Test fun listingSkipsSidecarsAndBusyFilesAndSortsNewestFirst() {
        file("old.mp4", 5_000); file("new.apk", 1_000); file("mid.zip", 3_000)
        file("abc.part", 0); file("abc.part.json", 0); file("running.bin", 0); file(".hidden", 0)
        tmp.newFolder("Extras").setLastModified(1_700_000_000_000 - 2_000)
        val names = FileBrowser.list(tmp.root, busy = setOf("running.bin")).map { it.name }
        assertEquals(listOf("new.apk", "Extras", "mid.zip", "old.mp4"), names)
        assertEquals(true, FileBrowser.list(tmp.root).first { it.name == "Extras" }.dir)
    }

    @Test fun busyNamesAreTheUnfinishedDownloadsOnly() {
        val items = listOf(
            DownloadItem("1", "https://h/a", name = "a.zip", state = DownloadState.Active),
            DownloadItem("2", "https://h/b", name = "b.zip", state = DownloadState.Done, file = "b.zip"),
            DownloadItem("3", "https://h/c", state = DownloadState.Paused, file = "c.zip"),
        )
        assertEquals(setOf("a.zip", "c.zip"), FileBrowser.busyNames(items))
    }

    @Test fun renameRejectsBadNames() {
        file("a.txt", 0); file("b.txt", 0)
        assertNull(FileBrowser.renameError(tmp.root, "a.txt", "c.txt"))
        assertNull(FileBrowser.renameError(tmp.root, "a.txt", "a.txt"))
        assertNotNull(FileBrowser.renameError(tmp.root, "a.txt", "b.txt"))
        assertNotNull(FileBrowser.renameError(tmp.root, "a.txt", "  "))
        assertNotNull(FileBrowser.renameError(tmp.root, "a.txt", "../x"))
        assertNotNull(FileBrowser.renameError(tmp.root, "a.txt", "dir/x"))
        assertNotNull(FileBrowser.renameError(tmp.root, "a.txt", "dir\\x"))
        assertNotNull(FileBrowser.renameError(tmp.root, "a.txt", ".."))
        assertNotNull(FileBrowser.renameError(tmp.root, "a.txt", "x.part"))
    }

    @Test fun renameMovesTheFile() {
        val a = file("a.txt", 0, "hello")
        assertNull(FileBrowser.rename(a, " c.txt "))
        assertEquals("hello", File(tmp.root, "c.txt").readText())
        assertEquals(false, a.exists())
        assertNotNull(FileBrowser.rename(File(tmp.root, "c.txt"), "x/y"))
    }

    @Test fun sha256IsHexOfTheContent() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", FileBrowser.sha256(file("h.txt", 0, "hello")))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", FileBrowser.sha256(file("e.txt", 0, "")))
    }

    @Test fun hashSplitsInTwoLines() {
        val h = "a".repeat(32) + "b".repeat(32)
        assertEquals("a".repeat(32) + "\n" + "b".repeat(32), FileBrowser.hashLines(h))
    }

    @Test fun mimeByExtension() {
        assertEquals("video/mp4", FileBrowser.mime("Movie.MP4"))
        assertEquals("application/vnd.android.package-archive", FileBrowser.mime("app.apk"))
        assertNull(FileBrowser.mime("noext"))
        assertNull(FileBrowser.mime("thing.xyz"))
        assertEquals(FileKind.Apk, FileBrowser.kind(FileEntry(File("a.apk"), false, 1, 0)))
        assertEquals(FileKind.Folder, FileBrowser.kind(FileEntry(File("d"), true, 0, 0)))
        assertEquals(FileKind.Other, FileBrowser.kind(FileEntry(File("a.zip"), false, 1, 0)))
    }
}
