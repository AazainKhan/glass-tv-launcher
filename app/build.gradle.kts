plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.glasslauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.glasslauncher"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "UPDATE_REPO", "\"AazainKhan/glass-launcher\"")
    }

    signingConfigs {
        // CI supplies a real key through secrets; local builds fall back to the debug key.
        val keystore = System.getenv("RELEASE_KEYSTORE")?.let { file(it) }
        if (keystore != null && keystore.exists()) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric screenshot tests (Roborazzi) need merged resources and the real manifest.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            it.maxHeapSize = "3g"
            // -Pstrips=all|<name> turns on the MotionStrips frame captures.
            it.systemProperty("strips", providers.gradleProperty("strips").getOrElse(""))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Every @Preview under dev.glasslauncher.preview (debug source set) becomes a screenshot test.
roborazzi {
    // Preview baselines live with the other screenshots (HomeShots pass explicit paths).
    outputDir.set(file("src/test/screenshots/previews"))
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    generateComposePreviewRobolectricTests {
        enable = true
        packages = listOf("dev.glasslauncher.preview")
        robolectricConfig = mapOf("sdk" to "[35]", "qualifiers" to "\"w960dp-h540dp-land-television-xhdpi\"")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.zxing.core)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.preview.scanner)
    testImplementation(libs.roborazzi.preview.scanner.support)
    debugImplementation(libs.compose.ui.test.manifest)
}
