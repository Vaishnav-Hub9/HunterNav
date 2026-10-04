package com.hunternav.ui.search

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hunternav.di.AppContainer
import com.hunternav.domain.model.Destination
import com.hunternav.ui.AppViewModel
import com.hunternav.ui.AppViewModelFactory
import com.hunternav.ui.theme.Cobalt
import com.hunternav.ui.theme.InkSecondary
import com.hunternav.ui.theme.Ivory
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Search state container: debounced query flow + results. Lives outside recomposition. */
class SearchState {
    val query = MutableStateFlow("")
    val results = MutableStateFlow<List<Destination>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
}

/** Destination search: debounced Nominatim queries + confirm action. */
@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(
    container: AppContainer,
    onDestinationConfirmed: (Destination) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: AppViewModel = viewModel(factory = AppViewModelFactory(container))
    val state = remember { SearchState() }

    // Debounced search (spec: debounce search, cancel stale requests).
    LaunchedEffect(Unit) {
        launch {
            state.query
                .debounce(350)
                .distinctUntilChanged()
                .collect { query ->
                    if (query.isBlank()) {
                        state.results.value = emptyList()
                        state.error.value = null
                        return@collect
                    }
                    state.loading.value = true
                    state.error.value = null
                    val near = viewModel.currentLocationOrNull()?.coordinate
                    val result = container.searchDestination(query, near)
                    state.loading.value = false
                    when (result) {
                        is com.hunternav.core.result.AppResult.Success -> state.results.value = result.value
                        is com.hunternav.core.result.AppResult.Failure ->
                            state.error.value = when (result.kind) {
                                com.hunternav.core.result.AppErrorKind.NETWORK -> "No internet connection."
                                com.hunternav.core.result.AppErrorKind.TIMEOUT -> "Search timed out. Try again."
                                else -> "Search is unavailable right now."
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
                value = state.query.collectAsState().value,
                onValueChange = { state.query.value = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search a place or address…", color = InkSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Cobalt) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cobalt,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { state.query.value.let { q -> state.query.value = q } }),
            )

            Spacer(Modifier.height(12.dp))

            val loading by state.loading.collectAsState()
            val error by state.error.collectAsState()
            val results by state.results.collectAsState()

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
                    if (state.query.collectAsState().value.isBlank()) {
                        "Type to search — or go back and long-press the map to drop a pin."
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
