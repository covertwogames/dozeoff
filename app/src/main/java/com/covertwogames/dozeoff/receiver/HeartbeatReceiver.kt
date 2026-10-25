package com.covertwogames.dozeoff.receiver

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.covertwogames.dozeoff.MainActivity
import com.covertwogames.dozeoff.util.PrefsManager

class HeartbeatReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeartbeatReceiver"
        private const val WAKELOCK_TIMEOUT = 10000L // safety cap; released in finally

        // Three distinct alarms. Each needs its own action as well as its own
        // request code, because PendingIntent matching takes the action into
        // account and they must be cancellable independently.
        //
        //  ACTION_HEARTBEAT       periodic wake-up. Used by Balanced mode, while
        //                         paused for Do Not Disturb, and by Max while
        //                         the phone is plugged in.
        //  ACTION_SHORT_LEAD      Balanced mode's brief alarm clock, placed a
        //                         few seconds out to pull the device out of Doze.
        //  ACTION_STANDING_CLOCK  Max mode's standing alarm clock, always one
        //                         interval out. Android will not enter deep Doze
        //                         while a wake-from-idle alarm is due soon, so
        //                         this keeps the device out of Doze rather than
        //                         repeatedly pulling it out.
        const val ACTION_HEARTBEAT = "com.covertwogames.dozeoff.HEARTBEAT"
        const val ACTION_SHORT_LEAD = "com.covertwogames.dozeoff.SHORT_LEAD"
        const val ACTION_STANDING_CLOCK = "com.covertwogames.dozeoff.STANDING_CLOCK"

        private const val REQUEST_CODE_HEARTBEAT = 0
        private const val REQUEST_CODE_SHORT_LEAD = 1
        private const val REQUEST_CODE_STANDING = 2

        // How far ahead Balanced places its brief alarm clock. Long enough for
        // the system to act on it, short enough that it is almost never the
        // device's "next alarm" on the lock screen.
        private const val SHORT_LEAD_MS = 3000L

        // Android reports the Doze exit caused by the brief clock within a few
        // seconds of it being placed, before the clock itself fires. For this
        // long after placing one, the service's recovery checks leave the chain
        // alone, so they cannot cancel the clock before it has been counted.
        private const val SHORT_LEAD_GUARD_MS = 30_000L

        // When Balanced last placed its brief clock, on the elapsed-realtime
        // clock, or 0 once that clock has fired or been cancelled (both go
        // through cancelPulses). Held in memory only: the service that reads it
        // always runs in this same process, and a value from before a reboot
        // must never count.
        @Volatile
        private var lastShortLeadPlacedAt = 0L

        // True only while the service's charger watcher is registered. Max may
        // step back on a charger only when something is guaranteed to put the
        // standing clock back the moment the phone is unplugged; without the
        // watcher, the clock could be missing for up to an interval after
        // unplugging, long enough for deep Doze to start. In memory only, so
        // it resets by itself if the process dies.
        @Volatile
        var chargerWatcherActive = false

        fun isDndActive(context: Context): Boolean {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            return when (notificationManager.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                NotificationManager.INTERRUPTION_FILTER_NONE,
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
                else -> false
            }
        }

        /** Protection is on, but paused because DND / Bedtime Mode is active. */
        fun isPausedForDnd(context: Context): Boolean {
            val p = PrefsManager(context)
            return p.protectionLevel != PrefsManager.LEVEL_OFF &&
                    p.respectDnd && isDndActive(context)
        }

        /** Max selected and not paused. */
        fun isUsingMaxScheduling(context: Context): Boolean =
            PrefsManager(context).protectionLevel == PrefsManager.LEVEL_MAX &&
                    !isPausedForDnd(context)

        /** Balanced selected and not paused. */
        fun isUsingBalancedScheduling(context: Context): Boolean =
            PrefsManager(context).protectionLevel == PrefsManager.LEVEL_BALANCED &&
                    !isPausedForDnd(context)

        /**
         * Whether the phone is on any charger, wired, wireless or dock. This is
         * the same test Android's Doze uses (battery present and plugged), and
         * Doze never runs while it is true. Reads the battery status Android
         * keeps on hand, which needs no receiver and is allowed from any
         * context. If the status can't be read, reports unplugged, which keeps
         * Max doing exactly what it did before this check existed.
         */
        fun isPluggedIn(context: Context): Boolean {
            return try {
                val status = context.applicationContext.registerReceiver(
                    null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                ) ?: return false
                val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val present = status.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
                present && plugged != 0
            } catch (e: Exception) {
                Log.e(TAG, "Battery status unreadable, assuming unplugged: ${e.message}")
                false
            }
        }

        /**
         * True while Balanced's brief clock is pending: placed within the last
         * 30 seconds and not yet fired or cancelled. The service checks this
         * before re-arming the chain, because re-arming cancels that clock
         * before it fires.
         */
        fun shortLeadRecentlyPlaced(): Boolean {
            val placedAt = lastShortLeadPlacedAt
            if (placedAt == 0L) return false
            val since = SystemClock.elapsedRealtime() - placedAt
            return since in 0 until SHORT_LEAD_GUARD_MS
        }

        /**
         * Whether Max should have its standing clock in place right now.
         *
         * Max steps back while the phone is plugged in. Doze can't run on a
         * charger, so the standing clock would do nothing there except show as
         * the phone's next alarm and wake other apps that watch for alarm
         * changes. It only steps back while the service's charger watcher is
         * running, because that watcher is what puts the clock back the moment
         * the phone is unplugged.
         *
         * [pluggedInOverride] is for callers that have just been told the
         * charger state by Android itself (the power connected and disconnected
         * broadcasts), so the decision doesn't depend on how quickly Android
         * updates the battery status that would otherwise be read.
         */
        private fun wantsStandingClock(context: Context, pluggedInOverride: Boolean?): Boolean {
            if (!isUsingMaxScheduling(context)) return false
            if (!chargerWatcherActive) return true
            val pluggedIn = pluggedInOverride ?: isPluggedIn(context)
            return !pluggedIn
        }

        /** Whether the next pulse scheduled now would be Max's standing clock. */
        fun usesStandingClockNow(context: Context): Boolean =
            wantsStandingClock(context, null)

        /** Schedules the next alarm for the selected mode. */
        fun scheduleNextPulse(context: Context, pluggedInOverride: Boolean? = null) {
            val prefsManager = PrefsManager(context)
            if (prefsManager.protectionLevel == PrefsManager.LEVEL_OFF) return

            val intervalMs = prefsManager.pulseIntervalMinutes * 60 * 1000L
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            cancelPulses(context)

            if (wantsStandingClock(context, pluggedInOverride)) {
                placeStandingAlarmClock(context, alarmManager, intervalMs, prefsManager)
            } else {
                // Balanced, paused for DND, or Max on a charger. Either way the
                // next thing to happen is a plain wake-up; what it does is
                // decided when it fires, in onReceive.
                scheduleWakeUp(context, alarmManager, intervalMs, prefsManager)
            }
        }

        /** Max: standing alarm clock, one interval out. */
        private fun placeStandingAlarmClock(
            context: Context,
            alarmManager: AlarmManager,
            intervalMs: Long,
            prefsManager: PrefsManager
        ) {
            val intent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_STANDING_CLOCK
            }
            try {
                val pendingIntent = PendingIntent.getBroadcast(
                    context, REQUEST_CODE_STANDING, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(
                        System.currentTimeMillis() + intervalMs, showIntent(context)
                    ),
                    pendingIntent
                )
                prefsManager.isMaxVerified = true
                Log.d(TAG, "MAX: standing clock set ${prefsManager.pulseIntervalMinutes}m out")
            } catch (e: SecurityException) {
                // Device refuses alarm clocks. Fall back to a plain wake-up so
                // the chain survives rather than dying silently.
                Log.e(TAG, "MAX: setAlarmClock refused, falling back: ${e.message}")
                prefsManager.isMaxVerified = false
                scheduleWakeUp(context, alarmManager, intervalMs, prefsManager)
            }
        }

        /** Balanced: brief alarm clock, a few seconds out. */
        fun placeShortLeadAlarmClock(context: Context) {
            val prefsManager = PrefsManager(context)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_SHORT_LEAD
            }
            try {
                val pendingIntent = PendingIntent.getBroadcast(
                    context, REQUEST_CODE_SHORT_LEAD, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(
                        System.currentTimeMillis() + SHORT_LEAD_MS, showIntent(context)
                    ),
                    pendingIntent
                )
                lastShortLeadPlacedAt = SystemClock.elapsedRealtime()
                prefsManager.isMaxVerified = true
                Log.d(TAG, "BALANCED: short-lead clock placed")
            } catch (e: Exception) {
                // The brief clock was refused. This call is the only thing the
                // wake-up that triggered it was going to schedule, so without
                // a fallback the chain would end here. Schedule the next plain
                // wake-up instead, and leave the pulse uncounted: this cycle
                // did not pull the device out of Doze.
                Log.e(TAG, "BALANCED: setAlarmClock refused, falling back: ${e.message}")
                prefsManager.isMaxVerified = false
                val intervalMs = prefsManager.pulseIntervalMinutes * 60 * 1000L
                scheduleWakeUp(context, alarmManager, intervalMs, prefsManager)
            }
        }

        /**
         * The periodic wake-up. Exact rather than inexact: aggressive OEM
         * schedulers defer inexact allow-while-idle alarms heavily (observed
         * over 80 minutes late on one device) while honouring exact ones.
         */
        private fun scheduleWakeUp(
            context: Context,
            alarmManager: AlarmManager,
            intervalMs: Long,
            prefsManager: PrefsManager
        ) {
            val intent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_HEARTBEAT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE_HEARTBEAT, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + intervalMs,
                    pendingIntent
                )
                Log.d(TAG, "Wake-up scheduled ${prefsManager.pulseIntervalMinutes}m out")
            } catch (e: SecurityException) {
                // Android refuses exact alarms when DozeOff has neither the
                // Alarms & reminders permission nor a battery exemption. Log
                // and stop rather than crash. Nothing is scheduled, so the
                // dashboard reports "Protection Not Working" until the user
                // restores the permission, at which point the chain is re-armed.
                Log.e(TAG, "Exact alarm refused, nothing scheduled: ${e.message}")
            }
        }

        /** Where the alarm indicator points, on devices that show one. */
        private fun showIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun cancelPulses(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            for ((code, act) in listOf(
                REQUEST_CODE_HEARTBEAT to ACTION_HEARTBEAT,
                REQUEST_CODE_SHORT_LEAD to ACTION_SHORT_LEAD,
                REQUEST_CODE_STANDING to ACTION_STANDING_CLOCK
            )) {
                val i = Intent(context, HeartbeatReceiver::class.java).apply { action = act }
                alarmManager.cancel(PendingIntent.getBroadcast(context, code, i, flags))
            }
            // Any brief clock is gone now, whether it fired (its handler
            // reschedules through here) or was cancelled.
            lastShortLeadPlacedAt = 0L
            Log.d(TAG, "All pulses cancelled")
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != ACTION_HEARTBEAT &&
            action != ACTION_SHORT_LEAD &&
            action != ACTION_STANDING_CLOCK
        ) return

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DozeOff::HeartbeatWakeLock"
        )
        wakeLock.acquire(WAKELOCK_TIMEOUT)

        try {
            val prefsManager = PrefsManager(context)
            val idleNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isDeviceIdleMode
            } else false

            when (action) {
                ACTION_STANDING_CLOCK -> {
                    // Max. The standing clock is itself the pulse. Rescheduling
                    // immediately means a clock is always pending, which is what
                    // keeps the device out of deep Doze rather than repeatedly
                    // pulling it out.
                    Log.d(TAG, "MAX: clock fired, idle=$idleNow")
                    countPulse(prefsManager)
                    scheduleNextPulse(context)
                }

                ACTION_SHORT_LEAD -> {
                    // Balanced. This is the alarm that pulled the device out.
                    Log.d(TAG, "BALANCED: clock fired, idle=$idleNow")
                    countPulse(prefsManager)
                    scheduleNextPulse(context)
                }

                else -> {
                    if (isUsingBalancedScheduling(context) && idleNow) {
                        // Dozing: place the brief clock to pull the device out.
                        // The pulse is counted when that clock fires.
                        placeShortLeadAlarmClock(context)
                    } else {
                        countPulse(prefsManager)
                        scheduleNextPulse(context)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Pulse failed: ${e.message}")
        } finally {
            try {
                if (wakeLock.isHeld) wakeLock.release()
            } catch (e: Exception) {
                Log.e(TAG, "Wakelock release failed: ${e.message}")
            }
        }
    }

    private fun countPulse(prefsManager: PrefsManager) {
        prefsManager.lastPulseTime = System.currentTimeMillis()
        prefsManager.incrementPulseCount()
    }
}
