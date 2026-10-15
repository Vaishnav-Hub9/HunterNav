package com.hunternav

import android.app.Application
import com.hunternav.di.AppContainer

/**
 * App entry point. No map SDK initialization is required: Google Maps reads its API key
 * from the manifest meta-data `com.google.android.geo.API_KEY` (populated from
 * local.properties `google.maps.apiKey` via manifestPlaceholders).
 */
class HunterNavApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
