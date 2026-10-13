// Reference add-on and emulator test target: two Control Center tiles. Not packaged into :app.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.glasslauncher.addon.sample"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.glasslauncher.addon.sample"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":addon-api"))
}
