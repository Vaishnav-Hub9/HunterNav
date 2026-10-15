package com.hunternav.ui.map

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.MapView

/**
 * Google Maps SDK MapView embedded into Compose with correct lifecycle forwarding
 * (onCreate/onStart/onResume/onPause/onStop/onDestroy). The Maps SDK requires
 * `onCreate(Bundle)` before the other events, so the observer starts there.
 */
@Composable
fun GoogleMapView(
    modifier: Modifier = Modifier,
    onMapViewReady: (MapView) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapView(context).also { onMapViewReady(it) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        var created = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> {
                    mapView.onCreate(Bundle())
                    created = true
                }
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    if (created) mapView.onDestroy()
                    created = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (created) mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
    )
}

/**
 * The app's map surface — Google Maps, the only map engine since the Google-only
 * migration (docs/google-maps.md). Screens talk to [MapSurface] only, so this
 * factory is the entire engine-selection surface.
 */
@Composable
fun HunterNavMapSurface(
    modifier: Modifier = Modifier,
    onSurfaceReady: (MapSurface) -> Unit,
) {
    val context = LocalContext.current
    GoogleMapView(
        modifier = modifier,
        onMapViewReady = { mv -> onSurfaceReady(GoogleMapsSurface(context, mv)) },
    )
}
