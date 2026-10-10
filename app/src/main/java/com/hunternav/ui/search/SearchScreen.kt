package com.hunternav.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hunternav.core.result.AppErrorKind
import com.hunternav.core.result.AppResult
import com.hunternav.di.AppContainer
import com.hunternav.domain.model.Destination
import com.hunternav.ui.AppViewModel
import com.hunternav.ui.rememberAppViewModel
import com.hunternav.ui.theme.Cobalt
import com.hunternav.ui.theme.InkSecondary
import com.hunternav.ui.theme.Ivory
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Destination search (spec §5 — public Nominatim usage policy):
 *  - queries fire ONLY on deliberate submission (IME Search action or the trailing button) —
 *    no type-ahead/autocomplete requests while typing,
 *  - the previous in-flight request is cancelled so a slow old response can never overwrite
 *    newer results,
 *  - the user's GPS location is NEVER sent to the geocoder,
 *  - the provider enforces ≥1 s between requests app-wide and caches repeated queries, so
 *    even a submit-spam stays within policy.
 * Every failure (network, timeout, HTTP, malformed, empty) lands in a visible, finite state —
 * never an indefinite spinner.
 */
@Composable
fun SearchScreen(
    container: AppContainer,
    onDestinationConfirmed: (Destination) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: AppViewModel = rememberAppViewModel(container)

    var queryText by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Destination>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasSearched by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun submitSearch() {
        val query = queryText.trim()
        if (query.isEmpty()) {
            results = emptyList()
            error = null
            hasSearched = false
            return
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            hasSearched = true
            loading = true
            error = null
            when (val result = container.searchDestination(query, near = null)) {
                is AppResult.Success -> {
                    loading = false
                    results = result.value
                }
                is AppResult.Failure -> {
                    loading = false
                    results = emptyList()
                    error = when (result.kind) {
                        AppErrorKind.NETWORK -> "No internet connection. Unable to find destination."
                        AppErrorKind.TIMEOUT -> "Search timed out. Unable to find destination."
                        // Spec §4: every geocoding failure must say why instead of silently
                        // producing an empty destination.
                        else -> "Unable to find destination"
                    }
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Ivory) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.background(Color.White, CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.size(10.dp))
                Text("Where are you going?", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search a place or address…", color = InkSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Cobalt) },
                trailingIcon = {
                    IconButton(onClick = { submitSearch() }) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Cobalt)
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cobalt,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
            )

            Spacer(Modifier.height(12.dp))

            when {
                loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(color = Cobalt) }

                error != null -> Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )

                results.isEmpty() -> Text(
                    if (!hasSearched) {
                        "Type a place and press search — or go back and long-press the map to drop a pin."
                    } else {
                        "No results. Try a different query."
                    },
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results) { destination ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.onDestinationSelected(destination)
                                    onDestinationConfirmed(destination)
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            shadowElevation = 1.dp,
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = Color(0xFFFDECEA)) {
                                    Icon(
                                        Icons.Default.Place,
                                        contentDescription = null,
                                        tint = Color(0xFFC6453D),
                                        modifier = Modifier.padding(8.dp).size(20.dp),
                                    )
                                }
                                Spacer(Modifier.size(12.dp))
                                Column {
                                    Text(destination.title, style = MaterialTheme.typography.titleMedium)
                                    destination.subtitle?.let {
                                        Text(it, style = MaterialTheme.typography.bodyMedium, color = InkSecondary, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
