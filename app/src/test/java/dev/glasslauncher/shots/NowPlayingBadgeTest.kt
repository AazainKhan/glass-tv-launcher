package dev.glasslauncher.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.widgets.Like
import dev.glasslauncher.widgets.NowPlaying
import dev.glasslauncher.widgets.NowPlayingHero
import androidx.core.graphics.drawable.toBitmap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P235: the playing app's icon rides the cover as a badge; AirPlay has its own glyph; an app whose icon can't be read shows none. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class NowPlayingBadgeTest {
    @get:Rule val compose = createComposeRule()

    private class Spy : NowPlaying.Controls {
        override fun play() {}; override fun pause() {}; override fun next() {}; override fun previous() {}
    }

    private fun badges(pkg: String): Int {
        val item = NowPlaying("Song", "Artist", "Album", pkg, null, durationMs = 0, positionMs = 0, positionAt = 0, speed = 1f, playing = false, controller = Spy(), canSeek = false, like = Like.None)
        compose.setContent { Box(Modifier.fillMaxSize().padding(16.dp)) { NowPlayingHero(item, false, androidx.compose.runtime.remember { FocusRequester() }, androidx.compose.runtime.remember { FocusRequester() }) } }
        compose.waitForIdle()
        return compose.onAllNodes(hasTestTag("np-source-badge")).fetchSemanticsNodes().size
    }

    @Test fun anInstalledAppsIconIsTheBadge() {
        val pm = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
        val info = android.content.pm.PackageInfo().apply { packageName = "com.test.music"; applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.test.music" } }
        org.robolectric.Shadows.shadowOf(pm).installPackage(info)
        org.robolectric.Shadows.shadowOf(pm).setApplicationIcon("com.test.music", android.graphics.drawable.ColorDrawable(0xFF1DB954.toInt()))
        assertEquals(1, badges("com.test.music"))
    }
    @Test fun airPlayHasItsOwnBadge() = assertEquals(1, badges("airplay"))
    @Test fun anUnknownAppHasNone() = assertEquals(0, badges("com.example.not.installed"))

    @Test fun goldenHeroWithTheBadge() {
        val pm = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
        val info = android.content.pm.PackageInfo().apply { packageName = "com.test.music"; applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.test.music" } }
        org.robolectric.Shadows.shadowOf(pm).installPackage(info)
        org.robolectric.Shadows.shadowOf(pm).setApplicationIcon("com.test.music", android.graphics.drawable.ColorDrawable(0xFF1DB954.toInt()))
        val item = NowPlaying("Saltwater", "Chicane", "Far from the Maddening Crowds", "com.test.music", null, durationMs = 0, positionMs = 0, positionAt = 0, speed = 1f, playing = true, controller = Spy(), canSeek = false, like = Like.None)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF20232B)).padding(40.dp)) { NowPlayingHero(item, false, androidx.compose.runtime.remember { FocusRequester() }, androidx.compose.runtime.remember { FocusRequester() }) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("now-playing-source-badge"))
    }
}
