package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Frame strips of Home's main transitions, written to app/build/strips/. Not assertions: they're
 * for looking at. Run with `scripts/shots strips` (or -Pstrips=<name> for one).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class MotionStrips {

    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private val only = System.getProperty("strips").orEmpty()

    @Before fun enabled() = assumeTrue("strips not requested", only.isNotEmpty())

    private fun strip(name: String, vararg setup: Button, button: Button, frames: Int = 12, stepMs: Long = 32, columns: Int = 4) {
        assumeTrue(only == "all" || only == name)
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(*setup)
            compose.settle()
            println("strip: " + compose.strip(name, button, frames, stepMs, columns).absolutePath)
        }
    }

    @Test fun dockRight() = strip("dock-right", button = Button.Right)
    @Test fun dockToFeatured() = strip("dock-to-featured", button = Button.Up)
    @Test fun featuredToDock() = strip("featured-to-dock", Button.Up, button = Button.Down)
    @Test fun dockToGrid() = strip("dock-to-grid", button = Button.Down)
    @Test fun gridToDock() = strip("grid-to-dock", Button.Down, button = Button.Up)
    @Test fun openAppMenu() = strip("open-app-menu", button = Button.Menu)
    // The status pill over white, light and dark scenes, side by side (P18: too smoky on light backgrounds).
    @Test fun statusPillOnBackgrounds() {
        assumeTrue(only == "all" || only == "status-pill")
        val white = java.io.File.createTempFile("white", ".png").apply {
            val b = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)
            b.eraseColor(android.graphics.Color.WHITE)
            outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val scenes = listOf(
            "white" to dev.glasslauncher.data.Wallpaper(dev.glasslauncher.data.WallpaperKind.File, white.absolutePath),
            "light preset" to dev.glasslauncher.data.Wallpaper(dev.glasslauncher.data.WallpaperKind.Preset, "dawn"),
            "dark preset" to dev.glasslauncher.data.Wallpaper(dev.glasslauncher.data.WallpaperKind.Preset, "aurora"),
        )
        val crops = scenes.map { (label, wp) ->
            TvHarness.setUp(config = { it.copy(background = dev.glasslauncher.data.BackgroundMode.Wallpaper, wallpaperDark = wp, wallpaperLight = wp) })
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForHome(); compose.settle()
                val full = compose.onRoot().captureToImage().asAndroidBitmap()
                val pill = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("status-pill"), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
                val pad = 40
                val l = (pill.left.toInt() - pad).coerceAtLeast(0); val t = (pill.top.toInt() - pad).coerceAtLeast(0)
                label to android.graphics.Bitmap.createBitmap(full, l, t, (pill.width.toInt() + 2 * pad).coerceAtMost(full.width - l), (pill.height.toInt() + 2 * pad).coerceAtMost(full.height - t))
            }
        }
        val w = crops.maxOf { it.second.width } * 3; val h = crops.maxOf { it.second.height } * 3 + 30
        val sheet = android.graphics.Bitmap.createBitmap(w * crops.size, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet); canvas.drawColor(android.graphics.Color.DKGRAY)
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.YELLOW; textSize = 24f }
        crops.forEachIndexed { i, (label, b) ->
            canvas.drawBitmap(android.graphics.Bitmap.createScaledBitmap(b, b.width * 3, b.height * 3, true), (i * w).toFloat(), 30f, null)
            canvas.drawText(label, i * w + 8f, 24f, text)
        }
        val out = java.io.File("build/strips/status-pill-backgrounds.png").apply { parentFile?.mkdirs() }
        out.outputStream().use { sheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        println("strip: " + out.absolutePath)
    }

    @Test fun moveModeRight() = strip("move-mode-right", Button.Menu, Button.Down, Button.Select, button = Button.Right, frames = 12, stepMs = 28, columns = 6)
    @Test fun moveModeDown() = strip("move-mode-down", Button.Menu, Button.Down, Button.Select, button = Button.Down, frames = 12, stepMs = 28, columns = 6)
    @Test fun controlCenterOpen() = strip("control-center-open", Button.Up, Button.Up, Button.Up, button = Button.Select, frames = 28, stepMs = 28, columns = 7)
    // Exits: Back goes to the activity's dispatcher directly (the harness's Back key doesn't reach it).
    @Test fun controlCenterClose() {
        assumeTrue(only == "all" || only == "control-center-close")
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.settle()
            val shots = compose.frames({ scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() } }, 24, 24)
            println("strip: " + sheet("control-center-close", shots, 24, 6).absolutePath)
        }
    }
    // Focus moves to the next tray app: the tray glass dissolves with the backdrop (P23).
    @Test fun trayHeroSwap() {
        assumeTrue(only == "all" || only == "tray-hero-swap")
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome(); compose.settle()
            val shots = ArrayList<android.graphics.Bitmap>()
            compose.trayHeroSwap(shots)
            val every = 3
            println("strip: " + sheet("tray-hero-swap", shots.filterIndexed { i, _ -> i in 12..(12 + 12 * every) && i % every == 0 }, 16L * every, 5).absolutePath)
        }
    }
    @Test fun settingsPagePush() = strip("settings-page-push", Button.Down, Button.Down, Button.Right, Button.Right, Button.Select, button = Button.Select)
}
