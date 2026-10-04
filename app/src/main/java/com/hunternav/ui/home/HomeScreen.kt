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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hunternav.ui.rememberAppViewModel
import com.hunternav.BuildConfig
import com.hunternav.di.AppContainer
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Destination
import com.hunternav.ui.AppViewModel
import com.hunternav.ui.components.HunterLogo
import com.hunternav.ui.components.PrimaryActionButton
import com.hunternav.ui.map.HunterNavMapView
import com.hunternav.ui.map.MapController
import com.hunternav.ui.theme.Cobalt
import com.hunternav.ui.theme.CobaltTint
import com.hunternav.ui.theme.InkSecondary
import com.hunternav.ui.theme.IvoryElevated
import org.maplibre.android.maps.MapView

/** Recents are placeholders in V1 (spec: recent destination placeholders). */
private data class Recent(val title: String, val subtitle: String, val lat: Double, val lon: Double)

private val RECENTS = listOf(
    Recent("Vasavi College of Engineering", "Ibrahimbagh, Hyderabad", 17.3969, 78.3208),
    Recent("Charminar", "Char Kaman, Ghansi Bazaar", 17.3616, 78.4747),
    Recent("Gachibowli", "Financial District, Hyderabad", 17.4401, 78.3489),
)

/** Home: map preview + destination search + recents + start action. */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onDestinationPicked: () -> Unit,
    onStartNavigation: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: AppViewModel = rememberAppViewModel(container)

    val destination by viewModel.destination.collectAsState()
    val locationStatus by viewModel.locationStatus.collectAsState()
    val demoMode by viewModel.demoMode.collectAsState()

    var mapController by remember { mutableStateOf<MapController?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }

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
        HunterNavMapView(
            modifier = Modifier.fillMaxSize(),
            onMapViewReady = { mv ->
                mapView = mv
                val controller = MapController(context, mv)
                mapController = controller
                controller.loadStyle {
                    viewModel.engine.state.value.currentLocation?.let { controller.showUserLocation(it) }
                    viewModel.onMapReady()
                }
            },
        )

        // Keep the current-location pointer in sync with the engine (acceptance 5–6).
        LaunchedEffect(mapController) {
            viewModel.engine.state.collect { state ->
                state.currentLocation?.let { mapController?.showUserLocation(it) }
            }
        }

        // Map long-press = destination selection (spec: map selection).
        LaunchedEffect(mapController) {
            mapController?.let { controller ->
                mapView?.getMapAsync { map ->
                    map.addOnMapLongClickListener { point ->
                        val coord = Coordinate(point.latitude, point.longitude)
                        viewModel.onMapLongPress(coord)
                        controller.showDestination(coord)
                        true
                    }
                }
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
                viewModel.currentLocationOrNull()?.let { mapController?.centerOn(it.coordinate) }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent destinations", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text("View all", style = MaterialTheme.typography.labelLarge, color = Cobalt)
                }
                Spacer(Modifier.height(4.dp))

                RECENTS.forEach { recent ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.onDestinationSelected(
                                    Destination(
                                        Coordinate(recent.lat, recent.lon),
                                        recent.title,
                                        recent.subtitle,
                                    ),
                                )
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
                            Text(recent.subtitle, style = MaterialTheme.typography.bodyMedium, color = InkSecondary)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                PrimaryActionButton(
                    text = "Start Navigation",
                    enabled = destination != null,
                    onClick = {
                        viewModel.prepareRoute()
                        onDestinationPicked()
                    },
                )
            }
        }
    }
}
