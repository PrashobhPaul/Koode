package com.trippulse.app.service

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognitionResult
import com.trippulse.app.TripPulseApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts tracking after a device reboot if a trip is still active
 * (docs/spec/82, 95). Starting a location foreground service directly from
 * boot is only permitted when background location is granted; otherwise we post
 * a resume hint the driver can tap.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as TripPulseApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val trip = app.graph.db.tripDao().activeTrip()
                if (trip != null) {
                    val bgGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    if (bgGranted) {
                        TripTrackingService.start(context)
                    } else {
                        app.graph.notifier.showResumeHint(trip.originName, trip.destName)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Receives Activity Recognition updates and feeds an in-vehicle hint to the
 * stop detector. This is a corroborating signal only; tracking never depends
 * on it.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val top = result.mostProbableActivity ?: return
        val inVehicle = TripTrackingService.isInVehicle(top.type) && top.confidence >= 60
        val app = context.applicationContext as TripPulseApp
        CoroutineScope(Dispatchers.Default).launch {
            app.graph.tripManager.onActivityHint(inVehicle)
        }
    }

    companion object {
        fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ActivityTransitionReceiver::class.java)
                .setAction("com.trippulse.app.ACTIVITY_UPDATE")
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            return PendingIntent.getBroadcast(context, 42, intent, flags)
        }
    }
}

/**
 * The last thing the app does before the phone goes off.
 *
 * Android broadcasts ACTION_SHUTDOWN on an orderly power-off or restart and
 * gives receivers a few seconds. That window is the only chance the app will
 * ever have to say where it was, so this does the smallest useful thing in it:
 * write the position and battery down, then try to push.
 *
 * What this deliberately does *not* claim to catch: a held power button on
 * many devices, a pulled battery, a phone destroyed or dropped in water, or a
 * force-stop. Those produce no broadcast at all, and pretending otherwise
 * would be the worst kind of dishonesty in a safety app. They are covered the
 * only way they can be -- by the family's device noticing the silence, which
 * is domain/Darkness.kt and TripFollowService, and which keeps working
 * precisely because it does not run here.
 */
class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SHUTDOWN_ACTIONS) return
        val app = context.applicationContext as? TripPulseApp ?: return
        val restart = action == Intent.ACTION_REBOOT

        // goAsync buys us the broadcast's remaining budget rather than
        // returning immediately and losing the write.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (app.graph.db.tripDao().activeTrip() != null) {
                    app.graph.tripManager.loadActive()
                    app.graph.tripManager.recordShutdown(restart)
                }
            } catch (_: Exception) {
                // Nothing useful to do with a failure here: the system is
                // going down either way, and throwing would only lose the
                // part that did get written.
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val SHUTDOWN_ACTIONS = setOf(
            Intent.ACTION_SHUTDOWN,
            Intent.ACTION_REBOOT,
            // Several OEMs send their own instead of the standard one.
            "android.intent.action.QUICKBOOT_POWEROFF",
            "com.htc.intent.action.QUICKBOOT_POWEROFF"
        )
    }
}
