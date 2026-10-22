package dev.glasslauncher.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.R
import dev.glasslauncher.home.CcSheetRef
import dev.glasslauncher.shots.TV
import dev.glasslauncher.shots.shot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P268: the notice card is Control Center's glass material (the tinted fallback here: a software canvas can't draw the baked sheet). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class NoticeGlassTest {
    @get:Rule val compose = createComposeRule()

    private fun card(notice: Notice, backdrop: Brush) {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(backdrop).padding(24.dp)) { NoticeCardContent(notice, CcSheetRef()) }
        }
        compose.waitForIdle()
    }

    private val device = Notice("Headphones", 3_500, false, "Connected · 80%", R.drawable.ic_headphones)
    private val plain = Notice("Sleep timer cancelled", 4_000)
    private val dark = Brush.linearGradient(listOf(Color(0xFF1B2B4A), Color(0xFF5A2A3C), Color(0xFF14161B)))
    private val bright = Brush.linearGradient(listOf(Color(0xFFE9D8A6), Color(0xFFF4F1DE), Color(0xFFBFD7EA)))

    @Test fun goldenDeviceCardOverADarkScene() { card(device, dark); compose.onRoot().captureRoboImage(shot("notice-card-device-dark")) }
    @Test fun goldenPlainCardOverABrightScene() { card(plain, bright); compose.onRoot().captureRoboImage(shot("notice-card-plain-bright")) }

    @Test fun theCardViewCarriesItsNoticeForTheTests() {
        val ref = CcSheetRef()
        val v = NoticeCard.build(androidx.test.core.app.ApplicationProvider.getApplicationContext(), device, ref)
        assertEquals(device, v.tag)
    }
}
