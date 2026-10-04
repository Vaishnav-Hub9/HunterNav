package com.hunternav

import android.app.Application
import com.hunternav.di.AppContainer
import org.maplibre.android.MapLibre

/** App entry point. Initializes MapLibre (no API key needed for OpenFreeMap tiles) and DI. */
class HunterNavApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        container = AppContainer(this)
    }
}
