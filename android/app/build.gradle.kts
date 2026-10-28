plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release builds are signed when CI provides these; otherwise the debug key is
// used so `assembleRelease` still produces an installable APK.
val ciKeystore = System.getenv("POCKETDESK_KEYSTORE")

android {
    namespace = "dev.pocketdesk"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.pocketdesk"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("pocketdeskVersionCode") as String?)?.toInt() ?: 4
        versionName = (project.findProperty("pocketdeskVersionName") as String?) ?: "0.3.0"
    }

    if (!ciKeystore.isNullOrBlank()) {
        signingConfigs {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("POCKETDESK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("POCKETDESK_KEY_ALIAS")
                keyPassword = System.getenv("POCKETDESK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (!ciKeystore.isNullOrBlank()) {
                signingConfigs.getByName("ci")
            } else {
                signingConfigs.getByName("debug")
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // The app is sideloaded; Play-only gates would just add noise.
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity:1.9.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR pairing: CameraX preview + on-device barcode decoding.
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
