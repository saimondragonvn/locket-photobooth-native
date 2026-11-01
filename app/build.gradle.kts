plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.elingo.locket"
    compileSdk = 35

    val versionTxt = rootProject.file("VERSION.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty().ifBlank { "1.0.1" }
    val versionParts = versionTxt.split(".")
    val computedVersionCode = (versionParts.getOrNull(0)?.toIntOrNull() ?: 1) * 10000 +
        (versionParts.getOrNull(1)?.toIntOrNull() ?: 0) * 100 +
        (versionParts.getOrNull(2)?.toIntOrNull() ?: 1)
    val resolvedVersionCode = (project.findProperty("appVersionCode") as? String)?.toIntOrNull() ?: computedVersionCode
    val resolvedVersionName = (project.findProperty("appVersionName") as? String)?.takeIf { it.isNotBlank() } ?: versionTxt
    val resolvedGitSha = (project.findProperty("gitCommitSha") as? String)?.takeIf { it.isNotBlank() } ?: "local"

    defaultConfig {
        applicationId = "com.elingo.locket"
        minSdk = 26
        targetSdk = 34
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        buildConfigField("String", "GIT_COMMIT_SHA", "\"$resolvedGitSha\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val ks = file("release.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "locket123456"
                keyAlias = System.getenv("KEY_ALIAS") ?: "locketphotobooth"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "locket123456"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (file("release.keystore").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Native Hardware CameraX (60FPS Preview + 1:1 ViewPort + Hardware H.264 Recorder)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    // AndroidX Media3 (GPU Video Transformer + Shared ExoPlayer Feed)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)

    // Room Persistent Outbox Queue
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager, DataStore, Security Crypto, Exif & Futures
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.concurrent.futures.ktx)

    // Networking, Serialization, Coroutines & Image/Video Loading
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
}
