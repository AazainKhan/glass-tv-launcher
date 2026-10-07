package dev.glasslauncher.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which code Home runs at startup and while browsing, so release builds ship it
 * precompiled (no JIT jank on the first presses after an install). Needs a rooted device below
 * API 33, which the stick is. Run: ./gradlew :app:generateBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        startHome()
        browseHome()
    }
}
