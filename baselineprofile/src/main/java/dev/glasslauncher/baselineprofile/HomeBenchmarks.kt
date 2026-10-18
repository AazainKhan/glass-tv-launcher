package dev.glasslauncher.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One benchmark per perf budget scenario (docs/PERF-PROGRAM.md in the workspace), on the real stick, compiled
 * as users get it (speed-profile from the Baseline Profile). Keys are injected at remote pace (Select through glass-press).
 * Results (JSON + Perfetto traces) land in /sdcard/Android/media/dev.glasslauncher.baselineprofile/.
 * Run: scripts/bench [name-regex]
 */
@RunWith(AndroidJUnit4::class)
class HomeBenchmarks {

    @get:Rule val rule = MacrobenchmarkRule()

    private val profile = CompilationMode.Partial(BaselineProfileMode.Require)

    private fun frames(setup: MacrobenchmarkScope.() -> Unit, measure: MacrobenchmarkScope.() -> Unit) =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = profile,
            iterations = ITERATIONS,
            setupBlock = setup,
            measureBlock = measure,
        )

    private fun startup(mode: CompilationMode, startupMode: StartupMode?, setup: MacrobenchmarkScope.() -> Unit, measure: MacrobenchmarkScope.() -> Unit) =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = mode,
            startupMode = startupMode,
            iterations = ITERATIONS,
            setupBlock = setup,
            measureBlock = measure,
        )

    // Cold: COLD kills Glass before setupBlock, so another app must already be in front (else the system
    // relaunches Home at once), and the notification listener must not rebind (it restarts the process). scripts/bench re-enables it on exit.
    private fun cold(mode: CompilationMode) {
        listener(allow = false)
        try {
            startup(mode, StartupMode.COLD, setup = {}) { startActivityAndWait(homeIntent); waitForGlass(); openOtherApp() }
        } finally { listener(allow = true) }
    }

    @Test fun startupCold() { primeOtherApp(); cold(profile) }
    @Test fun startupColdNoProfile() { primeOtherApp(); cold(CompilationMode.None()) }

    // Warm: the process is alive (its notification listener restarts it after a kill), the activity isn't.
    @Test fun startupWarm() = startup(profile, null, setup = {
        openOtherApp()
        device.executeShellCommand("am force-stop $PACKAGE")
        repeat(40) { if (device.executeShellCommand("pidof $PACKAGE").isNotBlank()) return@startup; Thread.sleep(100) }
    }) { startActivityAndWait(homeIntent); waitForGlass() }

    // Hot: the Home button from another app.
    @Test fun startupHot() = startup(profile, null, setup = { homeAtStart(); openOtherApp() }) { keys(KeyEvent.KEYCODE_HOME, gapMs = 1_500); waitForGlass() }

    @Test fun focusDock() = frames(setup = { homeAtStart() }) {
        keys(R, R, R, L, L, L)
    }

    @Test fun focusGrid() = frames(setup = { homeAtStart(); keys(D, D, 0, gapMs = 900) }) {
        keys(R, R, R, L, L, L)
    }

    @Test fun rowScroll() = frames(setup = { homeAtStart() }) {
        keys(D, D, D, U, U, U, gapMs = 900)
    }

    @Test fun controlCenter() = frames(setup = { homeAtStart() }) {
        overlay("CONTROL_CENTER"); Thread.sleep(1_200)
        keys(D, R, L, gapMs = 500); keys(B, gapMs = 1_200)
    }

    @Test fun appSwitcher() = frames(setup = { homeAtStart() }) {
        overlay("APP_SWITCHER"); Thread.sleep(1_200)
        keys(R, L, gapMs = 500); keys(B, gapMs = 1_200)
    }

    @Test fun appMenu() = frames(setup = { homeAtStart() }) {
        keys(KeyEvent.KEYCODE_MENU, gapMs = 900); keys(D, D, gapMs = 400); keys(B, gapMs = 900)
    }

    // Glass's frames while it opens the first dock app (the launch transition), until that app is in front.
    @Test fun appLaunch() = frames(setup = { homeAtStart() }) {
        select(); Thread.sleep(2_500)
    }

    private fun primeOtherApp() {
        shell("monkey -p $OTHER_APP -c android.intent.category.LEANBACK_LAUNCHER 1")
        Thread.sleep(3_000)
    }

    private fun listener(allow: Boolean) {
        // A component disable, not disallow_listener: the profile compile's package change re-binds a disallowed listener.
        shell("pm ${if (allow) "enable" else "disable"} $PACKAGE/.widgets.NowPlayingService")
        Thread.sleep(500)
    }

    private fun shell(cmd: String) = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(cmd)

    private companion object {
        const val ITERATIONS = 10
        const val U = KeyEvent.KEYCODE_DPAD_UP
        const val D = KeyEvent.KEYCODE_DPAD_DOWN
        const val L = KeyEvent.KEYCODE_DPAD_LEFT
        const val R = KeyEvent.KEYCODE_DPAD_RIGHT
        const val B = KeyEvent.KEYCODE_BACK
    }
}
