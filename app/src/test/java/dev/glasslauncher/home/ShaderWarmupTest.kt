package dev.glasslauncher.home

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P115: the shader warm-up runs once per install, not on every start. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShaderWarmupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aNewInstallTimeIsDue() {
        assertTrue(ShaderWarmup.due(updatedAt = 1_700_000_000L, warmedFor = -1L))
        assertTrue(ShaderWarmup.due(updatedAt = 1_700_000_999L, warmedFor = 1_700_000_000L))
    }

    @Test fun theSameInstallIsWarmedOnce() {
        assertFalse(ShaderWarmup.due(updatedAt = 1_700_000_000L, warmedFor = 1_700_000_000L))
    }

    @Test fun theLayerDrawsEveryVariantAndReportsDone() {
        var done = 0
        compose.setContent { ShaderWarmupLayer(sample = ImageBitmap(16, 16)) { done++ } }
        compose.waitUntil(5_000) { done > 0 }
        assertTrue(done == 1)
    }

    @Test fun withoutASceneNothingIsDrawnOrReported() {
        var done = 0
        compose.setContent { ShaderWarmupLayer(sample = null) { done++ } }
        compose.waitForIdle()
        assertTrue(done == 0)
    }
}
