package com.hunternav.display.nav

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo

/**
 * Receives the Google turn-by-turn feed. The Navigation SDK binds to this service after
 * `Navigator.registerServiceForNavUpdates(packageName, className, numSteps)` and delivers one
 * [NavInfo] message roughly per second while guidance runs.
 *
 * The service decodes SDK types into the SDK-free [NavFeedSnapshot] immediately and republishes
 * it on [NavFeedBus], a process-wide channel the display adapter subscribes to. This keeps all
 * SDK surface area confined to this one class.
 *
 * Declared in AndroidManifest.xml as `com.hunternav.display.nav.NavFeedService`.
 */
class NavFeedService : Service() {

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != TurnByTurnManager.MSG_NAV_INFO) {
                super.handleMessage(msg)
                return
            }
            val navInfo: NavInfo? = try {
                manager.readNavInfoFromBundle(msg.getData())
            } catch (e: RuntimeException) {
                null // malformed bundle from the SDK must never crash the app
            }
            if (navInfo != null) {
                NavFeedBus.publish(navInfo.toSnapshot())
            }
        }
    }

    private val messenger = android.os.Messenger(handler)
    private val manager: TurnByTurnManager = TurnByTurnManager.createInstance()

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    companion object {
        /** Service class name passed to `registerServiceForNavUpdates`. */
        val CLASS_NAME: String = NavFeedService::class.java.name
    }
}

/** Process-wide fan-out for decoded turn-by-turn snapshots. */
object NavFeedBus {
    @Volatile private var listener: ((NavFeedSnapshot) -> Unit)? = null

    fun subscribe(l: ((NavFeedSnapshot) -> Unit)) {
        listener = l
    }

    fun unsubscribe() {
        listener = null
    }

    fun publish(snapshot: NavFeedSnapshot) {
        listener?.invoke(snapshot)
    }
}

/** Maps SDK [NavInfo] to the plain-data [NavFeedSnapshot]. Null-safe on every getter. */
internal fun NavInfo.toSnapshot(): NavFeedSnapshot {
    val step = try { currentStep } catch (e: RuntimeException) { null }
    return NavFeedSnapshot(
        navState = navState,
        maneuver = step?.maneuver ?: 0,
        roadName = step?.fullRoadName ?: step?.simpleRoadName,
        instructionText = step?.fullInstructionText,
        distanceToStepMeters = distanceToCurrentStepMeters,
        timeToStepSeconds = timeToCurrentStepSeconds,
        remainingMeters = distanceToFinalDestinationMeters,
        remainingSeconds = timeToFinalDestinationSeconds,
        routeChanged = routeChanged,
        timestampMs = System.currentTimeMillis(),
    )
}
