import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing. Locally: a git-ignored `keystore.properties` next to settings.gradle.kts
 * (storeFile, storePassword, keyAlias, keyPassword). In CI: the RELEASE_* environment
 * variables, filled from GitHub secrets. Without either, release builds fall back to the
 * debug key so `assembleRelease` still works on any machine (don't publish those).
 */
val releaseSigning: Map<String, String?>? = run {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        val p = Properties().apply { file.inputStream().use { load(it) } }
        mapOf(
            "storeFile" to p.getProperty("storeFile"),
            "storePassword" to p.getProperty("storePassword"),
            "keyAlias" to p.getProperty("keyAlias"),
            "keyPassword" to p.getProperty("keyPassword"),
        )
    } else {
        System.getenv("RELEASE_STORE_FILE")?.let {
            mapOf(
                "storeFile" to it,
                "storePassword" to System.getenv("RELEASE_STORE_PASSWORD"),
                "keyAlias" to System.getenv("RELEASE_KEY_ALIAS"),
                "keyPassword" to System.getenv("RELEASE_KEY_PASSWORD"),
            )
        }
    }
}

android {
    namespace = "com.typepilot.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.typepilot.android"
        minSdk = 28
        targetSdk = 36
        // Phones (and Apple-silicon emulators) are arm64: shipping only that keeps the native AI runtime to one copy.
        ndk { abiFilters += listOf("arm64-v8a") }
        versionCode = 4
        versionName = "1.2.0"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile")!!)
                storePassword = releaseSigning["storePassword"]
                keyAlias = releaseSigning["keyAlias"]
                keyPassword = releaseSigning["keyPassword"]
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.litertlm)
    implementation(libs.pdfbox.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
