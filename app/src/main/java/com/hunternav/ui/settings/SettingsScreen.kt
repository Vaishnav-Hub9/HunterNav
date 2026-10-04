package com.hunternav.ui.settings

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hunternav.ui.rememberAppViewModel
import com.hunternav.BuildConfig
import com.hunternav.di.AppContainer
import com.hunternav.ui.AppViewModel
import com.hunternav.ui.components.PillChip
import com.hunternav.ui.theme.Cobalt
import com.hunternav.ui.theme.InkSecondary
import com.hunternav.ui.theme.Ivory

/** Basic V1 settings: units, camera preference, map style placeholder, demo mode, attribution. */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: AppViewModel = rememberAppViewModel(container)
    val demoMode by viewModel.demoMode.collectAsState()

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
                    "OpenFreeMap · Liberty (bright daylight style). More styles coming.",
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
                Text("About & attribution", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Map data © OpenStreetMap contributors\n" +
                        "Map tiles © OpenFreeMap (openfreemap.org)\n" +
                        "Routing by OSRM (project-osrm.org)\n" +
                        "Geocoding by Nominatim (nominatim.openstreetmap.org)\n\n" +
                        "HunterNav ${BuildConfig.VERSION_NAME} — open-source prototype.\n" +
                        "Routing endpoint: ${BuildConfig.OSRM_BASE_URL}",
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
