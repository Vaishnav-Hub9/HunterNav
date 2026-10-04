package com.hunternav.ui.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hunternav.ui.components.HunterLogo
import com.hunternav.ui.theme.Cobalt
import com.hunternav.ui.theme.InkSecondary
import kotlinx.coroutines.delay

/** Brief branded startup screen; navigation work begins immediately behind it. */
@Composable
fun SplashScreen(onReady: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(700)
        onReady()
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HunterLogo(size = 72)
        Spacer(Modifier.height(20.dp))
        Text("HunterNav", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        Text(
            "Motorcycle navigation, distilled.",
            style = MaterialTheme.typography.bodyMedium,
            color = InkSecondary,
        )
        Spacer(Modifier.height(40.dp))
        Text("OpenStreetMap · OpenFreeMap · OSRM", style = MaterialTheme.typography.labelMedium, color = Cobalt)
    }
}
