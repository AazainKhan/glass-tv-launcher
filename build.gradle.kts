plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.roborazzi) apply false
}

// Device safety: a connected test, a baseline-profile run or a macrobenchmark installs on EVERY device the default adb server
// sees, and the Fire TV stick sits there over Wi-Fi; one such run silently replaced the build under test on 2026-10-17.
// These tasks now refuse to run unless ANDROID_SERIAL names the one device they may touch.
gradle.taskGraph.whenReady {
    val touchesDevices = allTasks.filter { t ->
        val n = t.name.lowercase()
        n.startsWith("connected") || n.contains("baselineprofile") && (n.startsWith("generate") || n.startsWith("collect") || n.contains("connected")) ||
            n.contains("macrobenchmark") || n.endsWith("androidtest") && n.startsWith("connected")
    }
    if (touchesDevices.isNotEmpty() && System.getenv("ANDROID_SERIAL").isNullOrBlank()) {
        throw GradleException(
            "${touchesDevices.first().path} installs on every attached device. Set ANDROID_SERIAL to the one device it may use " +
                "(an emulator: see scripts/emulator env). The stick needs the stick lock and is Yin's to install on."
        )
    }
}
