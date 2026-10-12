package com.hunternav

import android.app.Application
import com.hunternav.core.util.DebugLog
import com.hunternav.di.AppContainer
import org.maplibre.android.MapLibre

/**
 * App entry point and SDK initialization.
 *
 * Mappls (primary map, current auth mechanism — Aug 2025): `Mappls.getInstance(context)` reads
 * the build-time authentication produced by the Mappls services Gradle plugin from the
 * `app` module's `.a.olf` + `.a.conf` files. Those files are tied to this package name and the
 * signing certificate, are never committed, and when absent at build time `BuildConfig.MAPPLS_ENABLED`
 * is false and this init is skipped entirely (the app runs on the MapLibre fallback map).
 *
 * MapLibre (fallback map + the map used for checkouts/CI): initialized unconditionally — it
 * needs no key and is cheap; the display pipeline's software renderer does not depend on it.
 */
class HunterNavApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.MAPPLS_ENABLED) {
            try {
                com.mappls.sdk.maps.Mappls.getInstance(this)
                DebugLog.d(TAG, "Mappls SDK initialized (auth files present at build time)")
            } catch (e: RuntimeException) {
                // Missing/invalid auth at runtime must never crash the app: the map screens
                // surface the SDK's error through OnMapReadyCallback.onMapError + watchdog.
                DebugLog.d(TAG, "Mappls SDK init failed: ${e.message}")
            }
        }

        MapLibre.getInstance(this)
        container = AppContainer(this)
    }

    private companion object {
        const val TAG = "HunterNavApp"
    }
}
