package dev.glasslauncher.baselineprofile

import android.content.Intent
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

const val PACKAGE = "dev.glasslauncher"

/** Any other app to put in front of Glass (cold/hot starts, app launch). VLC is light and always installed on the stick. */
const val OTHER_APP = "org.videolan.vlc"

/**
 * Presses keys by injection (UiAutomation), `0` meaning a pause: each key is followed by [gapMs]. D-pad, Back,
 * Menu and Home work injected; only Select needs real input events (see [select]).
 */
fun MacrobenchmarkScope.keys(vararg codes: Int, gapMs: Long = 600) {
    for (c in codes) { if (c != 0) device.pressKeyCode(c); Thread.sleep(gapMs) }
}

/**
 * Select through tools/uinput/glass-press (a virtual USB keypad, real input events): an injected Select doesn't
 * click Glass's tiles on Fire OS. scripts/bench pushes it. 96 is evdev KEY_KPENTER as the remote's layout maps it.
 */
fun MacrobenchmarkScope.select() {
    device.executeShellCommand("/data/local/tmp/glass-press 120 w1000 96")
}

/** Opens one of MainActivity's overlay actions (CONTROL_CENTER, APP_SWITCHER), as the remote's buttons do. */
fun MacrobenchmarkScope.overlay(action: String) {
    device.executeShellCommand("am start -n $PACKAGE/.MainActivity -a $PACKAGE.action.$action")
}

fun MacrobenchmarkScope.waitForGlass() {
    device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 5_000)
    device.waitForIdle()
}

/** Home with focus back at its start (Home on Home resets it). */
fun MacrobenchmarkScope.homeAtStart() {
    keys(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_HOME, gapMs = 1_000)
    waitForGlass()
}

fun MacrobenchmarkScope.openOtherApp() {
    device.executeShellCommand("monkey -p $OTHER_APP -c android.intent.category.LEANBACK_LAUNCHER 1")
    device.wait(Until.hasObject(By.pkg(OTHER_APP).depth(0)), 8_000)
    Thread.sleep(1_000)
}

val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(PACKAGE)

/** Starts Home and waits until the first tile is on screen. */
fun MacrobenchmarkScope.startHome() {
    pressHome()
    startActivityAndWait()
    waitForGlass()
}

/** The everyday Home journey on a remote: along the dock, into the grid, up to the featured row and back. */
fun MacrobenchmarkScope.browseHome() {
    repeat(3) { device.pressDPadRight(); Thread.sleep(250) }
    repeat(3) { device.pressDPadLeft(); Thread.sleep(250) }
    repeat(2) { device.pressDPadDown(); Thread.sleep(400) }
    repeat(2) { device.pressDPadRight(); Thread.sleep(250) }
    repeat(3) { device.pressDPadUp(); Thread.sleep(500) }
    repeat(2) { device.pressDPadRight(); Thread.sleep(300) }
    device.pressDPadDown(); Thread.sleep(500)
    device.waitForIdle()
}
