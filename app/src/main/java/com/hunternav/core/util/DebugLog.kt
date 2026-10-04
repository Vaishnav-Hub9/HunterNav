package com.hunternav.core.util

import android.util.Log
import com.hunternav.BuildConfig

/**
 * Temporary stage tracing for the destination → route flow.
 *
 * Each stage is emitted as its own logcat **tag** so a device run can be filtered with:
 *
 * ```
 * adb logcat -s DESTINATION_SELECTED:V DESTINATION_COORDINATES:V GEOCODING_REQUEST:V \
 *             GEOCODING_RESULT:V ROUTE_REQUEST:V ROUTE_RESPONSE:V ROUTE_PREVIEW_STATE:V
 * ```
 *
 * The message body is prefixed with `HunterNav` so a plain `adb logcat | grep HunterNav`
 * shows the whole flow in order.
 *
 * Silent in release builds. **Remove this logging once the destination-loss issue is
 * confirmed fixed on a real device.**
 */
object DebugLog {

    private const val MARKER = "HunterNav"

    fun d(stage: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(stage, "$MARKER | $message")
        }
    }
}
