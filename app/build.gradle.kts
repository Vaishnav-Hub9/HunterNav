import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Optional local overrides (OSRM endpoint, default camera, demo mode). Never commit real secrets.
// See README "Configuration" and local.properties.example.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.hunternav"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hunternav"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Development endpoints and defaults. Override via local.properties (local.properties.example).
        buildConfigField("String", "OSRM_BASE_URL", "\"${localProps.getProperty("osrm.baseUrl", "https://router.project-osrm.org/")}\"")
        buildConfigField("String", "GEOCODER_BASE_URL", "\"${localProps.getProperty("geocoder.baseUrl", "https://nominatim.openstreetmap.org/")}\"")
        buildConfigField("String", "MAP_STYLE_URL", "\"${localProps.getProperty("map.styleUrl", "https://tiles.openfreemap.org/styles/liberty")}\"")
        buildConfigField("double", "DEFAULT_LATITUDE", localProps.getProperty("default.latitude", "17.3850"))
        buildConfigField("double", "DEFAULT_LONGITUDE", localProps.getProperty("default.longitude", "78.4867"))
        buildConfigField("double", "DEFAULT_ZOOM", localProps.getProperty("default.zoom", "12.5"))
        buildConfigField("boolean", "DEMO_MODE_ENABLED", localProps.getProperty("demo.enabled", "true"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    // Maps (OpenFreeMap-compatible vector tiles) and location
    implementation(libs.maplibre.android)
    implementation(libs.play.services.location)

    // Networking / serialization
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
