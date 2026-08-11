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
