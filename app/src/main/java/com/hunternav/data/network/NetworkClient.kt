package com.hunternav.data.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Central HTTP client: timeouts, cancellation, one shared connection pool. */
class NetworkClient(routing: Boolean = false) : HttpTransport {

    // Routing requests get tighter bounds than geocoding (spec P2): a hung reroute must fail
    // fast so the engine's cooldown can retry, instead of the rider waiting on a 20 s+ timeout.
    // Worst case with 2 retries + backoff: ~3 × 12 s + 2.1 s ≈ 38 s, with a visible
    // "Rerouting…"/failure ribbon the whole time.
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(if (routing) 5 else 10, TimeUnit.SECONDS)
        .readTimeout(if (routing) 10 else 15, TimeUnit.SECONDS)
        .writeTimeout(if (routing) 10 else 10, TimeUnit.SECONDS)
        .callTimeout(if (routing) 12 else 20, TimeUnit.SECONDS)
        .build()

    /** Executes [request] asynchronously, resuming the coroutine with the response. */
    override suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = okHttpClient.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response)
            }
        })
    }

    companion object {
        /** Standard User-Agent identifying the app on REST calls. */
        const val USER_AGENT = "HunterNav/0.1 (open-source prototype)"
    }
}
