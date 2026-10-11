package com.hunternav.data.offline

import android.content.Context
import com.hunternav.BuildConfig
import com.hunternav.core.util.DebugLog
import com.hunternav.domain.model.RegionBounds
import com.hunternav.domain.model.SupportedRegion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Manages the on-device offline map pack (spec P3: Hyderabad/Telangana region only).
 *
 * Uses MapLibre's offline-pack API against the same OpenFreeMap style the app already uses
 * (OSM data, ODbL — attribution is shown in the app). Only ONE pack exists at a time; the
 * download is explicit (a user button), bounded to the chosen region and zoom range, and is
 * stored in the app's private storage. Nothing is pre-fetched automatically — this respects
 * the tile provider's usage policy (no bulk scraping, no world downloads).
 */
class TelanganaOfflineMap(context: Context) {

    /** Download presets; sizes are estimates — the exact byte count shows during download. */
    enum class Preset(
        val displayName: String,
        val bounds: RegionBounds,
        val minZoom: Double,
        val maxZoom: Double,
        val slug: String,
        val sizeEstimate: String,
    ) {
        HYDERABAD(
            displayName = "Hyderabad",
            bounds = SupportedRegion.HYDERABAD,
            minZoom = 10.0,
            maxZoom = 16.0,
            slug = "hunter-nav-hyderabad",
            sizeEstimate = "≈ 0.5–1 GB",
        ),
        TELANGANA(
            displayName = "Telangana",
            bounds = SupportedRegion.TELANGANA,
            minZoom = 8.0,
            maxZoom = 14.0,
            slug = "hunter-nav-telangana",
            sizeEstimate = "≈ 2–3 GB",
        ),
    }

    sealed class OfflineState {
        data object Checking : OfflineState()
        data object Idle : OfflineState()
        data class Downloading(val percent: Int, val bytesDownloaded: Long) : OfflineState()
        data class Ready(val bytesDownloaded: Long) : OfflineState()
        data class Error(val message: String) : OfflineState()
    }

    private val offlineManager = OfflineManager.getInstance(context.applicationContext)

    /** The single active pack's observer; re-attached on refresh, ignored after completion. */
    private var observerRegion: OfflineRegion? = null
    private var completed = false

    private val _state = MutableStateFlow<OfflineState>(OfflineState.Checking)
    val state: StateFlow<OfflineState> = _state.asStateFlow()

    /** Currently stored pack preset, or null when none is downloaded. */
    private val _installedPreset = MutableStateFlow<Preset?>(null)
    val installedPreset: StateFlow<Preset?> = _installedPreset.asStateFlow()

    /** Loads existing packs and reflects their real status (call when the screen opens). */
    fun refresh() {
        _state.value = OfflineState.Checking
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val ours = offlineRegions?.firstOrNull { presetFor(it) != null }
                val preset = ours?.let { presetFor(it) }
                _installedPreset.value = preset
                if (ours == null || preset == null) {
                    _state.value = OfflineState.Idle
                    return
                }
                completed = false
                observerRegion = ours
                ours.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) {
                        if (status == null) {
                            _state.value = OfflineState.Error("Could not read offline map status.")
                        } else if (status.isComplete) {
                            completed = true
                            _state.value = OfflineState.Ready(status.completedResourceSize)
                        } else {
                            _state.value = downloadingState(status)
                            attachObserver(ours, preset)
                            ours.setDownloadState(OfflineRegion.STATE_ACTIVE)
                        }
                    }

                    override fun onError(error: String?) {
                        _state.value = OfflineState.Error("Could not read offline map status: ${error ?: "unknown"}")
                    }
                })
            }

            override fun onError(error: String) {
                _state.value = OfflineState.Error("Could not read offline maps: $error")
            }
        })
    }

    /** Starts a fresh download of [preset], replacing any existing pack. */
    fun startDownload(preset: Preset) {
        deletePack {
            val bounds = LatLngBounds.Builder()
                .include(LatLng(preset.bounds.minLatitude, preset.bounds.minLongitude))
                .include(LatLng(preset.bounds.maxLatitude, preset.bounds.maxLongitude))
                .build()
            val definition = OfflineTilePyramidRegionDefinition(
                BuildConfig.MAP_STYLE_URL,
                bounds,
                preset.minZoom,
                preset.maxZoom,
                1.0f,
                false,
            )
            _state.value = OfflineState.Downloading(0, 0L)
            completed = false
            offlineManager.createOfflineRegion(
                definition,
                preset.slug.toByteArray(Charsets.UTF_8),
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        DebugLog.d("OFFLINE_PACK", "created preset=${preset.slug}")
                        observerRegion = offlineRegion
                        _installedPreset.value = preset
                        attachObserver(offlineRegion, preset)
                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    }

                    override fun onError(error: String) {
                        DebugLog.d("OFFLINE_PACK", "create_failed preset=${preset.slug} error=$error")
                        _state.value = OfflineState.Error("Could not start download: $error")
                    }
                },
            )
        }
    }

    /** Deletes the stored pack (if any) and returns to [OfflineState.Idle]. */
    fun deletePack(onDone: () -> Unit = {}) {
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val targets = offlineRegions.orEmpty().filter { presetFor(it) != null }
                if (targets.isEmpty()) {
                    observerRegion = null
                    _installedPreset.value = null
                    _state.value = OfflineState.Idle
                    onDone()
                    return
                }
                var remaining = targets.size
                val finishOne = {
                    remaining -= 1
                    if (remaining == 0) {
                        observerRegion = null
                        _installedPreset.value = null
                        _state.value = OfflineState.Idle
                        onDone()
                    }
                }
                targets.forEach { region ->
                    DebugLog.d("OFFLINE_PACK", "deleting ${presetFor(region)?.slug}")
                    runCatching { region.setDownloadState(OfflineRegion.STATE_INACTIVE) }
                    region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {
                            DebugLog.d("OFFLINE_PACK", "deleted id=${region.id}")
                            finishOne()
                        }

                        override fun onError(error: String) {
                            _state.value = OfflineState.Error("Could not delete offline map: $error")
                            finishOne()
                        }
                    })
                }
            }

            override fun onError(error: String) {
                _state.value = OfflineState.Error("Could not delete offline map: $error")
                onDone()
            }
        })
    }

    private fun downloadingState(status: OfflineRegionStatus): OfflineState.Downloading {
        val required = status.requiredResourceCount.coerceAtLeast(1L)
        val percent = ((status.completedResourceCount * 100) / required).toInt().coerceIn(0, 100)
        return OfflineState.Downloading(percent, status.completedResourceSize)
    }

    private fun presetFor(region: OfflineRegion): Preset? {
        val name = runCatching { region.metadata?.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        return Preset.entries.firstOrNull { it.slug == name }
    }

    private fun attachObserver(region: OfflineRegion, preset: Preset) {
        // MapLibre 13.6 exposes setObserver() (single, replaceable) — not add/remove pairs.
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.isComplete) {
                    if (!completed) {
                        completed = true
                        DebugLog.d("OFFLINE_PACK", "complete preset=${preset.slug} bytes=${status.completedResourceSize}")
                        _state.value = OfflineState.Ready(status.completedResourceSize)
                    }
                } else if (!completed) {
                    _state.value = downloadingState(status)
                }
            }

            override fun onError(error: OfflineRegionError) {
                DebugLog.d("OFFLINE_PACK", "error preset=${preset.slug} reason=${error.reason} ${error.message}")
                _state.value = OfflineState.Error(error.message ?: "Offline download failed")
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                _state.value = OfflineState.Error("Tile limit reached for this region — delete the pack and retry.")
            }
        })
    }
}
