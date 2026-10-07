package dev.glasslauncher.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup and D-pad browsing on the real device, with and without the Baseline Profile.
 * Results (JSON + traces) land in baselineprofile/build/outputs/connected_android_test_additional_output/.
 * Run: scripts/bench
 */
@RunWith(AndroidJUnit4::class)
class HomeBenchmarks {

    @get:Rule val rule = MacrobenchmarkRule()

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) { startHome() }

    private fun browse(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        iterations = 3,
        setupBlock = { startHome() },
    ) { browseHome() }

    @Test fun startupNoCompilation() = startup(CompilationMode.None())
    @Test fun startupBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))
    @Test fun browseNoCompilation() = browse(CompilationMode.None())
    @Test fun browseBaselineProfile() = browse(CompilationMode.Partial(BaselineProfileMode.Require))
}
