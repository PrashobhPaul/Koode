package com.trippulse.app.di

import android.content.Context
import com.trippulse.app.core.RegionDetector
import com.trippulse.app.core.SettingsStore
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
import com.trippulse.app.data.update.UpdateChecker
import com.trippulse.app.domain.Measures
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

    /** User-tunable behaviour (location cadence, refresh rate, theme, units). */
    val settings: SettingsStore = SettingsStore(appContext)

    /** Which country the traveller is in, for currency and units. */
    val region: RegionDetector = RegionDetector(appContext)

    /**
     * How to render distances, speeds and money right now.
     *
     * Resolved on demand rather than cached, because the two things it depends
     * on — the user's setting and the country their phone can see — can both
     * change mid-session, and a journey that crosses a border should price
     * itself correctly on the far side.
     */
    fun measures(refreshRegion: Boolean = false): Measures {
        val s = settings.current
        return Measures.resolve(
            countryCode = region.countryCode(refresh = refreshRegion),
            unitPreference = s.unitPreference,
            currencyOverride = s.currencyCode.ifBlank { null }
        )
    }

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
        settings = settings,
        appScope = appScope,
        cfg = cfg
    )

    val viewerRepository: ViewerRepository = ViewerRepository(db, cloud, settings, cfg)

    /** Nudges people off old builds; never touches an in-flight journey. */
    val updateChecker: UpdateChecker = UpdateChecker(appContext, settings)

    fun cloudEnabledByDefault(): Boolean = cloud.isAvailable()
}
