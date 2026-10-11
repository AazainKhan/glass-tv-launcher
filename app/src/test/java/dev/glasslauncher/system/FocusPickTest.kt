package dev.glasslauncher.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P87: the app owns Back only once one of its application windows has input focus. */
class FocusPickTest {
    private fun win(focused: Boolean, app: Boolean = true, pkg: String? = "com.netflix.ninja") = FocusPick.Win(focused, app, pkg)

    @Test fun aStartingWindowWithoutFocusIsNotTheAppHavingBack() {
        // The stick's trace: the app's window exists (+324 ms) but "no window has focus": the system holds Back.
        assertNull(FocusPick.focusedAppPackage(listOf(win(focused = false), win(focused = false, pkg = "dev.glasslauncher"))))
    }

    @Test fun theFocusedApplicationWindowNamesTheOwner() {
        assertEquals("com.netflix.ninja", FocusPick.focusedAppPackage(listOf(win(false, pkg = "dev.glasslauncher"), win(true))))
    }

    @Test fun aFocusedNonApplicationWindowIsIgnored() {
        assertNull(FocusPick.focusedAppPackage(listOf(win(true, app = false, pkg = "com.android.systemui"))))
    }
}
