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

    /** Server push for journeys this phone follows (inert without Firebase values). */
    val push: com.trippulse.app.data.push.PushRegistry =
        com.trippulse.app.data.push.PushRegistry(appContext, cloud)

    // Free OSRM public router first, deterministic estimator when offline.
    private val routing: RoutingProvider =
        CompositeRouting(OsrmRoutingProvider(), FallbackRoutingProvider(cfg))

    val notifier: Notifier = Notifier(appContext).also { it.ensureChannels() }

    val sync: SyncEngine = SyncEngine(db, cloud, cfg)

    /** Toll plazas for location-based toll counting (bundled, refreshed weekly). */
    val tollPlazas: com.trippulse.app.data.TollPlazaRepository =
        com.trippulse.app.data.TollPlazaRepository(appContext, cloud, appScope)

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
        cfg = cfg,
        tollPlazas = { tollPlazas.index() }
    )

    val viewerRepository: ViewerRepository = ViewerRepository(db, cloud, settings, cfg)

    /** Approved analytics and the verified report, to the journey's followers. */
    val publisher: com.trippulse.app.data.export.ApprovedJourneyPublisher =
        com.trippulse.app.data.export.ApprovedJourneyPublisher(appContext, db, cloud, { measures() }, appScope)
            .also { p ->
                tripManager.onJourneyApproved = { p.enqueue(it) }
                p.resume()
            }

    /** Nudges people off old builds; never touches an in-flight journey. */
    /** Fetches a journey's full path back from the cloud when the phone's copy was trimmed. */
    val restorer: com.trippulse.app.data.RecordRestorer =
        com.trippulse.app.data.RecordRestorer(appContext, db, sync, tripManager, publisher)

    val updateChecker: UpdateChecker = UpdateChecker(appContext, settings)

    fun cloudEnabledByDefault(): Boolean = cloud.isAvailable()
}
