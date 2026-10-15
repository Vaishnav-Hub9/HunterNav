import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Optional local overrides (default camera, demo mode, display endpoints). Never commit real
// secrets. See README "Configuration" and local.properties.example.
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

        // Google Maps Platform (docs/google-maps.md). ONE key covers the Maps SDK (map),
        // Routes API v2 (traffic-aware two-wheeler routing) and Geocoding API (search +
        // reverse). Supplied locally via local.properties `google.maps.apiKey` (never
        // committed); the manifest meta-data com.google.android.geo.API_KEY carries it to
        // the Maps SDK, BuildConfig carries it to the REST providers. Left EMPTY, routing
        // and search report a clear configuration error and fail loudly.
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"" + localProps.getProperty("google.maps.apiKey", "") + "\"")
        buildConfigField("String", "GOOGLE_ROUTES_BASE_URL", "\"" + localProps.getProperty("google.routes.baseUrl", "https://routes.googleapis.com") + "\"")
        buildConfigField("String", "GOOGLE_GEOCODING_BASE_URL", "\"" + localProps.getProperty("google.geocoding.baseUrl", "https://maps.googleapis.com") + "\"")
        buildConfigField("double", "DEFAULT_LATITUDE", localProps.getProperty("default.latitude", "17.3850"))
        buildConfigField("double", "DEFAULT_LONGITUDE", localProps.getProperty("default.longitude", "78.4867"))
        buildConfigField("double", "DEFAULT_ZOOM", localProps.getProperty("default.zoom", "12.5"))
        // Default FALSE so a normal build never ships with the developer demo switch
        // pre-enabled; set demo.enabled=true in local.properties to develop demo mode.
        buildConfigField("boolean", "DEMO_MODE_ENABLED", localProps.getProperty("demo.enabled", "false"))
        // Handlebar display pipeline (see docs/display-pipeline.md):
        // WebSocket endpoint of the ESP32 display, and a dev switch that runs the display
        // navigation source as the offline simulator regardless of backend.
        buildConfigField("String", "DISPLAY_WS_URL", "\"" + localProps.getProperty("display.wsUrl", "ws://192.168.4.1:81/display") + "\"")
        buildConfigField("boolean", "DISPLAY_SIMULATOR", localProps.getProperty("display.simulator", "false"))
        // Handlebar display resolution (initial hardware target 240×240; configurable).
        buildConfigField("int", "DISPLAY_WIDTH", localProps.getProperty("display.width", "240"))
        buildConfigField("int", "DISPLAY_HEIGHT", localProps.getProperty("display.height", "240"))

        manifestPlaceholders["MAPS_API_KEY"] = localProps.getProperty("google.maps.apiKey", "")
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

    // Google Maps SDK (map rendering; key via manifest meta-data com.google.android.geo.API_KEY)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)

    // Networking / serialization
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
