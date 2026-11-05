import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Kept outside the repo; release builds are left unsigned when it is missing.
val releaseSigningFile = File(System.getProperty("user.home"), ".android/keystores/zn-keyboard-release.properties")
val releaseSigning = releaseSigningFile
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "dev.zain.znkeyboard"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.zain.znkeyboard"
        minSdk = 36
        targetSdk = 36
        versionCode = 13
        versionName = "1.2.0"

        // Personal arm64 phones only; keeps LiteRT and ML Kit native libraries from multiplying APK size.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }


    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseSigningFile.parentFile.resolve(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Stored uncompressed so the ~630 MB Bark model isn't inflated through a zip stream at load.
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation(libs.onnxruntime.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.glide)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.mlkit.text.recognition)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
