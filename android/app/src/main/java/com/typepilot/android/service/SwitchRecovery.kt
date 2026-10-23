package com.typepilot.android.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.typepilot.android.TypePilotApp
import com.typepilot.android.core.Log

/**
 * Makes sure the smart switch can never leave TypePilot switched off. Whatever happens to the watcher (app
 * update, phone maker killing background apps, reboot), one of these brings accessibility back:
 *  - this receiver on app update and on boot;
 *  - a backup alarm about every minute while the service is switched off;
 *  - [ensureOn] whenever the app process starts.
 */
class SwitchRecovery : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("switch", "recovery check: ${intent.action ?: "alarm"}")
        ensureOn(context, reason = intent.action ?: "alarm")
    }

    companion object {
        private const val REQUEST = 7

        /** Turn accessibility back on unless the app in front is one where it must stay off. */
        fun ensureOn(context: Context, reason: String) {
            val app = context.applicationContext
            if (!A11ySwitch.canToggle(app) || A11ySwitch.isOn(app)) { cancelBackup(app); return }
            val s = TypePilotApp.instance.settings.value
            val fg = runCatching { A11ySwitch.foregroundPackage(app) }.getOrNull()
            if (s.smartSwitch && fg != null && s.hiddenIn(fg)) {
                // Still inside a payment app: check again shortly.
                AppWatcherService.start(app, fg)
                scheduleBackup(app)
                return
            }
            A11ySwitch.turnOn(app)
            cancelBackup(app)
            Log.i("switch", "accessibility restored ($reason)")
        }

        private fun pending(context: Context) = PendingIntent.getBroadcast(context, REQUEST,
            Intent(context, SwitchRecovery::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Inexact (~1 min) and battery-friendly; it only exists while the service is switched off. */
        fun scheduleBackup(context: Context) {
            context.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 60_000, pending(context))
        }

        fun cancelBackup(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(pending(context))
    }
}
