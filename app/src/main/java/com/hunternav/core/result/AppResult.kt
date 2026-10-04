package com.hunternav.core.result

/** Domain-level error kinds. UI maps these to human-readable messages; no raw exceptions leak upward. */
enum class AppErrorKind {
    NETWORK,
    TIMEOUT,
    SERVER,
    PARSE,
    UNREACHABLE_DESTINATION,
    LOCATION_UNAVAILABLE,
    NO_ROUTE,
    UNKNOWN,
}

/** Lightweight Result wrapper for repository/provider calls. */
sealed class AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>()
    data class Failure(val kind: AppErrorKind, val message: String? = null, val cause: Throwable? = null) : AppResult<Nothing>()

    inline fun onSuccess(block: (T) -> Unit): AppResult<T> {
        if (this is Success) block(value)
        return this
    }

    inline fun onFailure(block: (AppErrorKind, String?) -> Unit): AppResult<T> {
        if (this is Failure) block(kind, message)
        return this
    }
}

/** Maps a thrown exception to a domain error kind (network stack independent). */
fun Throwable.toAppErrorKind(): AppErrorKind = when (this) {
    is java.io.IOException -> AppErrorKind.NETWORK
    is java.util.concurrent.TimeoutException -> AppErrorKind.TIMEOUT
    is kotlinx.serialization.SerializationException -> AppErrorKind.PARSE
    else -> AppErrorKind.UNKNOWN
}
