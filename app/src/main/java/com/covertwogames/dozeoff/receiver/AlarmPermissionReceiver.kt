package com.covertwogames.dozeoff.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.covertwogames.dozeoff.service.DozeOffService
import com.covertwogames.dozeoff.util.HealthChecker
import com.covertwogames.dozeoff.util.PrefsManager

/**
 * Restores protection when the Alarms & reminders permission is turned back on.
 *
 * When that permission is turned off, Android deletes DozeOff's alarms and
 * stops the app. When it's turned back on, from DozeOff's Fix button or
 * straight from system Settings, Android sends this broadcast, and while
 * handling it the app is allowed to start its foreground service. Android
 * never sends it when the permission is turned off. It exists from Android 12.
 */
class AlarmPermissionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmPermissionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (intent?.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return

        try {
            val prefsManager = PrefsManager(context)

            // Nothing to restore. This includes onboarding, where the
            // permission is granted before protection is switched on.
            if (prefsManager.protectionLevel == PrefsManager.LEVEL_OFF) return

            if (HealthChecker.isServiceRunning(context)) {
                // Service survived; only the alarms need putting back. A full
                // restart would zero the pulse count and could cancel a
                // Balanced brief clock that is about to fire.
                Log.d(TAG, "Alarm permission granted, re-arming chain")
                HeartbeatReceiver.scheduleNextPulse(context)
            } else {
                // Service was stopped along with the app. Starting it also
                // arms the chain.
                Log.d(TAG, "Alarm permission granted, restarting service")
                context.startForegroundService(Intent(context, DozeOffService::class.java))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not restore protection: ${e.message}")
        }
    }
}
