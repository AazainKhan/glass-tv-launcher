package dev.glasslauncher.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** P130: the shelf title must not show for the new app before its background, even for the frame the gate animation lags. */
class TitleGateTest {
    @Test fun aNotReadySceneHidesTheTitleEvenWhileTheAnimatedGateIsStillOpen() {
        assertEquals(0f, titleGateAlpha(ready = false, gate = 1f), 0f)
        assertEquals(0f, titleGateAlpha(ready = false, gate = 0.6f), 0f)
    }

    @Test fun aReadySceneLetsTheGateFadeTheTitleIn() {
        assertEquals(0.4f, titleGateAlpha(ready = true, gate = 0.4f), 0f)
        assertEquals(1f, titleGateAlpha(ready = true, gate = 1f), 0f)
    }
}
