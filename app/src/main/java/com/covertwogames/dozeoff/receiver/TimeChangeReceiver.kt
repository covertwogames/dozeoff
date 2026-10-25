package com.covertwogames.dozeoff.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.covertwogames.dozeoff.util.PrefsManager

/**
 * Puts wall-clock alarms back in the right place when the phone's clock is
 * changed.
 *
 * Alarm clocks can only be set in wall-clock time. If the clock jumps
 * backward, a pending alarm clock lands further in the future than intended.
 * DozeOff has two of them, and each is handled here:
 *
 *  - Max's standing clock: deep Doze would be allowed again until it fired.
 *    Re-arming puts it back one interval from the new time.
 *  - Balanced's brief 3-second clock, if one is pending: it would stop
 *    pulling the phone out of Doze, and the chain waits on it. Placing it
 *    again makes it fire 3 seconds from the new time.
 *
 * Plain wake-ups run on the phone's uptime clock, which a clock change doesn't
 * move, so nothing else is touched. Re-arming them would only push the next
 * one back by up to an interval. Time zone changes don't move the underlying
 * time, so they are not handled at all.
 */
class TimeChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimeChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_TIME_CHANGED) return

        try {
            if (PrefsManager(context).protectionLevel == PrefsManager.LEVEL_OFF) return

            if (HeartbeatReceiver.usesStandingClockNow(context)) {
                Log.d(TAG, "System time changed, re-arming Max's standing clock")
                HeartbeatReceiver.scheduleNextPulse(context)
            } else if (HeartbeatReceiver.isUsingBalancedScheduling(context) &&
                HeartbeatReceiver.shortLeadRecentlyPlaced()
            ) {
                Log.d(TAG, "System time changed, re-placing Balanced's brief clock")
                HeartbeatReceiver.placeShortLeadAlarmClock(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Re-arm after time change failed: ${e.message}")
        }
    }
}
