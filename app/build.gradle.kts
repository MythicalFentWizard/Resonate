plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val resonateVersion = providers.gradleProperty("resonateVersion").get()

// Android wants a single increasing integer, so major.minor.patch is folded
// into one: 1.6.0 becomes 10600. Two digits each for minor and patch, which
// keeps the number readable next to the versionName it came from.
val resonateVersionCode = resonateVersion.split(".")
    .also {
        require(it.size == 3) {
            "resonateVersion must be major.minor.patch, but was '$resonateVersion'"
        }
    }
    .let { (major, minor, patch) ->
        major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
    }

android {
    namespace = "com.exo.musicplayer"
    compileSdk = 35
    // Pinned to the build-tools package staged into .android-sdk by tools/setup-sdk.sh.
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.exo.musicplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = resonateVersionCode
        versionName = resonateVersion

        // yt-dlp ships a Python runtime and ffmpeg as native libraries. Building
        // every ABI would roughly triple the APK; arm64 covers every Android
        // phone shipped in the last several years.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // For BuildConfig.VERSION_NAME, shown in Settings.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // The Python runtime is exec'd from disk, so it must be extracted
        // rather than loaded straight out of a compressed APK.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)

    implementation(libs.ytdlp.library)
    implementation(libs.ytdlp.ffmpeg)
}
