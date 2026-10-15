package com.hunternav.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.hunternav.ui.rememberAppViewModel
import com.hunternav.BuildConfig
import com.hunternav.data.offline.TelanganaOfflineMap
import com.hunternav.di.AppContainer
import com.hunternav.ui.AppViewModel
import com.hunternav.ui.components.PillChip
import com.hunternav.ui.components.PrimaryActionButton
import com.hunternav.ui.theme.Cobalt
import com.hunternav.ui.theme.InkSecondary
import com.hunternav.ui.theme.Ivory
import java.util.Locale

/** Basic V1 settings: units, camera preference, map style placeholder, demo mode, attribution. */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: AppViewModel = rememberAppViewModel(container)
    val demoMode by viewModel.demoMode.collectAsState()
    // Names the active destination-search backend in the privacy/attribution cards below.
    val geocoderLabel = if (container.geocodingProviderTag == "mappls") "Mappls" else "Nominatim"
    // Map renderer label: Mappls when the SDK auth files were present at build time,
    // otherwise the keyless MapLibre + OpenFreeMap fallback (CI / checkout builds).
    val mapProviderLabel = if (BuildConfig.MAPPLS_ENABLED) "Mappls (mapmyindia.com)" else "OpenFreeMap (openfreemap.org)"
    // Routing is Mappls-only; show the unconfigured state instead of pretending it is active.
    val routingLabel = if (container.routingProviderTag == "mappls") "Mappls" else "Mappls (not configured)"

    var units by remember { mutableStateOf("km") }
    var cameraPref by remember { mutableStateOf("Follow") }
    // Hidden developer gate: tap the version row 5 times to reveal demo mode (spec §29).
    var developerUnlocked by remember { mutableStateOf(false) }
    var versionTaps by remember { mutableStateOf(0) }

    Surface(modifier = Modifier.fillMaxSize(), color = Ivory) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.background(Color.White, CircleShape)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.size(10.dp))
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(Modifier.height(18.dp))

            SettingsCard {
                Text("Distance units", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    PillChip("km · meters", units == "km", { units = "km" })
                    PillChip("mi · feet", units == "mi", { units = "mi" })
                }
            }

            Spacer(Modifier.height(12.dp))

            SettingsCard {
                Text("Navigation camera", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    PillChip("Follow", cameraPref == "Follow", { cameraPref = "Follow" })
                    PillChip("North-up", cameraPref == "North-up", { cameraPref = "North-up" })
                }
            }

            Spacer(Modifier.height(12.dp))

            SettingsCard {
                Text("Map style", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (BuildConfig.MAPPLS_ENABLED) {
                        "Mappls vector map (mapmyindia.com) — automotive-grade India road data."
                    } else {
                        "OpenFreeMap · Liberty (bright daylight style). More styles coming."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary,
                )
            }

            Spacer(Modifier.height(12.dp))

            if (BuildConfig.DEMO_MODE_ENABLED && developerUnlocked) {
                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Demo mode", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Simulated GPS for testing without riding. Adds demo controls to the navigation screen.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkSecondary,
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                        Switch(
                            checked = demoMode,
                            onCheckedChange = { viewModel.setDemoMode(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Cobalt),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            SettingsCard {
                Text("Offline map (Hyderabad region)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Download the area you ride so the map keeps rendering without a data " +
                        "connection. OSM data © OpenStreetMap contributors (ODbL), served via " +
                        "OpenFreeMap; stored on this device only.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary,
                )
                Spacer(Modifier.height(8.dp))

                val context = LocalContext.current
                val offlineMap = remember(context) { TelanganaOfflineMap(context.applicationContext) }
                val offlineState by offlineMap.state.collectAsState()
                val installedPreset by offlineMap.installedPreset.collectAsState()
                var selectedPreset by remember {
                    mutableStateOf(installedPreset ?: TelanganaOfflineMap.Preset.HYDERABAD)
                }
                LaunchedEffect(Unit) { offlineMap.refresh() }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TelanganaOfflineMap.Preset.entries.forEach { preset ->
                        PillChip(
                            "${preset.displayName} ${preset.sizeEstimate}",
                            selectedPreset == preset,
                            { selectedPreset = preset },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                when (val state = offlineState) {
                    is TelanganaOfflineMap.OfflineState.Checking ->
                        Text("Checking offline maps…", style = MaterialTheme.typography.bodyMedium, color = InkSecondary)

                    is TelanganaOfflineMap.OfflineState.Idle ->
                        Text("Not downloaded yet. Estimates are for the zoom levels navigation needs.", style = MaterialTheme.typography.bodyMedium, color = InkSecondary)

                    is TelanganaOfflineMap.OfflineState.Downloading ->
                        Text(
                            "Downloading… ${state.percent}% (${formatBytes(state.bytesDownloaded)})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cobalt,
                        )

                    is TelanganaOfflineMap.OfflineState.Ready ->
                        Text(
                            "Ready — ${installedPreset?.displayName ?: "region"} offline (${formatBytes(state.bytesDownloaded)}).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cobalt,
                        )

                    is TelanganaOfflineMap.OfflineState.Error ->
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryActionButton(
                        text = "Download",
                        enabled = offlineState !is TelanganaOfflineMap.OfflineState.Downloading,
                        onClick = { offlineMap.startDownload(selectedPreset) },
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryActionButton(
                        text = "Delete",
                        enabled = installedPreset != null && offlineState !is TelanganaOfflineMap.OfflineState.Downloading,
                        onClick = { offlineMap.deletePack() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            SettingsCard {
                Text("Privacy & services", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "No accounts, no analytics, no crash reporting.\n\n" +
                        "• Map rendering ($mapProviderLabel) sees which map areas are displayed.\n" +
                        "• Place search ($geocoderLabel) sees your search text — never your GPS location.\n" +
                        "• Routing ($routingLabel) receives your origin and destination coordinates, " +
                        "and your current position while rerouting.\n" +
                        "• Your location stays on this device; precise-location diagnostics are " +
                        "written only to Logcat in debug builds.\n\n" +
                        "These third-party services can see these requests — they are not hidden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary,
                )
            }

            Spacer(Modifier.height(12.dp))

            SettingsCard {
                Text("About & attribution", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    (if (BuildConfig.MAPPLS_ENABLED) {
                        "Map rendered by Mappls © CEINFO Systems (MapmyIndia)\n"
                    } else {
                        "Map data © OpenStreetMap contributors\nMap tiles © OpenFreeMap (openfreemap.org)\n"
                    }) +
                        "Routing by Mappls (mapmyindia.com) — two-wheeler profile, traffic-aware\n" +
                        "Geocoding by $geocoderLabel " +
                        (if (geocoderLabel == "Mappls") "(mappls.com)" else "(nominatim.openstreetmap.org)") +
                        "\n\n" +
                        "HunterNav ${BuildConfig.VERSION_NAME} — open-source prototype.\n" +
                        "Routing endpoint: ${BuildConfig.MAPPLS_ROUTING_BASE_URL} ($routingLabel)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary,
                    modifier = Modifier.clickable {
                        versionTaps += 1
                        if (versionTaps >= 5) developerUnlocked = true
                    },
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
    else -> "$bytes B"
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = Color.White,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}
