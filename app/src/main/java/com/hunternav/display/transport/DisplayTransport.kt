package com.hunternav.display.transport

import com.hunternav.core.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Sink for encoded [FrameProtocol] messages destined for the handlebar display.
 *
 * The transport is deliberately dumb: it ships opaque binary protocol messages and reports
 * connectivity. Framing, sequencing and encoding all happen upstream in the pipeline.
 */
interface DisplayTransport {
    val connected: StateFlow<Boolean>
    fun connect()
    fun disconnect()

    /** Queues one protocol message. Returns false when the transport is not connected. */
    fun send(bytes: ByteArray): Boolean
}

/**
 * WebSocket client transport: the ESP32 runs a WebSocket **server** on its soft-AP or the
 * shared LAN, the phone connects and pushes binary frames (`FrameProtocol` messages). One
 * binary WebSocket message = one protocol message — JPEG is never base64'd.
 *
 * Reconnects with capped exponential backoff while [connect] has been called; each new
 * connection restarts the caller's sequence numbering via the pipeline (sequence is
 * per-connection by protocol contract, and the receiver uses gaps only within a connection).
 */
class WebSocketDisplayTransport(
    private val wsUrl: String,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build(),
) : DisplayTransport {

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var wantConnection = false
    private var attempts = 0

    override fun connect() {
        wantConnection = true
        attempts = 0
        open()
    }

    override fun disconnect() {
        wantConnection = false
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(CLOSE_NORMAL, "bye")
        webSocket = null
        _connected.value = false
    }

    override fun send(bytes: ByteArray): Boolean {
        val ws = webSocket ?: return false
        return ws.send(bytes.toByteString())
    }

    private fun open() {
        if (!wantConnection) return
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    _connected.value = true
                    attempts = 0
                    DebugLog.d(TAG, "display connected: $wsUrl")
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(code, reason)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    _connected.value = false
                    webSocket = null
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    _connected.value = false
                    webSocket = null
                    DebugLog.d(TAG, "display transport failure: ${t.message}")
                    scheduleReconnect()
                }
            },
        )
    }

    private fun scheduleReconnect() {
        if (!wantConnection || reconnectJob?.isActive == true) return
        val backoffMs = (BASE_BACKOFF_MS shl attempts.coerceAtMost(MAX_SHIFT)).coerceAtMost(MAX_BACKOFF_MS)
        attempts += 1
        reconnectJob = scope.launch {
            delay(backoffMs)
            open()
        }
    }

    companion object {
        private const val TAG = "DisplayTransport"
        const val CLOSE_NORMAL = 1000
        const val BASE_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 8_000L
        const val MAX_SHIFT = 4
    }
}
