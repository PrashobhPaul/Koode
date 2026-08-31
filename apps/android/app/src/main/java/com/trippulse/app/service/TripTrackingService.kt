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
import com.google.android.gms.tasks.CancellationTokenSource
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

    /**
     * When the last fix actually arrived, and when we last forced a sample to
     * be stored. Both are wall-clock and both are written from the location
     * callback, so "stale" means what a traveller would mean by it.
     */
    @Volatile private var lastFixAtMs: Long = 0L
    @Volatile private var lastStoredAtMs: Long = 0L
    private var recoveries = 0

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
            lastFixAtMs = fix.timeMs
            lastStoredAtMs = fix.timeMs
            recoveries = 0
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

    private fun startLocationUpdates(degraded: Boolean = false) {
        if (!hasLocationPermission()) return
        currentIntervalMs = graph.tripManager.currentSamplingIntervalMs()
        val priority =
            if (degraded) Priority.PRIORITY_BALANCED_POWER_ACCURACY
            else Priority.PRIORITY_HIGH_ACCURACY
        val request = LocationRequest.Builder(priority, currentIntervalMs)
            .setMinUpdateIntervalMillis(currentIntervalMs / 2)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            // Start the staleness clock from the request, not from zero, so the
            // watchdog measures "no fix since we asked" rather than firing
            // immediately on a cold start.
            val now = System.currentTimeMillis()
            if (lastFixAtMs == 0L) lastFixAtMs = now
            if (lastStoredAtMs == 0L) lastStoredAtMs = now
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
                delay(TICK_MS)
                graph.tripManager.onTick()
                watchdog()
            }
        }
    }

    /**
     * Notices that fixes have stopped arriving, and does something about it.
     *
     * This exists because of a real journey: tracking stopped overnight in
     * Bangalore and never came back, all the way home, on a phone with working
     * network the whole time. The cause was structural rather than incidental
     * -- the only code that re-requested location updates lived *inside* the
     * location callback, so the recovery path could only run when the thing it
     * was meant to recover was already working. Doze, or an OEM battery
     * manager, only had to interrupt delivery once and nothing would ever ask
     * again.
     *
     * So the check lives here, on the ticker, which runs on its own clock and
     * does not care whether a fix ever arrives. Two things can be wrong, and
     * they need different answers:
     *
     *  - Fixes have gone quiet. Re-subscribe, and ask for one fix outright.
     *  - Fixes are arriving but the journey is parked, so nothing is being
     *    written. Store one anyway, because an hour of silence in the timeline
     *    is indistinguishable from an hour of lost tracking to whoever is
     *    watching, and the travelled line needs the point either way.
     */
    private suspend fun watchdog() {
        if (!hasLocationPermission()) return
        val now = System.currentTimeMillis()

        // Two missed cycles is noise; three is a pattern. The floor keeps a
        // fast cadence from re-subscribing over a tunnel or a bad minute.
        val staleAfter = maxOf(currentIntervalMs * 3, MIN_STALE_MS)
        if (now - lastFixAtMs > staleAfter) {
            recoveries++
            resubscribe()
            requestSingleFix()
            // Treat the attempt as the new reference point, otherwise every
            // subsequent tick re-fires while the request is still in flight.
            lastFixAtMs = now
            return
        }

        if (now - lastStoredAtMs >= BREADCRUMB_MS) requestSingleFix()
    }

    /** Tears the subscription down and builds it again from scratch. */
    private fun resubscribe() {
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        // After repeated failures stop insisting on high accuracy: a fix that
        // Doze will actually deliver beats a precise one it keeps withholding.
        startLocationUpdates(degraded = recoveries >= DEGRADE_AFTER)
    }

    /**
     * Asks for one fix now, outside the subscription.
     *
     * This is what puts a point on the map during a long stop and what proves
     * the provider is alive after a gap; it goes through the same callback, so
     * a success also resets the staleness clock.
     */
    private fun requestSingleFix() {
        if (!hasLocationPermission()) return
        val priority =
            if (recoveries >= DEGRADE_AFTER) Priority.PRIORITY_BALANCED_POWER_ACCURACY
            else Priority.PRIORITY_HIGH_ACCURACY
        try {
            fused.getCurrentLocation(priority, CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    if (loc == null) return@addOnSuccessListener
                    val fix = Fix(
                        point = GeoPoint(loc.latitude, loc.longitude),
                        accuracyM = if (loc.hasAccuracy()) loc.accuracy else 999f,
                        speedMps = if (loc.hasSpeed()) loc.speed else null,
                        bearing = if (loc.hasBearing()) loc.bearing else null,
                        timeMs = System.currentTimeMillis(),
                        batteryPct = readBatteryPct()
                    )
                    lastFixAtMs = fix.timeMs
                    lastStoredAtMs = fix.timeMs
                    recoveries = 0
                    scope.launch { graph.tripManager.onLocation(fix) }
                }
        } catch (_: SecurityException) {
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

    /**
     * The app was swiped out of recents while a journey was running.
     *
     * Swiping away the task is not "end my journey" -- the whole point of the
     * app is that it keeps its promise while nobody is looking at it -- so the
     * service asks to be brought back. START_STICKY alone is not enough here:
     * some launchers tear the process down hard enough that the sticky restart
     * never comes.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        scope.launch {
            if (graph.db.tripDao().activeTrip() != null) start(applicationContext)
        }
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        /** How often the ticker wakes to advance state and check for silence. */
        private const val TICK_MS = 30_000L

        /**
         * The shortest silence worth reacting to. Below this, a re-subscribe
         * costs more than the gap it is trying to close -- a tunnel, a lift, a
         * minute of bad sky.
         */
        private const val MIN_STALE_MS = 5 * 60_000L

        /**
         * A stored point at least this often, moving or parked. Without it a
         * long stop leaves an hours-wide hole that reads, to whoever is
         * following, exactly like tracking having died.
         */
        private const val BREADCRUMB_MS = 60 * 60_000L

        /** Consecutive failed recoveries before accuracy is traded for delivery. */
        private const val DEGRADE_AFTER = 2

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
