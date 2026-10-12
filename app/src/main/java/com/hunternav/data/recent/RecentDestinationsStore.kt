package com.hunternav.data.recent

import android.content.SharedPreferences
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Destination
import com.hunternav.domain.repository.DestinationHistory
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Minimal key/value storage abstraction so the history logic is unit-testable on the JVM
 * without Android preferences, while production uses [SharedPreferencesBackend].
 */
interface PreferenceBackend {
    fun read(): String?
    fun write(value: String)
}

/** In-memory backend: unit tests, and the fallback when no Android preferences exist. */
class InMemoryPreferenceBackend : PreferenceBackend {
    private var value: String? = null
    override fun read(): String? = value
    override fun write(value: String) {
        this.value = value
    }
}

/** Production backend backed by [SharedPreferences] (main-thread safe: apply() is async). */
class SharedPreferencesBackend(private val prefs: SharedPreferences) : PreferenceBackend {
    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(value: String) {
        prefs.edit().putString(KEY, value).apply()
    }

    private companion object {
        const val KEY = "recent_destinations_v1"
    }
}

/**
 * Persists recently selected destinations as a small JSON list, most-recent-first.
 *
 * Correctness rules (these are what make recents trustworthy):
 *  - a destination is stored with EXACTLY the coordinate the user selected — no centroid,
 *    no default camera coordinate, no name-derived guess;
 *  - dedupe by coordinate: re-selecting the same place moves it to the front;
 *  - the list is capped at [maxEntries];
 *  - corrupted storage or out-of-range coordinates degrade to being dropped, never a crash.
 */
class RecentDestinationsStore(
    private val backend: PreferenceBackend,
    private val maxEntries: Int = MAX_ENTRIES,
) : DestinationHistory {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(StoredDestination.serializer())

    @Synchronized
    override fun record(destination: Destination) {
        val coordinate = destination.coordinate
        if (!isValid(coordinate)) return
        val current = loadStored()
        val next = (
            listOf(StoredDestination(coordinate.latitude, coordinate.longitude, destination.title, destination.subtitle)) +
                current.filterNot { it.latitude == coordinate.latitude && it.longitude == coordinate.longitude }
            ).take(maxEntries)
        // A storage failure must never break the selection flow.
        runCatching { backend.write(json.encodeToString(listSerializer, next)) }
    }

    @Synchronized
    override fun recent(): List<Destination> = loadStored().map {
        Destination(Coordinate(it.latitude, it.longitude), it.title, it.subtitle)
    }

    private fun loadStored(): List<StoredDestination> {
        val raw = backend.read()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }
            .getOrElse { emptyList() }  // corrupted storage: start clean, never crash
            .filter { isValid(Coordinate(it.latitude, it.longitude)) }
    }

    private fun isValid(coordinate: Coordinate): Boolean =
        coordinate.latitude.isFinite() && coordinate.longitude.isFinite() &&
            coordinate.latitude in -90.0..90.0 && coordinate.longitude in -180.0..180.0

    @Serializable
    internal data class StoredDestination(
        val latitude: Double,
        val longitude: Double,
        val title: String,
        val subtitle: String? = null,
    )

    companion object {
        /** Enough for a useful home screen without ever growing unbounded. */
        const val MAX_ENTRIES = 5
    }
}
