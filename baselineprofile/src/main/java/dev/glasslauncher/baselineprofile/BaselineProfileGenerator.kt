package dev.glasslauncher.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which code Home runs at startup and while browsing, so release builds ship it
 * precompiled (no JIT jank on the first presses after an install). Needs a rooted device below
 * API 33, which the stick is. Run: scripts/bench profile (Gradle's connected task hangs on Magisk's su).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        startHome()
        browseHome()
        // Every perf-budget scenario (HomeBenchmarks), so none of them runs interpreted after an install.
        homeAtStart()
        keys(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT)
        keys(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, gapMs = 900)
        keys(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT)
        keys(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, gapMs = 900)
        overlay("CONTROL_CENTER"); Thread.sleep(1_200)
        keys(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, gapMs = 500)
        keys(KeyEvent.KEYCODE_BACK, gapMs = 1_200)
        overlay("APP_SWITCHER"); Thread.sleep(1_200)
        keys(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, gapMs = 500)
        keys(KeyEvent.KEYCODE_BACK, gapMs = 1_200)
        keys(KeyEvent.KEYCODE_MENU, gapMs = 900); keys(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, gapMs = 400)
        keys(KeyEvent.KEYCODE_BACK, gapMs = 900)
    }
}
