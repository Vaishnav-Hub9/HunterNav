package com.hunternav.ui.navigation

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hunternav.ui.rememberAppViewModel
import com.hunternav.core.util.FormatUtils
import com.hunternav.di.AppContainer
import com.hunternav.domain.model.CameraMode
import com.hunternav.domain.model.LocationData
import com.hunternav.ui.AppViewModel
import com.hunternav.ui.components.ManeuverIcon
import com.hunternav.ui.components.MapLoadErrorOverlay
import com.hunternav.ui.components.PillChip
import com.hunternav.ui.components.StatusRibbon
import com.hunternav.ui.map.HunterNavMapView
import com.hunternav.ui.map.MapController
import com.hunternav.ui.map.NavigationCameraController
import com.hunternav.ui.map.NavigationZoomPolicy
import com.hunternav.ui.theme.Cobalt
import com.hunternav.ui.theme.IvoryElevated
import kotlinx.coroutines.delay

/**
 * Active navigation — the most important screen.
 * Top: next maneuver + distance + road name. Middle: following map. Bottom: remaining + ETA.
 * Shows rerouting ribbon without leaving the screen; arrival state replaces the bottom card.
 */
@Composable
fun NavigationScreen(
    container: AppContainer,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: AppViewModel = rememberAppViewModel(container)

    val navState by viewModel.engine.state.collectAsState()
    val phase by viewModel.phase.collectAsState()
    val demoMode by viewModel.demoMode.collectAsState()
    val demoRunning by viewModel.demoRunning.collectAsState()

    var mapController by remember { mutableStateOf<MapController?>(null) }
    var cameraController by remember { mutableStateOf<NavigationCameraController?>(null) }
    var cameraMode by remember { mutableStateOf(CameraMode.FOLLOW) }
    var mapHeightPx by remember { mutableIntStateOf(0) }
    var mapError by remember { mutableStateOf<String?>(null) }

    // System back during active navigation must behave exactly like the cancel button:
    // tear the trip down (jobs, engine, route, arrival latch) BEFORE leaving the screen.
    // Without this, popping the screen left the engine navigating and rerouting off-screen.
    BackHandler {
        viewModel.cancelNavigation()
        onExit()
    }

    // Reserve the top band so the camera keeps the puck in the lower-middle of the screen.
    LaunchedEffect(mapHeightPx, mapController) {
        if (mapHeightPx > 0 && mapController != null) {
            mapController?.setNavPadding(0, (mapHeightPx * 0.34f).toInt(), 0, 0)
        }
    }

    val route = navState.activeRoute

    // Camera follow loop: NavigationCameraController decides zoom via NavigationZoomPolicy;
    // MapController no-ops outside FOLLOW so user gestures stay in FREE mode.
    LaunchedEffect(mapController, route) {
        if (mapController == null || route == null) return@LaunchedEffect
        while (true) {
            val state = viewModel.engine.state.value
            state.currentLocation?.let { location ->
                mapController?.showUserLocation(location)
                cameraController?.onNavigationTick(
                    location = location,
                    bearing = state.currentBearing,
                    distanceToManeuverMeters = state.distanceToNextManeuver,
                )
                mapController?.showRouteProgress(route, state.snappedGeometryIndex)
            }
            delay(500)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { mapHeightPx = it.height },
    ) {
        HunterNavMapView(
            modifier = Modifier.fillMaxSize(),
            onMapViewReady = { mv ->
                val controller = MapController(context, mv)
                mapController = controller
                cameraController = NavigationCameraController(controller)
                controller.onUserGesture = {
                    cameraMode = CameraMode.FREE
                    controller.setCameraMode(CameraMode.FREE)
                }
                controller.loadStyle(
                    onReady = {
                        mapError = null
                        controller.showRoute(route)
                        route?.let { controller.fitRoute(it) }
                        viewModel.engine.state.value.currentLocation?.let { controller.showUserLocation(it) }
                        viewModel.onMapReady()
                    },
                    onError = { mapError = it },
                )
            },
        )

        // Map/style load failure: visible error + retry instead of a blank screen (spec §1).
        mapError?.let { message ->
            MapLoadErrorOverlay(message = message, onRetry = { mapController?.retryStyle() })
        }

        // Maneuver banner (top)
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navState.nextStep?.let { step ->
                    ManeuverIcon(type = step.maneuver.type, tint = Color(0xFF1B2430), size = 52.dp)
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            FormatUtils.distance(navState.distanceToNextManeuver),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            step.roadName ?: instructionFor(step.maneuver.type),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF5A6472),
                            maxLines = 1,
                        )
                    }
                } ?: run {
                    Column(Modifier.weight(1f)) {
                        Text("Starting…", style = MaterialTheme.typography.titleMedium)
                        Text("Waiting for GPS fix", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5A6472))
                    }
                }
            }
        }

        // Back/cancel + camera chips (under the banner)
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 108.dp, start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    viewModel.cancelNavigation()
                    onExit()
                },
                modifier = Modifier.background(Color.White.copy(alpha = 0.95f), CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel navigation", tint = Color(0xFF1B2430))
            }
        }

        // Rerouting / off-route ribbon
        when {
            navState.rerouting -> StatusRibbon(
                text = "Rerouting…",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 118.dp),
            )
            navState.offRoute -> StatusRibbon(
                text = "Off route — finding a new way",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 118.dp),
            )
        }

        // Camera mode chips (right side)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillChip(
                text = "Follow",
                selected = cameraMode == CameraMode.FOLLOW,
                onClick = {
                    cameraMode = CameraMode.FOLLOW
                    cameraController?.followMode()
                    navState.currentLocation?.let {
                        mapController?.follow(it, navState.currentBearing, NavigationZoomPolicy().cruiseZoom)
                    }
                },
            )
            PillChip(
                text = "Overview",
                selected = cameraMode == CameraMode.OVERVIEW,
                onClick = {
                    cameraMode = CameraMode.OVERVIEW
                    cameraController?.overview(route)
                },
            )
        }

        // Bottom card
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = if (phase == com.hunternav.ui.AppPhase.ARRIVED) Color(0xFFEAF6EF) else IvoryElevated,
            shadowElevation = 18.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                if (phase == com.hunternav.ui.AppPhase.ARRIVED) {
                    Text(
                        "You arrived 🏁",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color(0xFF2E8B57),
                        fontWeight = FontWeight.Black,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        viewModel.destination.value?.title ?: "Destination",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF5A6472),
                    )
                    Spacer(Modifier.height(14.dp))
                    com.hunternav.ui.components.PrimaryActionButton(
                        text = "Done",
                        onClick = {
                            viewModel.finishArrival()
                            onExit()
                        },
                    )
                } else {
                    Row {
                        BottomMetric(
                            "Remaining",
                            FormatUtils.distance(navState.remainingDistance),
                            Modifier.weight(1f),
                        )
                        BottomMetric(
                            "ETA (est.)",
                            FormatUtils.eta(navState.remainingDuration, System.currentTimeMillis()),
                            Modifier.weight(1f),
                        )
                        BottomMetric(
                            "Duration",
                            FormatUtils.duration(navState.remainingDuration),
                            Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Demo controls (developer-only): simulate drive + wrong turn.
                    if (demoMode) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillChip(
                                text = if (demoRunning) "Stop sim" else "Simulate drive",
                                selected = demoRunning,
                                onClick = {
                                    if (demoRunning) viewModel.stopDemoSimulation() else viewModel.startDemoSimulation()
                                },
                            )
                            PillChip(
                                text = "Wrong turn",
                                selected = false,
                                onClick = { viewModel.injectDemoWrongTurn() },
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Cancel navigation",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clickable {
                                viewModel.cancelNavigation()
                                onExit()
                            }
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}



@Composable
private fun BottomMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF5A6472))
    }
}

/** Short human instruction for a normalized maneuver (road name is shown when available). */
fun instructionFor(type: com.hunternav.domain.model.ManeuverType): String = when (type) {
    com.hunternav.domain.model.ManeuverType.START -> "Begin route"
    com.hunternav.domain.model.ManeuverType.CONTINUE -> "Continue"
    com.hunternav.domain.model.ManeuverType.SLIGHT_LEFT -> "Keep left"
    com.hunternav.domain.model.ManeuverType.SLIGHT_RIGHT -> "Keep right"
    com.hunternav.domain.model.ManeuverType.LEFT -> "Turn left"
    com.hunternav.domain.model.ManeuverType.RIGHT -> "Turn right"
    com.hunternav.domain.model.ManeuverType.SHARP_LEFT -> "Sharp left"
    com.hunternav.domain.model.ManeuverType.SHARP_RIGHT -> "Sharp right"
    com.hunternav.domain.model.ManeuverType.U_TURN -> "Make a U-turn"
    com.hunternav.domain.model.ManeuverType.ROUNDABOUT -> "At the roundabout"
    com.hunternav.domain.model.ManeuverType.ARRIVE -> "Arrive at destination"
}
