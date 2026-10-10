package com.hunternav.data.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.Response

/**
 * Minimal HTTP abstraction over [NetworkClient]. Providers depend on this interface so unit
 * tests can exercise request handling, retries and error mapping without a real socket.
 */
interface HttpTransport {
    /** Executes [request] asynchronously, resuming the coroutine with the response. */
    suspend fun execute(request: Request): Response
}

/**
 * Enforces a minimum interval between request *starts* — the public Nominatim usage policy
 * allows at most one request per second, and the public OSRM demo server should be treated
 * the same way. Reservation happens under a mutex (so concurrent callers are spaced correctly)
 * but the network call itself runs outside the lock, so a slow request never deadlocks the
 * next one; each caller still starts at least [minIntervalMs] after the previous one.
 *
 * Time is read through [nowMs] and waiting uses [delay], so tests can drive it with
 * kotlinx-coroutines-test virtual time.
 */
class RequestRateLimiter(
    private val minIntervalMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var lastPermitAtMs: Long? = null

    /** Runs [block] after acquiring a rate-limit permit. */
    suspend fun <T> withPermit(block: suspend () -> T): T {
        mutex.withLock {
            val last = lastPermitAtMs
            if (last != null) {
                val waitMs = last + minIntervalMs - nowMs()
                if (waitMs > 0) delay(waitMs)
            }
            lastPermitAtMs = nowMs()
        }
        return block()
    }
}
