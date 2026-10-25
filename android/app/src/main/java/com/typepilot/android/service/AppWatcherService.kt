package com.typepilot.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.typepilot.android.R
import com.typepilot.android.TypePilotApp
import com.typepilot.android.core.Log
import com.typepilot.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs only while TypePilot's accessibility service is switched off for an app (a payment app, or any app outside
 * your selected list). Checks the app in front every ~0.8 s while the screen is on and switches the service back on
 * as soon as you're in an app where TypePilot should work. Then it stops itself.
 */
class AppWatcherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_APP).orEmpty()
        startForeground(NOTIFICATION_ID, notification(label), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (job?.isActive != true) job = scope.launch { watch() }
        return START_STICKY
    }

    private fun restore(by: String) {
        com.typepilot.android.telemetry.Usage.expectAccessibilityChange("smart_switch")
        val ok = A11ySwitch.turnOn(this)
        com.typepilot.android.telemetry.Usage.count(if (ok) "smart_switch_restored" else "smart_switch_failed", if (ok) mapOf("by" to by) else mapOf("op" to "on"))
    }

    private suspend fun watch() {
        val app = TypePilotApp.instance
        val power = getSystemService(PowerManager::class.java)
        while (scope.isActive) {
            delay(800)
            if (!power.isInteractive) continue
            val s = app.settings.value
            if (!s.smartSwitch || !A11ySwitch.canToggle(this)) { restore("watcher_disabled"); break }
            val fg = A11ySwitch.foregroundPackage(this) ?: continue
            if (fg == packageName) continue
            if (!s.hiddenIn(fg)) {
                restore("watcher")
                SwitchRecovery.cancelBackup(this)
                Log.i("switch", "back on: $fg")
                break
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(label: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "TypePilot paused in an app", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while TypePilot is fully off inside a payment or banking app"
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (label.isBlank()) "TypePilot is off here" else "TypePilot is off in $label")
            .setContentText("It turns back on when you leave this app.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "smart_switch"
        private const val NOTIFICATION_ID = 42
        const val EXTRA_APP = "app"

        fun start(context: Context, appLabel: String) {
            context.startForegroundService(Intent(context, AppWatcherService::class.java).putExtra(EXTRA_APP, appLabel))
        }
    }
}
