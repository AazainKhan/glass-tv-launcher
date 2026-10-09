package dev.glasslauncher.shots

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import dev.glasslauncher.data.Folder
import dev.glasslauncher.data.folderKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The open folder as laid out on screen (tvOS 27: a fixed 3x3 glass panel, tiles on a 200 x 121 dp pitch) and
 * how focus moves through that grid. The harness is xhdpi, so 1 dp = 2 px.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class FolderLayoutTest {

    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    /** In the order the folder lists them (by name: Disney+, Downloader, Jellyfin, Max, Plex, Spotify, Stremio, Twitch, VLC, YouTube). */
    private val pool = listOf(
        "com.disney.disneyplus", "com.esaba.downloader", "org.jellyfin.androidtv", "com.hbo.hbonow", "com.plexapp.android",
        "com.spotify.tv.android", "com.stremio.one", "tv.twitch.android.viewer", "org.videolan.vlc", "com.amazon.firetv.youtube",
    )

    private fun openFolder(count: Int, block: () -> Unit) {
        TvHarness.setUp(config = { c -> c.copy(dock = listOf("com.netflix.ninja"), folders = listOf(Folder("f", "Media", pool.take(count))), order = listOf(folderKey("f"))) })
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.focusTag(folderKey("f"))
            compose.settle()
            compose.press(Button.Select)
            compose.waitForTag("folder-title")
            compose.settle()
            block()
        }
    }

    private fun node(tag: String): SemanticsNode =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().first()

    private fun tile(i: Int) = node("app:${pool[i]}").boundsInRoot
    private fun dp(px: Float) = px / 2f

    @Test fun panelIsAFixedThreeByThreeWhateverTheAppCount() {
        for (n in listOf(5, 6, 9, 10)) openFolder(n) {
            val p = node("folder-panel").boundsInRoot
            assertEquals("width, $n apps", 604f, dp(p.width), 1f)
            assertEquals("height, $n apps", 385f, dp(p.height), 1f)
            assertEquals("left, $n apps", 178f, dp(p.left), 1f)
            assertEquals("top, $n apps", 75f, dp(p.top), 1f)
        }
    }

    @Test fun tilesSitOnTheTvosPitch() = openFolder(9) {
        val t0 = tile(0)
        assertEquals("first tile left", 217.5f, dp(t0.left), 1f)
        assertEquals("first tile top", 101f, dp(t0.top), 1f)
        assertEquals("tile width", 125f, dp(t0.width), 1f)
        assertEquals("column pitch", 200f, dp(tile(1).left - t0.left), 1f)
        assertEquals("column pitch 2", 200f, dp(tile(2).left - tile(1).left), 1f)
        assertEquals("row pitch", 121f, dp(tile(3).top - t0.top), 1f)
        assertEquals("row pitch 2", 121f, dp(tile(6).top - tile(3).top), 1f)
        assertEquals("tile 5 is column 3", tile(2).left, tile(5).left, 0.5f)
    }

    @Test fun nameCapsuleSitsCentredAbovePanel() = openFolder(5) {
        val c = node("folder-title").boundsInRoot
        val p = node("folder-panel").boundsInRoot
        assertTrue("capsule bottom ${c.bottom} above panel top ${p.top}", c.bottom <= p.top)
        assertEquals("centred", p.center.x, c.center.x, 1f)
        assertEquals("capsule top", 14f, dp(c.top), 3f)
        assertEquals("capsule height", 50f, dp(c.height), 3f)
    }

    @Test fun focusMovesThroughTheThreeColumnGrid() = openFolder(9) {
        fun at() = compose.focused()
        fun app(i: Int) = "app:${pool[i]}"
        assertEquals(app(0), at())
        compose.press(Button.Left); assertEquals("Left stops at the row start", app(0), at())
        compose.press(Button.Right, Button.Right); assertEquals(app(2), at())
        compose.press(Button.Right); assertEquals("Right stops at the row end", app(2), at())
        compose.press(Button.Down); assertEquals(app(5), at())
        compose.press(Button.Down); assertEquals(app(8), at())
        compose.press(Button.Down); assertEquals("Down stops at the last row", app(8), at())
        compose.press(Button.Left, Button.Left); assertEquals(app(6), at())
        compose.press(Button.Up, Button.Up); assertEquals(app(0), at())
        compose.press(Button.Up); assertEquals("Up from the first row reaches the name", "folder-title", at())
        compose.press(Button.Down); assertEquals("Down from the centred name lands on the middle column", app(1), at())
    }

    @Test fun aShortLastRowKeepsFocusInTheGrid() = openFolder(5) {
        fun app(i: Int) = "app:${pool[i]}"
        compose.press(Button.Right, Button.Right, Button.Down)
        assertTrue("Down from column 3 of a 2-app row lands on an app, was ${compose.focused()}", compose.focused() in setOf(app(3), app(4)))
    }

    @Test fun moreThanNineAppsScrollAndStayReachable() = openFolder(10) {
        fun app(i: Int) = "app:${pool[i]}"
        compose.press(Button.Down, Button.Down, Button.Down)
        assertEquals(app(9), compose.focused())
        compose.settle()
        val p = node("folder-panel").boundsInRoot
        val t = tile(9)
        assertTrue("the tenth app scrolled inside the panel: tile ${t.top}..${t.bottom}, panel ${p.top}..${p.bottom}", t.top >= p.top && t.bottom <= p.bottom)
    }
}
