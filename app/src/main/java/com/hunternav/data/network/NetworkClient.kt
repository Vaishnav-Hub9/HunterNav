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
class NetworkClient {

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Executes [request] asynchronously, resuming the coroutine with the response. */
    suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
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
        /** Standard User-Agent identifying the app (Nominatim usage policy requires one). */
        const val USER_AGENT = "HunterNav/0.1 (open-source prototype)"
    }
}
