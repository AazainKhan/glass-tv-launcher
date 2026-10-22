// P239 spike (not for merge): Home's dock drawn natively (Rust, rust/) vs the same scene in Compose, for an A/B on the stick.
// Its own package, so it never replaces Glass. The .so files in src/main/jniLibs come from: cd rust && cargo ndk -t armeabi-v7a -t arm64-v8a -P 28 -o ../src/main/jniLibs build --release
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.glasslauncher.p239spike"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.glasslauncher.p239spike"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
}
