package com.hunternav.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hunternav.ui.rememberAppViewModel
import com.hunternav.BuildConfig
import com.hunternav.di.AppContainer
import com.hunternav.ui.AppViewModel
import com.hunternav.ui.components.HunterLogo
import com.hunternav.ui.components.MapLoadErrorOverlay
import com.hunternav.ui.components.PrimaryActionButton
import com.hunternav.ui.map.HunterNavMapSurface
import com.hunternav.ui.map.MapSurface
import com.hunternav.ui.theme.Cobalt
import com.hunternav.ui.theme.CobaltTint
import com.hunternav.ui.theme.InkSecondary
import com.hunternav.ui.theme.IvoryElevated

/** Home: map preview + destination search + recents + start action. */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onDestinationPicked: () -> Unit,
    onStartNavigation: () -> Unit,
) {
    val viewModel: AppViewModel = rememberAppViewModel(container)

    val destination by viewModel.destination.collectAsState()
    val locationStatus by viewModel.locationStatus.collectAsState()
    val demoMode by viewModel.demoMode.collectAsState()
    // Real selection history (never a built-in placeholder list): every entry is a place the
    // user actually picked, with the exact coordinate that was selected.
    val recents by viewModel.recents.collectAsState()

    var mapSurface by remember { mutableStateOf<MapSurface?>(null) }
    var mapError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.onPermissionResult(granted = fine || coarse, coarseOnly = fine && !coarse)
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    DisposableEffect(Unit) {
        viewModel.startBrowseTracking()
        onDispose { viewModel.stopBrowseTracking() }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HunterNavMapSurface(
            modifier = Modifier.fillMaxSize(),
            onSurfaceReady = { surface ->
                mapSurface = surface
                surface.loadStyle(
                    onReady = {
                        mapError = null
                        viewModel.engine.state.value.currentLocation?.let { surface.showUserLocation(it) }
                        viewModel.onMapReady()
                    },
                    onError = { mapError = it },
                )
            },
        )

        // Map/style load failure: visible error + retry instead of a blank screen (spec §1).
        mapError?.let { message ->
            MapLoadErrorOverlay(message = message, onRetry = { mapSurface?.retryStyle() })
        }

        // Keep the current-location pointer in sync with the engine (acceptance 5–6).
        LaunchedEffect(mapSurface) {
            viewModel.engine.state.collect { state ->
                state.currentLocation?.let { mapSurface?.showUserLocation(it) }
            }
        }

        // Map long-press = destination selection (spec: map selection).
        LaunchedEffect(mapSurface) {
            mapSurface?.addOnMapLongClickListener { coord ->
                viewModel.onMapLongPress(coord)
                mapSurface?.showDestination(coord)
                true
            }
        }

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HunterLogo()
            Spacer(Modifier.size(10.dp))
            Text("HunterNav", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.background(Color.White.copy(alpha = 0.92f), CircleShape),
            ) {
                Icon(Icons.Default.Menu, contentDescription = "Settings", tint = Color(0xFF1B2430))
            }
        }

        // Locate-me
        IconButton(
            onClick = {
                viewModel.currentLocationOrNull()?.let { mapSurface?.centerOn(it.coordinate) }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 68.dp, end = 16.dp)
                .background(Color.White.copy(alpha = 0.95f), CircleShape)
                .size(46.dp),
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "My location", tint = Cobalt)
        }

        // Demo-mode banner (developer switch; visible only when enabled in Settings)
        if (demoMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shadowElevation = 4.dp,
            ) {
                Text(
                    "DEMO MODE — simulated GPS active",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // Bottom sheet: search + recents + start action
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = IvoryElevated,
            shadowElevation = 18.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                viewModel.statusMessage(locationStatus)?.let { message ->
                    Text(message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenSearch() },
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White,
                    shadowElevation = 2.dp,
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = CobaltTint) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = Cobalt,
                                modifier = Modifier.padding(10.dp).size(22.dp),
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text("DESTINATION", style = MaterialTheme.typography.labelMedium, color = InkSecondary)
                            Text(
                                destination?.title ?: "Where are you going?",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                if (recents.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent destinations", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(4.dp))

                    recents.forEach { recent ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.onDestinationSelected(recent, source = "recent")
                                    onDestinationPicked()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(shape = CircleShape, color = CobaltTint) {
                                Icon(Icons.Default.Place, contentDescription = null, tint = Cobalt, modifier = Modifier.padding(8.dp).size(20.dp))
                            }
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(recent.title, style = MaterialTheme.typography.titleMedium)
                                recent.subtitle?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = InkSecondary)
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        "Your picked destinations will appear here — search for a place or " +
                            "long-press the map to drop a pin.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSecondary,
                    )
                }

                Spacer(Modifier.height(10.dp))
                PrimaryActionButton(
                    text = "Start Navigation",
                    enabled = destination != null,
                    onClick = {
                        // Reuses routes already fetched for this destination; fetches when
                        // there are none (destination changes always discard stale routes).
                        viewModel.ensureRoute()
                        onDestinationPicked()
                    },
                )
            }
        }
    }
}
