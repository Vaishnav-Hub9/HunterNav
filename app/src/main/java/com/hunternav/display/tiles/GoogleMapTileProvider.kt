package com.hunternav.display.tiles

import com.hunternav.core.util.DebugLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Map Tiles API (2D raster) tile provider.
 *
 * Implements the documented request flow end-to-end:
 *  1. `POST https://tile.googleapis.com/v1/createSession?key=...` with a JSON body
 *     (mapType/language/region/imageFormat/scale) returns a `session` token and its `expiry`
 *     (epoch seconds). Tokens are valid ~2 weeks; we refresh well before that, and immediately
 *     when a tile request is rejected because the session is expired/invalid.
 *  2. `GET https://tile.googleapis.com/v1/2dtiles/{z}/{x}/{y}?session=...&key=...` returns the
 *     tile image (256×256 by default; zoom 0–22).
 *
 * Caching follows the Map Tiles API policies: in-memory only, honoring `Cache-Control`
 * max-age (see [TileCache]) — no permanent storage.
 *
 * All network work runs on [Dispatchers.IO]; the main thread is never blocked.
 */
class GoogleMapTileProvider(
    private val apiKey: String,
    private val baseUrl: String = BASE_URL,
    private val language: String = "en",
    private val region: String = "IN",
    /** Image format requested at session creation. */
    private val imageFormat: String = "png",
    private val client: OkHttpClient = defaultClient(),
    private val cache: TileCache = TileCache(maxEntries = 256, hardTtlMs = HARD_TTL_MS, nowMs = System::currentTimeMillis),
) : MapTileProvider {

    override val attribution: String = ATTRIBUTION

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile private var session: String? = null
    @Volatile private var sessionExpiryMs: Long = 0L

    @Volatile var lastFailureReason: FailureReason? = null
        private set

    override suspend fun tile(key: TileKey): TileResult {
        if (apiKey.isBlank()) {
            return TileResult.Failure(FailureReason.NETWORK, "Map Tiles API key not configured")
        }
        cache.get(key)?.let { return TileResult.Success(it) }

        return withContext(Dispatchers.IO) {
            // One session, one refresh path: serialize session maintenance.
            var s = validSession()
            var result = fetchTile(s, key)
            if (result is TileResult.Failure && result.reason == FailureReason.SESSION_EXPIRED) {
                s = refreshSession()
                result = fetchTile(s, key)
            }
            result
        }
    }

    override fun invalidate() {
        session = null
        sessionExpiryMs = 0L
        cache.clear()
    }

    // ------------------------------------------------------------------ session

    private fun validSession(): String? {
        val s = session ?: return null
        // Refresh 5 minutes before the reported expiry.
        return if (System.currentTimeMillis() < sessionExpiryMs - SESSION_SAFETY_MS) s else null
    }

    private suspend fun refreshSession(): String? = mutex.withLock {
        val existing = validSession()
        if (existing != null) return existing
        try {
            val body = buildSessionJson()
            val request = Request.Builder()
                .url("$baseUrl/createSession?key=$apiKey")
                .post(body.toRequestBody(JSON_MEDIA))
                .header("Content-Type", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLog.d("TILE_SESSION", "session create HTTP ${response.code}")
                    return null
                }
                val text = response.body?.string() ?: return null
                val obj = json.parseToJsonElement(text) as? JsonObject ?: return null
                val token = obj["session"]?.jsonPrimitive?.content ?: return null
                val expirySec = obj["expiry"]?.jsonPrimitive?.content?.toLongOrNull()
                session = token
                sessionExpiryMs = (expirySec ?: (System.currentTimeMillis() / 1000 + 86400)) * 1000
                DebugLog.d("TILE_SESSION", "session acquired expirySec=$expirySec")
                token
            }
        } catch (e: IOException) {
            DebugLog.d("TILE_SESSION", "session create failed: ${e.message}")
            null
        }
    }

    private fun buildSessionJson() = """
        {"mapType":"roadmap","language":"$language","region":"$region","imageFormat":"$imageFormat"}
    """.trimIndent()

    // ------------------------------------------------------------------ tiles

    private fun fetchTile(session: String?, key: TileKey): TileResult {
        if (session == null) {
            return TileResult.Failure(FailureReason.NETWORK, "No tile session available")
        }
        val url = "$baseUrl/2dtiles/${key.z}/${key.x}/${key.y}?session=$session&key=$apiKey"
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val bytes = response.body?.bytes()
                        if (bytes == null || bytes.isEmpty()) {
                            TileResult.Failure(FailureReason.MALFORMED, "Empty tile body")
                        } else {
                            val maxAge = response.header("Cache-Control")?.let(::parseMaxAge)
                            cache.put(key, bytes, maxAge)
                            lastFailureReason = null
                            TileResult.Success(bytes)
                        }
                    }
                    response.code == 403 || response.code == 400 -> {
                        // The documented failure modes for an invalid/expired session are
                        // surfaced as PERMISSION_DENIED / badRequest style errors; treat any
                        // 4xx on a *session-scoped* request as a possible session problem and
                        // let the caller decide (expired ⇒ refresh once; otherwise fail).
                        val bodySnippet = try { response.body?.string()?.take(200) } catch (_: Exception) { null }
                        val expired = bodySnippet?.contains("expired", ignoreCase = true) == true ||
                            bodySnippet?.contains("session", ignoreCase = true) == true
                        val reason = if (expired) FailureReason.SESSION_EXPIRED else FailureReason.NETWORK
                        lastFailureReason = reason
                        TileResult.Failure(reason, "HTTP ${response.code}: $bodySnippet")
                    }
                    response.code == 429 -> {
                        lastFailureReason = FailureReason.QUOTA_EXCEEDED
                        TileResult.Failure(FailureReason.QUOTA_EXCEEDED, "HTTP 429 rate limited")
                    }
                    else -> {
                        lastFailureReason = FailureReason.NETWORK
                        TileResult.Failure(FailureReason.NETWORK, "HTTP ${response.code}")
                    }
                }
            }
        } catch (e: IOException) {
            lastFailureReason = FailureReason.NETWORK
            TileResult.Failure(FailureReason.NETWORK, e.message ?: "IO error")
        }
    }

    companion object {
        const val BASE_URL = "https://tile.googleapis.com/v1"
        const val ATTRIBUTION = "Map data © Google"
        val JSON_MEDIA = "application/json".toMediaType()
        const val HARD_TTL_MS = 10 * 60 * 1000L
        const val SESSION_SAFETY_MS = 5 * 60 * 1000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /** Parses `max-age=N` out of a Cache-Control header; null when absent/invalid. */
        fun parseMaxAge(cacheControl: String?): Long? {
            if (cacheControl == null) return null
            val part = cacheControl.split(';').map { it.trim() }.firstOrNull {
                it.startsWith("max-age=", ignoreCase = true)
            } ?: return null
            return part.removePrefix("max-age=").toLongOrNull()
        }
    }
}
