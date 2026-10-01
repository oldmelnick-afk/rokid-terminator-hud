plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rocketglasses.terminatorpreview"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.rocketglasses.terminatorpreview"
        minSdk = 28
        targetSdk = 32
        versionCode = 7
        versionName = "0.7-profile-deck"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
}
