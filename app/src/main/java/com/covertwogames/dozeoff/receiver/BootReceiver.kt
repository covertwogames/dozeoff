package com.covertwogames.dozeoff.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.covertwogames.dozeoff.service.DozeOffService
import com.covertwogames.dozeoff.util.PrefsManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }

        Log.d(TAG, "Boot completed, checking if DozeOff should restart")

        val prefsManager = PrefsManager(context)
        if (prefsManager.protectionLevel == PrefsManager.LEVEL_OFF) {
            Log.d(TAG, "Protection is off, not starting service")
            return
        }

        Log.d(TAG, "Starting DozeOff service after boot")
        val serviceIntent = Intent(context, DozeOffService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
