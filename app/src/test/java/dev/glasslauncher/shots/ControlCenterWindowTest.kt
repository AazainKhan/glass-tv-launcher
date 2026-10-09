package dev.glasslauncher.shots

import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.home.CcMorph
import dev.glasslauncher.home.ControlCenterWindow
import dev.glasslauncher.system.RemoteKeysService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/**
 * The Control Center overlay window's lifecycle on the JVM: whenever it is logically closed it must not be
 * attached (or at least must neither take focus nor touches), whatever the order of show / hide / toggle.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class ControlCenterWindowTest {
    private lateinit var window: ControlCenterWindow
    private lateinit var service: RemoteKeysService

    @Before fun setUp() {
        TvHarness.setUp()
        service = Robolectric.buildService(RemoteKeysService::class.java).create().get()
        // The shadow's success path hands over a fake buffer; a failed capture takes the same attach path.
        shadowOf(service).setTakeScreenshotErrorCode(1)
        window = service.controlCenter
    }

    @After fun tearDown() {
        window.hide()
        idle(CcMorph.CLOSE_MS + 500L)
        ControlCenterWindow.homeBackdrop = null
        ControlCenterWindow.pillBounds = null
        TvHarness.restoreClock()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    /** The Control Center views the window manager really has attached (not the window's own bookkeeping). */
    private fun attached(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val wm = global.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val names = global.getMethod("getViewRootNames").invoke(wm) as Array<String>
        return names.mapNotNull { n -> runCatching { global.getMethod("getRootView", String::class.java).invoke(wm, n) as? View }.getOrNull() }
            .filter { (it.layoutParams as? WindowManager.LayoutParams)?.title == "Glass Control Center" }
    }

    private fun takesInput(v: View): Boolean {
        val f = (v.layoutParams as WindowManager.LayoutParams).flags
        return f and (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
    }

    private fun overHome() {
        val px = androidx.compose.ui.graphics.ImageBitmap(1, 1)
        ControlCenterWindow.homeBackdrop = dev.glasslauncher.glass.Backdrop(px, px, listOf(px), px, px, isLight = false)
    }

    @Test fun showAttachesAndCloseDetaches() {
        overHome()
        window.show(); idle(50)
        assertEquals(1, attached().size)
        window.hide()
        assertFalse("input stops at once", attached().any { takesInput(it) })
        idle(CcMorph.CLOSE_MS + 100L)
        assertEquals(0, attached().size)
        assertFalse(ControlCenterWindow.open)
    }

    // Over another app the window waits up to ~120 ms for a screen capture before it attaches. A close
    // asked for in that gap (Home press, service teardown, a second toggle) was dropped: view was still null.
    @Test fun closeAskedWhileTheCaptureIsPendingIsHonoured() {
        ControlCenterWindow.homeBackdrop = null
        window.show()
        window.hide()
        idle(1000)
        assertEquals("closed before it ever attached, yet a window is up", 0, attached().size)
        assertFalse(ControlCenterWindow.open)
    }

    @Test fun secondToggleWhileTheCaptureIsPendingCloses() {
        ControlCenterWindow.homeBackdrop = null
        window.toggle()
        window.toggle()
        idle(1000)
        assertEquals("toggled open then closed, yet a window is up", 0, attached().size)
    }

    @Test fun serviceTeardownWhileTheCaptureIsPendingLeavesNoWindow() {
        ControlCenterWindow.homeBackdrop = null
        window.show()
        window.hide() // RemoteKeysService.onDestroy
        idle(1000)
        assertEquals(0, attached().size)
    }

    @Test fun reopenDuringTheExitKeepsOneInputTakingWindow() {
        overHome()
        window.show(); idle(50)
        window.hide(); idle(100)
        window.show(); idle(CcMorph.CLOSE_MS + 200L)
        val w = attached()
        assertEquals(1, w.size)
        assertTrue(takesInput(w[0]))
        window.hide(); idle(CcMorph.CLOSE_MS + 100L)
        assertEquals(0, attached().size)
    }

    @Test fun closeTwiceAndReopenAfterCloseAreIdempotent() {
        overHome()
        window.show(); idle(50)
        window.hide(); window.hide()
        idle(CcMorph.CLOSE_MS + 100L)
        assertEquals(0, attached().size)
        window.hide()
        window.show(); idle(50)
        assertEquals(1, attached().size)
        window.hide(); idle(CcMorph.CLOSE_MS + 100L)
        assertEquals(0, attached().size)
    }
}
