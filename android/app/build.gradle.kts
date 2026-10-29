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
        // Separate package so the legacy build can live next to the normal app
        // on a device that is later upgraded, and so each channel has its own
        // update manifest.
        applicationId = "dev.pocketdesk.legacy"
        minSdk = 21
        targetSdk = 35
        versionCode = (project.findProperty("pocketdeskVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("pocketdeskVersionName") as String?) ?: "legacy-dev"

        // The legacy channel publishes to a fixed prerelease tag, never to
        // `latest`, so normal installs are unaffected.
        buildConfigField(
            "String",
            "UPDATE_MANIFEST",
            "\"https://github.com/pdev-labs/pocketdesk/releases/download/legacy-latest/latest-legacy.json\"",
        )
        buildConfigField(
            "String",
            "UPDATE_PAGE",
            "\"https://github.com/pdev-labs/pocketdesk/releases/tag/legacy-latest\"",
        )
    }

    if (!ciKeystore.isNullOrBlank()) {
        signingConfigs {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("POCKETDESK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("POCKETDESK_KEY_ALIAS")
                keyPassword = System.getenv("POCKETDESK_KEY_PASSWORD")
                // The release key is a PKCS12 bundle; the same password protects
                // the store and the key (it is generated with openssl, so no JDK
                // is needed to create or rotate it).
                storeType = "PKCS12"
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
