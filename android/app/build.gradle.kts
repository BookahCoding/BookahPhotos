import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// A release sets these from its tag (-PappVersion=0.1.0) and repo (-PreleasesRepo=owner/name), as in Kurobane and Hikyaku.
// A local build never looks for updates (releasesRepo empty). The in-app updater stays off while the repo is private.
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "0.1.0"
val releasesRepo = (findProperty("releasesRepo") as String?).orEmpty()

android {
    namespace = "dev.bookah.photos"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.bookah.photos"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // 1.2.3 -> 10203: each release installs over the last as long as tags only go up.
        val (major, minor, patch) = appVersion.substringBefore('-').split('.').map { it.toInt() }.plus(listOf(0, 0)).take(3)
        require(minor < 100 && patch < 100) { "appVersion $appVersion: minor and patch must stay under 100" }
        versionCode = major * 10_000 + minor * 100 + patch
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "RELEASES_REPO", "\"$releasesRepo\"")
        // -PmapSmoke=1 compiles in a developer check (the map with made-up photos, no sign-in) so a MINIFIED build can be tried on an emulator; a published release never has it
        buildConfigField("boolean", "MAP_SMOKE", (findProperty("mapSmoke") != null).toString())
        // Every Android phone and tablet made in the last years is arm64; this keeps the app small.
        // Emulator test builds on a PC add x86_64 with -PallAbis (never for a release).
        ndk { abiFilters += if (findProperty("allAbis") != null) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Release signing comes from an untracked keystore.properties at the repo's android folder
    // (storeFile, storePassword, keyAlias, keyPassword); without it a release build uses the debug key.
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    if (keystore == null && releasesRepo.isNotEmpty()) error("releasesRepo is set but keystore.properties is missing")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(project(":design"))
    implementation(project(":feature-library"))
    implementation(project(":feature-viewer"))
    implementation(project(":feature-collections"))
    implementation(project(":feature-search"))
    implementation(project(":feature-map"))
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
