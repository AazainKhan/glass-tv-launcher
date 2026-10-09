package dev.glasslauncher.shots

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.glasslauncher.glass.Backdrop
import dev.glasslauncher.home.ControlCenterWindow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
/** Which screen Control Center's glass samples: Home's scene only when Home is really in front. */
class ControlCenterSceneTest {
    private val px = androidx.compose.ui.graphics.ImageBitmap(1, 1)
    private val scene = Backdrop(px, px, listOf(px), px, px, isLight = false)

    @After fun reset() { ControlCenterWindow.homeBackdrop = null; ControlCenterWindow.homeStarted = true }

    @Test fun glassInFrontAndStartedUsesHomeScene() =
        assertSame(scene, ControlCenterWindow.sceneFor(null, true, scene))

    @Test fun anotherAppInFrontCapturesEvenWithAStaleHomeScene() =
        assertNull(ControlCenterWindow.sceneFor("com.netflix.ninja", true, scene))

    @Test fun glassStoppedCaptures() =
        assertNull(ControlCenterWindow.sceneFor(null, false, scene))

    @Test fun lifecycleClearsHomeBackdropOnStopAndRestoresOnStart() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.CREATED
        owner.lifecycle.addObserver(ControlCenterWindow.homeLifecycleObserver { scene })
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertSame(scene, ControlCenterWindow.homeBackdrop)
        assertTrue(ControlCenterWindow.homeStarted)
        owner.registry.currentState = Lifecycle.State.CREATED // an app came in front: ON_PAUSE, ON_STOP
        assertNull(ControlCenterWindow.homeBackdrop)
        assertFalse(ControlCenterWindow.homeStarted)
        owner.registry.currentState = Lifecycle.State.STARTED
        assertSame(scene, ControlCenterWindow.homeBackdrop)
    }
}
