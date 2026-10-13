package dev.glasslauncher.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.shots.Button
import dev.glasslauncher.shots.press
import dev.glasslauncher.system.ApkInfo
import dev.glasslauncher.system.SignerCheck
import dev.glasslauncher.system.StagedApk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The install card hosted for a staged APK: shown, Install hands the file to the install, anything else discards it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp")
class InstallConfirmHostTest {
    @get:Rule val compose = createComposeRule()

    private fun staged() = StagedApk(
        java.io.File.createTempFile("staged", ".apk"), "00",
        ApkInfo("com.example", "Example", "1.0", 1, 2048, listOf("ab".repeat(32)), SignerCheck.NotInstalled),
    )

    private fun host(s: StagedApk, log: MutableList<String>) {
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) InstallConfirmHost(
                Overlay.InstallConfirm(s, { log += "install" }, { log += "discard" }), active = true, close = { shown = false },
            )
        }
    }

    @Test fun installHandsOverWithoutDiscarding() {
        val log = mutableListOf<String>(); val s = staged()
        host(s, log)
        compose.onNodeWithTag("install-confirm").assertExists()
        compose.waitForIdle(); compose.press(Button.Select) // Install has focus first
        compose.waitForIdle()
        assertEquals(listOf("install"), log)
        s.file.delete()
    }

    @Test fun cancelDiscards() {
        val log = mutableListOf<String>(); val s = staged()
        host(s, log)
        compose.waitForIdle(); compose.press(Button.Down, Button.Select)
        compose.waitForIdle()
        assertEquals(listOf("discard"), log)
        assertTrue(s.file.delete())
    }
}
