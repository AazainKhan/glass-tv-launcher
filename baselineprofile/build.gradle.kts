// Baseline Profile generation and Macrobenchmarks for :app, run on a connected device (the stick).
//   ./gradlew :app:generateBaselineProfile              -> app/src/release/generated/baselineProfiles/
//   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest   (or scripts/bench)
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.glasslauncher.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
