import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Mappls authentication (current mechanism, Aug 2025): the console-issued
// `com.hunternav.a.olf` + `com.hunternav.a.conf` files sit in this module's directory and are
// processed at build time by the Mappls services plugin. They are per-package + per-signing-
// certificate, MUST NEVER be committed (see .gitignore), and their presence switches the app
// to the real Mappls map. Absent ⇒ MapLibre/OpenFreeMap stays the map so the project never
// breaks for other checkouts or CI.
val mapplsAuthFilesPresent = run {
    val names = projectDir.listFiles()?.map { it.name } ?: emptyList()
    names.any { it.endsWith(".a.olf") } && names.any { it.endsWith(".a.conf") }
}

if (mapplsAuthFilesPresent) {
    // Plugin is on the buildscript classpath from the root build.gradle.kts.
    apply(plugin = "com.mappls.services.android")
} else {
    logger.lifecycle("Mappls auth files (*.a.olf/*.a.conf) not found in app/ — using MapLibre fallback map")
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
        // Routing is Mappls-only — there is NO OSRM/other-provider fallback: without
        // mappls.accessToken route calculation fails loudly with setup instructions.
        buildConfigField("String", "GEOCODER_BASE_URL", "\"${localProps.getProperty("geocoder.baseUrl", "https://nominatim.openstreetmap.org/")}\"")
        // Mappls REST APIs (see docs/mappls.md). One Console access token covers routing and
        // destination search: set mappls.accessToken in local.properties (never committed —
        // Mappls Console credentials are personal). Left EMPTY (only for keyless CI builds),
        // routing reports a clear configuration error and search stays on Nominatim.
        buildConfigField("String", "MAPPLS_ACCESS_TOKEN", "\"" + localProps.getProperty("mappls.accessToken", "") + "\"")
        buildConfigField("String", "MAPPLS_SEARCH_BASE_URL", "\"${localProps.getProperty("mappls.search.baseUrl", "https://search.mappls.com/")}\"")
        buildConfigField("String", "MAPPLS_ROUTING_BASE_URL", "\"${localProps.getProperty("mappls.routing.baseUrl", "https://route.mappls.com/")}\"")
        // Two-wheeler routing by default — the profile HunterNav is built for (documented
        // values: driving | biking | walking | trucking).
        buildConfigField("String", "MAPPLS_ROUTING_PROFILE", "\"${localProps.getProperty("mappls.routing.profile", "biking")}\"")
        buildConfigField("String", "MAP_STYLE_URL", "\"${localProps.getProperty("map.styleUrl", "https://tiles.openfreemap.org/styles/liberty")}\"")
        buildConfigField("double", "DEFAULT_LATITUDE", localProps.getProperty("default.latitude", "17.3850"))
        buildConfigField("double", "DEFAULT_LONGITUDE", localProps.getProperty("default.longitude", "78.4867"))
        buildConfigField("double", "DEFAULT_ZOOM", localProps.getProperty("default.zoom", "12.5"))
        // Default FALSE so a normal build never ships with the developer demo switch
        // pre-enabled; set demo.enabled=true in local.properties to develop demo mode.
        buildConfigField("boolean", "DEMO_MODE_ENABLED", localProps.getProperty("demo.enabled", "false"))
        // Google Navigation SDK + Map Tiles API key. Supplied locally via local.properties
        // (never committed). Empty string = display pipeline runs in degraded mode.
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"" + localProps.getProperty("google.maps.apiKey", "") + "\"")
        manifestPlaceholders["MAPS_API_KEY"] = localProps.getProperty("google.maps.apiKey", "")
        // Handlebar display pipeline (see docs/display-pipeline.md):
        // WebSocket endpoint of the ESP32 display, and a dev switch that runs the display
        // navigation source as the offline simulator regardless of API key.
        buildConfigField("String", "DISPLAY_WS_URL", "\"" + localProps.getProperty("display.wsUrl", "ws://192.168.4.1:81/display") + "\"")
        buildConfigField("boolean", "DISPLAY_SIMULATOR", localProps.getProperty("display.simulator", "false"))
        // True when the Mappls auth files were present at build time ⇒ Mappls Maps is the
        // primary map. False ⇒ the app uses MapLibre + OpenFreeMap (no key needed).
        buildConfigField("boolean", "MAPPLS_ENABLED", mapplsAuthFilesPresent.toString())
        // Handlebar display resolution (initial hardware target 240×240; configurable).
        buildConfigField("int", "DISPLAY_WIDTH", localProps.getProperty("display.width", "240"))
        buildConfigField("int", "DISPLAY_HEIGHT", localProps.getProperty("display.height", "240"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // Required by the Google Navigation SDK AAR (java.time APIs on minSdk 26).
        isCoreLibraryDesugaringEnabled = true
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

    // Mappls Maps SDK (primary map when the local auth files are present; the classes are
    // compile-safe without them — Mappls.getInstance() is only called when MAPPLS_ENABLED).
    implementation(libs.mappls.android)

    // MapLibre + OpenFreeMap (always-available fallback map, and the map for checkouts/CI
    // without Mappls credentials)
    implementation(libs.maplibre.android)
    implementation(libs.play.services.location)

    // Google Navigation SDK (custom-experience navigation data feed for the display pipeline)
    implementation(libs.navigation.sdk)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Networking / serialization
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
