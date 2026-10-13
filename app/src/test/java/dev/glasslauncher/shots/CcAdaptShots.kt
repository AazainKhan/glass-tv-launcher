package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.MainActivity
import dev.glasslauncher.data.BackgroundMode
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.ThemeMode
import dev.glasslauncher.data.Wallpaper
import dev.glasslauncher.data.WallpaperKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P137: Control Center over a light and over a dark backdrop, in both themes. Over light art (the YouTube Top Shelf
 * in the report) the glass stays light and the labels go dark; over dark art it is the usual white on dark glass.
 * Home's backdrop is a wallpaper preset here ("mist" is light, "aurora" dark), the same in either theme.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcAdaptShots {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private fun over(preset: String, theme: ThemeMode, name: String) {
        val wall = Wallpaper(WallpaperKind.Preset, preset)
        TvHarness.setUp(config = { it.copy(theme = theme, background = BackgroundMode.Wallpaper, wallpaperDark = wall, wallpaperLight = wall) })
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up)
            compose.press(Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            compose.stableImage().captureRoboImage(shot(name))
        }
    }

    @Test fun lightBackdropDarkTheme() = over("mist", ThemeMode.Dark, "cc-over-light-dark-theme")
    @Test fun lightBackdropLightTheme() = over("mist", ThemeMode.Light, "cc-over-light-light-theme")
    @Test fun darkBackdropDarkTheme() = over("aurora", ThemeMode.Dark, "cc-over-dark-dark-theme")
    @Test fun darkBackdropLightTheme() = over("aurora", ThemeMode.Light, "cc-over-dark-light-theme")
}
