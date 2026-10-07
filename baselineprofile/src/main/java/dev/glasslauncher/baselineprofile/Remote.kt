package dev.glasslauncher.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

const val PACKAGE = "dev.glasslauncher"

/** Starts Home and waits until the first tile is on screen. */
fun MacrobenchmarkScope.startHome() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 5_000)
    device.waitForIdle()
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
