package dev.glasslauncher.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.apps.InstallState
import dev.glasslauncher.home.InstallBadge
import dev.glasslauncher.home.installSpoken
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class InstallBadgeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theBadgeAtQueuedHalfwayAndNearlyDone() {
        compose.setContent {
            Row(Modifier.background(Color(0xFF3A3F55)).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(0f, 0.4f, 0.9f).forEach { InstallBadge(InstallState(it)) }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("install-badge"))
    }

    @Test fun whatAScreenReaderSays() {
        assertEquals("updating", installSpoken(InstallState(0f)))
        assertEquals("updating, 40 percent", installSpoken(InstallState(0.4f)))
    }
}
