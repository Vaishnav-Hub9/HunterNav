package com.hunternav.display

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.hunternav.HunterNavApplication
import com.hunternav.R
import com.hunternav.core.util.DebugLog

/**
 * Foreground service that owns the handlebar display pipeline while a ride is in progress.
 *
 * Purpose (the "phone stays in the pocket" requirement): Android suspends location delivery,
 * throttles coroutines and can kill WebSockets once the app is backgrounded and the screen is
 * off — unless work runs inside a foreground service. This service:
 *
 *  - promotes itself to a **location** foreground service (the documented way to keep Fused
 *    Location delivering while backgrounded, per Android 10+ background-location policy),
 *  - holds a **partial wake lock** so the CPU keeps rendering/encoding frames at ~4 Hz,
 *  - holds a **high-performance Wi-Fi lock** so the Wi-Fi radio does not power-save the
 *    WebSocket link to the ESP32 while the screen is off,
 *  - starts/stops the shared [DisplayPipeline] from [HunterNavApplication.container].
 *
 * Started from the active-navigation screen (and restarts itself after a process death via
 * START_STICKY). Nothing else in the app calls `displayPipeline.start()` — this service is
 * the single owner, so the pipeline and the UI can never fight over the transport.
 */
class DisplayForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_display)
            .setContentTitle("Handlebar display streaming")
            .setContentText("Navigation frames are being sent to the bike display.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HunterNav:display").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HunterNav:display").apply {
            setReferenceCounted(false)
            acquire()
        }

        val pipeline = (application as HunterNavApplication).container.displayPipeline
        pipeline.start()
        DebugLog.d(TAG, "display foreground service started (provider=${pipeline.activeProvider.providerName})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Screen turned off / app backgrounded: stay alive (this is the normal riding state).
        return START_STICKY
    }

    override fun onDestroy() {
        val app = application as? HunterNavApplication
        app?.container?.displayPipeline?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
        DebugLog.d(TAG, "display foreground service stopped")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Handlebar display",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows while navigation frames stream to the bike display" },
        )
    }

    companion object {
        private const val TAG = "DisplayFgService"
        const val CHANNEL_ID = "display_streaming"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.hunternav.display.STOP"

        /**
         * Starts the display streaming service. Idempotent: Android delivers a second
         * onStartCommand rather than creating a second service instance.
         */
        fun start(context: Context) {
            val intent = Intent(context, DisplayForegroundService::class.java)
            context.startForegroundService(intent)
        }

        /** Stops the display streaming service (navigation ended). */
        fun stop(context: Context) {
            val intent = Intent(context, DisplayForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
