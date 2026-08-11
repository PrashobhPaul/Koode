package com.trippulse.app.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.trippulse.app.TripPulseApp
import com.trippulse.app.domain.Fix
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns continuous location tracking for an active trip
 * (docs/spec/54, 95). Started from an explicit user action (START TRIP) while
 * the app is visible, per Android's background-start restrictions. Adapts the
 * GPS sampling interval to the journey state and battery, and stops itself when
 * the trip completes.
 */
class TripTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var fused: FusedLocationProviderClient
    private var tickJob: Job? = null
    private var currentIntervalMs: Long = 15_000
    private var arRegistered = false

    private val graph get() = (application as TripPulseApp).graph

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val battery = readBatteryPct()
            val fix = Fix(
                point = GeoPoint(loc.latitude, loc.longitude),
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else 999f,
                speedMps = if (loc.hasSpeed()) loc.speed else null,
                bearing = if (loc.hasBearing()) loc.bearing else null,
                timeMs = System.currentTimeMillis(),
                batteryPct = battery
            )
            scope.launch {
                graph.tripManager.onLocation(fix)
                maybeAdjustInterval()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        graph.tripManager.onSamplingChanged = {
            scope.launch { maybeAdjustInterval() }
        }
        graph.tripManager.onStopTrackingRequested = {
            stopTracking()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Load active trip and go foreground immediately.
        scope.launch {
            val trip = graph.tripManager.loadActive()
            val origin = trip?.originName ?: "Trip"
            val dest = trip?.destName ?: ""
            startForegroundSafely(graph.notifier.buildTrackingNotification(origin, dest))
            startLocationUpdates()
            startTicker()
            registerActivityUpdates()
        }
        return START_STICKY
    }

    private fun startForegroundSafely(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, Notifier.NOTIF_TRACKING, notification, type)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        currentIntervalMs = graph.tripManager.currentSamplingIntervalMs()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentIntervalMs)
            .setMinUpdateIntervalMillis(currentIntervalMs / 2)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {
        }
    }

    private fun maybeAdjustInterval() {
        if (!hasLocationPermission()) return
        val desired = graph.tripManager.currentSamplingIntervalMs()
        if (desired != currentIntervalMs) {
            currentIntervalMs = desired
            fused.removeLocationUpdates(locationCallback)
            startLocationUpdates()
        }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(30_000)
                graph.tripManager.onTick()
            }
        }
    }

    private fun registerActivityUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val client = ActivityRecognition.getClient(this)
            val pi = ActivityTransitionReceiver.pendingIntent(this)
            client.requestActivityUpdates(20_000, pi)
            arRegistered = true
        } catch (_: SecurityException) {
        }
    }

    private fun readBatteryPct(): Int? {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) pct else null
    }

    private fun stopTracking() {
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        if (arRegistered) {
            try {
                ActivityRecognition.getClient(this)
                    .removeActivityUpdates(ActivityTransitionReceiver.pendingIntent(this))
            } catch (_: Exception) {}
        }
        tickJob?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: android.content.Context) {
            val i = Intent(context, TripTrackingService::class.java)
            ContextCompat.startForegroundService(context, i)
        }
        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, TripTrackingService::class.java))
        }

        /** Maps a DetectedActivity type to an in-vehicle hint. */
        fun isInVehicle(activityType: Int): Boolean = activityType == DetectedActivity.IN_VEHICLE
    }
}
