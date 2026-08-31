package com.trippulse.app

import android.app.Application
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.trippulse.app.di.AppGraph
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Application entry point. Builds the DI graph lazily, starts observing
 * connectivity so a reconnect immediately triggers a drain (current state
 * first, then backlog), and schedules a periodic safety-net sync (docs/spec/06,
 * 79).
 */
class TripPulseApp : Application() {

    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()

        // osmdroid (OpenStreetMap) setup: identify the app per OSM tile-usage
        // policy and keep the tile cache in app-private storage (no permissions).
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = getExternalFilesDir(null) ?: filesDir
            osmdroidTileCache = java.io.File(osmdroidBasePath, "osm_tiles")
        }

        graph.notifier.ensureChannels()

        // Reconnect -> drain. The journey continues locally regardless; this only
        // affects when the server receives events.
        graph.appScope.launch {
            graph.connectivity.changes().collectLatest { online ->
                if (online) {
                    val trip = graph.db.tripDao().activeTrip() ?: return@collectLatest
                    graph.sync.drain(trip)
                }
            }
        }

        // Resume viewer alerting for any trips this phone is still following.
        graph.appScope.launch {
            val following = try { graph.db.viewerDao().activeList() } catch (_: Exception) { emptyList() }
            if (following.isNotEmpty() && graph.cloud.isAvailable()) {
                com.trippulse.app.service.TripFollowService.start(this@TripPulseApp)
            }
        }

        scheduleSyncRetry()
        // Runs regardless of network: it exists for the cases where the network
        // (or the service, or the traveller's memory) is the thing that failed.
        com.trippulse.app.service.JourneyKeeperWorker.schedule(this)
    }

    private fun scheduleSyncRetry() {
        val req = PeriodicWorkRequestBuilder<SyncRetryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("trippulse-sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/** Periodic backstop that drains any pending events/locations for the active trip. */
class SyncRetryWorker(appContext: android.content.Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TripPulseApp ?: return Result.success()
        val trip = app.graph.db.tripDao().activeTrip() ?: return Result.success()
        return try {
            app.graph.sync.drain(trip)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
