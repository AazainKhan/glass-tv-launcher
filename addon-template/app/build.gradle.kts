plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myaddon"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.myaddon" // change this
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
