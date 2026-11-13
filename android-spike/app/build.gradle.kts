plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.bookah.photosspike"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bookah.photosspike"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-spike"
        ndk { abiFilters += "arm64-v8a" } // the Xiaomi 15T is arm64; drops about 100 MB of unused native libraries
    }

    // The model file is already compressed int8; do not compress it again so it can be copied quickly.
    androidResources {
        noCompress += listOf("onnx", "bin", "json")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Spike only: signed with the debug key. A real release uses keystore.properties like Kurobane.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(libs.onnxruntime.android)
}
