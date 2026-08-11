package com.trippulse.app.di

import android.content.Context
import com.trippulse.app.data.TripManager
import com.trippulse.app.data.ViewerRepository
import com.trippulse.app.data.local.TripPulseDb
import com.trippulse.app.data.remote.TripCloud
import com.trippulse.app.data.routing.CompositeRouting
import com.trippulse.app.data.routing.FallbackRoutingProvider
import com.trippulse.app.data.routing.OsrmRoutingProvider
import com.trippulse.app.data.routing.RoutingProvider
import com.trippulse.app.data.sync.ConnectivityObserver
import com.trippulse.app.data.sync.SyncEngine
import com.trippulse.app.domain.TripConfig
import com.trippulse.app.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph (composition root). Hilt is intentionally avoided to
 * keep the build lean; the object wiring here is explicit and easy to follow.
 * A single instance is held by [com.trippulse.app.TripPulseApp].
 */
class AppGraph(context: Context) {

    val appContext: Context = context.applicationContext
    val cfg: TripConfig = TripConfig.DEFAULT

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: TripPulseDb = TripPulseDb.get(appContext)

    val connectivity: ConnectivityObserver = ConnectivityObserver(appContext)

    val cloud: TripCloud = TripCloud(appContext)

    // Free OSRM public router first, deterministic estimator when offline.
    private val routing: RoutingProvider =
        CompositeRouting(OsrmRoutingProvider(), FallbackRoutingProvider(cfg))

    val notifier: Notifier = Notifier(appContext).also { it.ensureChannels() }

    val sync: SyncEngine = SyncEngine(db, cloud, cfg)

    val tripManager: TripManager = TripManager(
        appContext = appContext,
        db = db,
        cloud = cloud,
        routing = routing,
        sync = sync,
        connectivity = connectivity,
        notifier = notifier,
        appScope = appScope,
        cfg = cfg
    )

    val viewerRepository: ViewerRepository = ViewerRepository(db, cloud, cfg)

    fun cloudEnabledByDefault(): Boolean = cloud.isAvailable()
}
