package com.hunternav.ui.routepreview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hunternav.core.util.FormatUtils
import com.hunternav.di.AppContainer
import com.hunternav.ui.AppViewModel
import com.hunternav.ui.AppViewModelFactory
import com.hunternav.ui.components.PrimaryActionButton
import com.hunternav.ui.map.HunterNavMapView
import com.hunternav.ui.map.MapController
import com.hunternav.ui.theme.Cobalt
import com.hunternav.ui.theme.InkSecondary
import com.hunternav.ui.theme.IvoryElevated

/** Route preview: map + route + metrics + alternatives + Start Navigation. */
@Composable
fun RoutePreviewScreen(
    container: AppContainer,
    onChangeDestination: () -> Unit,
    onStartNavigation: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: AppViewModel = viewModel(factory = AppViewModelFactory(container))

    val destination by viewModel.destination.collectAsState()
    val routes by viewModel.routes.collectAsState()
    val selectedRoute by viewModel.selectedRoute.collectAsState()
    val loading by viewModel.isLoadingRoutes.collectAsState()
    val error by viewModel.routeError.collectAsState()

    var mapController by remember { mutableStateOf<MapController?>(null) }

    // Fetch if arriving without routes (e.g. process recreation).
    LaunchedEffect(Unit) {
        if (routes.isEmpty() && viewModel.destination.value != null) viewModel.prepareRoute()
    }

    // Draw route + destination when data arrives.
    LaunchedEffect(selectedRoute, mapController) {
        mapController?.let { controller ->
            controller.showRoute(selectedRoute)
            controller.fitRoute(selectedRoute)
            destination?.let { controller.showDestination(it.coordinate) }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HunterNavMapView(
            modifier = Modifier.fillMaxSize(),
            onMapViewReady = { mv ->
                val controller = MapController(context, mv)
                mapController = controller
                controller.loadStyle {
                    viewModel.engine.state.value.currentLocation?.let { controller.showUserLocation(it) }
                    viewModel.onMapReady()
                }
            },
        )

        // Current-location pointer stays live on the preview map as well.
        LaunchedEffect(mapController) {
            viewModel.engine.state.collect { state ->
                state.currentLocation?.let { mapController?.showUserLocation(it) }
            }
        }

        // Back button
        IconButton(
            onClick = onChangeDestination,
            modifier = Modifier
                .statusBarsPadding()
                .padding(16.dp)
                .background(Color.White.copy(alpha = 0.95f), CircleShape),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Change destination")
        }

        // Route summary chip (floating over map)
        selectedRoute?.let { route ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 72.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.97f),
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(width = 26.dp, height = 5.dp)
                            .background(Cobalt, RoundedCornerShape(3.dp)),
                    )
                    Text("Fastest route", style = MaterialTheme.typography.labelLarge)
                    Text(
                        FormatUtils.duration(route.durationSeconds),
                        style = MaterialTheme.typography.labelLarge,
                        color = Cobalt,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Bottom card
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = IvoryElevated,
            shadowElevation = 18.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("YOUR DESTINATION", style = MaterialTheme.typography.labelMedium, color = Cobalt)
                Text(
                    destination?.title ?: "—",
                    style = MaterialTheme.typography.headlineMedium,
                )
                destination?.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = InkSecondary)
                }

                Spacer(Modifier.height(16.dp))

                selectedRoute?.let { route ->
                    Row {
                        MetricColumn("Distance", FormatUtils.distance(route.distanceMeters), Modifier.weight(1f))
                        VerticalDivider()
                        MetricColumn("Estimated time", FormatUtils.duration(route.durationSeconds), Modifier.weight(1f))
                        VerticalDivider()
                        MetricColumn(
                            "Arrival",
                            FormatUtils.eta(route.durationSeconds, System.currentTimeMillis()),
                            Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(14.dp))

                    // Alternatives
                    if (routes.size > 1) {
                        Text("Routes", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        routes.take(3).forEach { routeOption ->
                            val isSelected = routeOption == selectedRoute
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectRoute(routeOption) }
                                    .padding(vertical = 3.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) Color(0xFFE8EDFC) else Color.White,
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(width = 4.dp, height = 30.dp)
                                            .background(
                                                if (isSelected) Cobalt else Color(0xFFDDD8CE),
                                                RoundedCornerShape(2.dp),
                                            ),
                                    )
                                    Spacer(Modifier.size(10.dp))
                                    Column {
                                        Text(
                                            routeOption.summaryRoadName ?: "Route",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            "${FormatUtils.distance(routeOption.distanceMeters)} · ${FormatUtils.duration(routeOption.durationSeconds)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = InkSecondary,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }

                when {
                    loading -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(color = Cobalt) }

                    error != null -> Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    else -> Unit
                }

                Spacer(Modifier.height(12.dp))
                PrimaryActionButton(
                    text = "Start Navigation",
                    enabled = selectedRoute != null && !loading,
                    onClick = {
                        viewModel.startNavigation()
                        onStartNavigation()
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Change destination",
                    color = Cobalt,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable { onChangeDestination() }
                        .padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = InkSecondary)
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        Modifier
            .size(width = 1.dp, height = 36.dp)
            .background(Color(0xFFE4DFD5)),
    )
}
