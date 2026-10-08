package com.trippulse.app.data

import android.content.Context
import com.trippulse.app.core.Geo
import com.trippulse.app.core.SettingsStore
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.core.TripCredentials
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.data.local.BreakRecordEntity
import com.trippulse.app.data.local.EventEntity
import com.trippulse.app.data.local.LocationSampleEntity
import com.trippulse.app.data.local.RecentDestinationEntity
import com.trippulse.app.data.local.TripLegEntity
import com.trippulse.app.data.local.TripPulseDb
import com.trippulse.app.data.local.TripStateEntity
import com.trippulse.app.data.remote.TripCloud
import com.trippulse.app.data.routing.RoutingProvider
import com.trippulse.app.data.sync.ConnectivityObserver
import com.trippulse.app.data.sync.SyncEngine
import com.trippulse.app.domain.Connectivity
import com.trippulse.app.domain.StageRepair
import com.trippulse.app.domain.EtaEngine
import com.trippulse.app.domain.EtaMode
import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.Fix
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.JourneyInput
import com.trippulse.app.domain.JourneyStateMachine
import com.trippulse.app.domain.JourneyStatus
import com.trippulse.app.domain.MealClassifier
import com.trippulse.app.domain.Nourishment
import com.trippulse.app.domain.PlaceResolver
import com.trippulse.app.domain.RoutePlan
import com.trippulse.app.domain.StopDetector
import com.trippulse.app.domain.SummaryCalculator
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.Darkness
import com.trippulse.app.domain.DistanceLedger
import com.trippulse.app.domain.DarkReason
import com.trippulse.app.core.DeviceIdentity
import com.trippulse.app.core.DeviceDossier
import com.trippulse.app.domain.TravelDetails
import com.trippulse.app.domain.DetailKeys
import com.trippulse.app.domain.LegDetails
import com.trippulse.app.domain.TransportProfile
import com.trippulse.app.domain.TripConfig
import com.trippulse.app.domain.forMode
import com.trippulse.app.domain.fastag.TollCrossing
import com.trippulse.app.domain.TripEvent
import com.trippulse.app.domain.WellbeingTimes
import com.trippulse.app.domain.EtaShift
import com.trippulse.app.domain.HaltPlanning
import com.trippulse.app.domain.Halts
import com.trippulse.app.domain.JourneyClosure
import com.trippulse.app.domain.Expenses
import com.trippulse.app.data.local.ExpenseEntity
import com.trippulse.app.domain.JourneyPlan
import com.trippulse.app.domain.JourneyPlans
import com.trippulse.app.domain.JourneyUpdates
import com.trippulse.app.domain.WellbeingCoach
import com.trippulse.app.notifications.Notifier
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Central journey orchestrator. Owns journey state, drives the detection
 * engines from location fixes and ticks, records events into the durable local
 * log, recomputes the realistic ETA and hands work to the two-lane
 * [SyncEngine]. Every mutation goes through [lock] so location updates, ticks
 * and traveller actions never race.
 *
 * Two rules shape almost everything below:
 *
 *  1. **Only the traveller ends a journey.** Arrival is *detected* and then
 *     *asked about*; it is never acted on. Nothing else — not a timer, not a
 *     lost connection, not an expiring capability — may present a journey as
 *     over. See [maybeArrival] and [completeTrip].
 *  2. **The mode of transport decides the rules.** Break prompts,
 *     refuelling questions and sampling cadence all come from the
 *     [TransportProfile] of the leg being travelled, never from scattered
 *     conditionals. See [activeProfile].
 */
/**
 * Thrown when a second journey is started while one is still open.
 *
 * Carries the running journey's id so the caller can offer to open it, which
 * is almost always what the person actually wanted.
 */
class JourneyAlreadyRunning(val tripId: String, val destination: String) :
    IllegalStateException("A journey to $destination is already running")

class TripManager(
    private val appContext: Context,
    private val db: TripPulseDb,
    private val cloud: TripCloud,
    private val routing: RoutingProvider,
    private val sync: SyncEngine,
    private val connectivity: ConnectivityObserver,
    private val notifier: Notifier,
    private val settings: SettingsStore,
    private val appScope: CoroutineScope,
    private val cfg: TripConfig = TripConfig.DEFAULT,
    /** Known toll plazas, for counting crossings on car and bike journeys. */
    private val tollPlazas: () -> com.trippulse.app.domain.TollPlazas.Index = {
        com.trippulse.app.domain.TollPlazas.Index(emptyList())
    }
) {
    /** Set by the app/service: asked to stop the foreground service. */
    var onStopTrackingRequested: (() -> Unit)? = null
    /** Set by the service: asked to (re)apply the sampling interval. */
    var onSamplingChanged: (() -> Unit)? = null
    /**
     * Set by the app: the traveller approved a journey's analytics. The
     * listener publishes them and the verified report to the followers.
     */
    var onJourneyApproved: ((com.trippulse.app.data.export.ApprovedJourneyPublisher.Approval) -> Unit)? = null

    private val lock = JourneyLock()

    private var trip: ActiveTripEntity? = null
    private var state: TripStateEntity? = null
    private var legs: List<TripLegEntity> = emptyList()

    private var detector = StopDetector(cfg)

    /** The previous good fix, for toll detection along the path. */
    private var lastTollFix: Fix? = null
    /** This journey's recorded crossings (where, when), for one-plaza-one-toll. */
    private var tollRecent: MutableList<com.trippulse.app.domain.TollPlazas.Recent>? = null
    private var tollRecentTrip: String? = null

    private val region by lazy { com.trippulse.app.core.RegionDetector(appContext) }

    /** How this traveller measures and prices things, from their setting and where the phone is. */
    private fun measures(): com.trippulse.app.domain.Measures = com.trippulse.app.domain.Measures.resolve(
        countryCode = runCatching { region.countryCode() }.getOrNull(),
        unitPreference = settings.current.unitPreference,
        currencyOverride = settings.current.currencyCode.ifBlank { null }
    )

    /** The traveller's market: toll scheme, wording, emergency number. See Markets. */
    private fun market(): com.trippulse.app.domain.Market = com.trippulse.app.domain.Market.resolve(
        countryCode = settings.current.countryOverride.ifBlank { null } ?: runCatching { region.countryCode() }.getOrNull(),
        unitPreference = settings.current.unitPreference,
        clockPreference = settings.current.clockPreference
    )

    private val _routeAhead = kotlinx.coroutines.flow.MutableStateFlow<List<GeoPoint>>(emptyList())
    /** The road the router found for the stage being travelled, for the map; empty when there is none. */
    val routeAhead: StateFlow<List<GeoPoint>> = _routeAhead
    private var currentRoute: RoutePlan? = null
        set(v) {
            field = v
            _routeAhead.value = v?.takeIf { it.provider != "fallback" }?.polyline.orEmpty()
        }
    private var routeFetchedAtMs: Long = 0
    @Volatile private var routeRefreshInFlight = false
    private var lastPersistMs: Long = 0
    private var lastPersistPoint: GeoPoint? = null
    private var lastDistancePoint: GeoPoint? = null
    /** When the last fix arrived; a long silence before the next one is a stretch to credit. */
    private var lastFixMs: Long = 0
    /** The last quick entry, so a double tap records it once. */
    private var lastNoteKey: String? = null
    private var lastNoteAtMs = 0L
    /**
     * A silence credited from its straight line, waiting for the route to
     * say how long the road really was (see [refineGapCredit]).
     */
    private data class GapCredit(val atMs: Long, val lineM: Double, val creditedM: Double, val remainingBeforeM: Double, val coveredAfterM: Double)
    private var gapCredit: GapCredit? = null
    /**
     * The metro ride under way: the station it began at, and what the
     * journey had covered before it. Each fix near a station raises the count
     * to at least the track ridden so far (see [metroFloor]).
     */
    private data class MetroAnchor(val legIndex: Int, val station: Int, val coveredBeforeM: Double, val kind: com.trippulse.app.domain.TransitNetwork.Kind)
    private var metroAnchor: MetroAnchor? = null
    /**
     * A metro ride just ended on a stale fix (underground, or the phone kept
     * quiet on the train): the station it was named after may be one short.
     * The first good fix after it settles which station it really was.
     */
    private data class MetroExit(
        val tripId: String, val endedIndex: Int, val startedIndex: Int,
        val anchor: MetroAnchor, val station: Int, val label: String, val atMs: Long
    )
    private var metroExit: MetroExit? = null
    private var lastEtaCalcMs: Long = 0
    private var batteryLowFired = false
    private var arrivalPromptShown = false
    /**
     * Set inside the lock, acted on outside it. recordBackOnline() takes the
     * same mutex, so calling it from within onTick would deadlock.
     */
    private var backOnlineDue = false

    init {
        sync.onSosDelivered = { tripId -> appendSosDelivered(tripId) }
        // Watchdog: a journey must never freeze behind one stuck step, even
        // when nobody is waiting to notice.
        appScope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                lock.healIfStuck(System.currentTimeMillis())
            }
        }
    }

    // ---- flows for UI ----
    fun activeTripFlow(): Flow<ActiveTripEntity?> = db.tripDao().activeTripFlow()
    fun stateFlow(tripId: String): Flow<TripStateEntity?> = db.stateDao().flow(tripId)
    fun eventsFlow(tripId: String): Flow<List<EventEntity>> = db.eventDao().eventsFlow(tripId)
    fun legsFlow(tripId: String): Flow<List<TripLegEntity>> = db.legDao().flowForTrip(tripId)
    fun pendingCountFlow(): Flow<Int> = db.eventDao().pendingCountFlow()

    fun cloudAvailable(): Boolean = cloud.isAvailable()
    fun currentTripIdOrNull(): String? = trip?.tripId

    /** Corroborating in-vehicle hint from Activity Recognition. */
    suspend fun onActivityHint(inVehicle: Boolean) = lock.withLock {
        detector.onActivityHint(inVehicle)
    }

    /** Reload the active journey from disk (after process/service restart). */
    suspend fun loadActive(): ActiveTripEntity? = lock.withLock {
        val t = db.tripDao().activeTrip() ?: return@withLock null
        trip = t
        state = db.stateDao().byId(t.tripId)
        legs = db.legDao().forTrip(t.tripId)
        arrivalPromptShown = state?.arrivalPromptDue == true
        state?.let { s -> if (!terminal(s)) state = pickUpDistance(t, s) }
        // detectors restart clean; persisted journey state is authoritative
        detector = StopDetector(cfg.forMode(activeLeg()?.mode ?: t.transportMode))
        plans.latest(t.tripId)
        appScope.launch { repairStages(t.tripId) }
        t
    }

    /**
     * Reads a journey's stages against each other and puts right what an
     * older build or a hurried tap left wrong: a finished stage ends where the
     * next began, and a switch point still called "En route" is named. Safe
     * to run again; changes nothing that is already right.
     */
    suspend fun repairStages(tripId: String): Boolean {
        val rows = db.legDao().forTrip(tripId)
        if (rows.size < 2) { runCatching { reconcileDistance(tripId) }; runCatching { relabelFares(tripId) }; return false }
        var changed = false
        lock.withLock {
            val now = db.legDao().forTrip(tripId)
            val ends = StageRepair.endsWhereNextBegan(now.map { it.toStage() }).associateBy { it.index }
            now.forEach { r ->
                ends[r.legIndex]?.let { e ->
                    db.legDao().upsert(r.copy(toName = e.toName, toLat = e.toLat, toLng = e.toLng)); changed = true
                }
            }
        }
        // Name each switch point once, outside the lock (the geocoder can be slow).
        db.legDao().forTrip(tripId).filter { it.fromName == EN_ROUTE && it.startedAtMs != null }.forEach { r ->
            val name = runCatching { resolvePlace(r.fromLat, r.fromLng) }.getOrNull() ?: return@forEach
            lock.withLock {
                val now = db.legDao().forTrip(tripId)
                now.firstOrNull { it.legIndex == r.legIndex && it.fromName == EN_ROUTE }
                    ?.let { db.legDao().upsert(it.copy(fromName = name)); changed = true }
                now.firstOrNull { it.legIndex == r.legIndex - 1 && it.toName == EN_ROUTE }
                    ?.let { db.legDao().upsert(it.copy(toName = name)); changed = true }
            }
        }
        if (nameMetroStations(tripId)) changed = true
        if (changed) lock.withLock {
            val t = trip
            if (t?.tripId == tripId) {
                legs = db.legDao().forTrip(tripId)
                if (t.cloudEnabled) appScope.launch { sync.writeMetaUpdate(t, metaMap(t)) }
            }
        }
        // A metro ride is measured by its track: a journey counted from its
        // fixes alone is put right here (it only ever grows).
        runCatching { reconcileDistance(tripId) }
        runCatching { relabelFares(tripId) }
        return changed
    }

    /**
     * Each fare's label made to read as its stage does now: a fare written
     * as "Metro · Tarnaka → En route" the moment the ride ended becomes
     * "Metro · Habsiguda Metro → HITEC City Metro" once both ends are named.
     */
    private suspend fun relabelFares(tripId: String) {
        val stages = db.legDao().forTrip(tripId).map { Expenses.FareStage(it.mode, it.fromName, it.toName, it.completedAtMs) }
        if (stages.none { it.endedAtMs != null }) return
        val opportunities = expenseStore.all(tripId)
        var changed = false
        val renamed = opportunities.map { o ->
            Expenses.relabelled(o.label, o.category, o.atMs, stages)?.let { changed = true; o.copy(label = it) } ?: o
        }
        if (changed) expenseStore.save(tripId, renamed)
        db.expenseDao().allForTrip(tripId).forEach { e ->
            Expenses.relabelled(e.item, Expenses.Category.fromType(e.type), e.tMs, stages)?.let { db.expenseDao().updateItem(e.id, it) }
        }
    }

    /**
     * Where a metro ride began or ended part-way through a journey, named
     * after the station there -- for journeys recorded before Koode knew the
     * stations, when the area name ("Tarnaka") stood in for the station
     * ("Habsiguda Metro"). The journey's own start and end, and any name that
     * is one of the traveller's saved places, are left as they are.
     */
    private suspend fun nameMetroStations(tripId: String): Boolean {
        val net = TransitData.load(appContext)
        if (net.isEmpty) return false
        val savedNames = runCatching { db.savedPlaceDao().all() }.getOrNull().orEmpty().map { it.name.trim() }.toSet()
        var changed = false
        lock.withLock {
            val rows = db.legDao().forTrip(tripId).sortedBy { it.legIndex }.toMutableList()
            fun rename(i: Int, p: GeoPoint, old: String, start: Boolean) {
                if (old.trim() in savedNames) return
                val label = net.stationLabel(p, listOf(rows[i].mode)) ?: return
                if (label == old) return
                rows[i] = if (start) rows[i].copy(fromName = label) else rows[i].copy(toName = label)
                // The stage on the other side of the switch shares the point and its name.
                val j = if (start) i - 1 else i + 1
                rows.getOrNull(j)?.let { o ->
                    if (start && o.toName == old) rows[j] = o.copy(toName = label)
                    if (!start && o.fromName == old) rows[j] = o.copy(fromName = label)
                }
                changed = true
            }
            for (i in rows.indices) {
                val r = rows[i]
                if (rideKind(r.mode) == null || r.startedAtMs == null) continue
                if (i > 0) rename(i, GeoPoint(r.fromLat, r.fromLng), r.fromName, start = true)
                if (r.completedAtMs != null && i < rows.lastIndex) rename(i, GeoPoint(rows[i].toLat, rows[i].toLng), rows[i].toName, start = false)
            }
            if (changed) db.legDao().upsertAll(rows)
        }
        return changed
    }

    private fun TripLegEntity.toStage() = StageRepair.Stage(
        legIndex, mode, fromName, fromLat, fromLng, toName, toLat, toLng, startedAtMs, completedAtMs
    )

    // -----------------------------------------------------------------------
    // Transport rules for the leg currently being travelled
    // -----------------------------------------------------------------------

    /** The leg the traveller is on right now (always leg 0 for single-mode). */
    private fun activeLeg(): TripLegEntity? {
        val t = trip ?: return null
        return legs.firstOrNull { it.legIndex == t.activeLegIndex } ?: legs.firstOrNull()
    }

    /**
     * The rule set in force. Every mode-dependent decision in this class reads
     * from here, so behaviour switches automatically the moment a hybrid
     * journey moves from its train leg onto its bus leg.
     */
    private fun activeProfile(): TransportProfile =
        TransportCatalog.profile(activeLeg()?.mode ?: trip?.transportMode)

    // -----------------------------------------------------------------------
    // Journey lifecycle
    // -----------------------------------------------------------------------

    /** One stage of a journey as supplied by the create screen. */
    data class NewLeg(
        val mode: String,
        val fromName: String, val from: GeoPoint,
        val toName: String, val to: GeoPoint,
        val fuelType: String? = null,
        val plannedDepartureMs: Long? = null,
        val boardingPoint: String? = null,
        /** Vehicle and booking details, keyed by [DetailKeys]. */
        val details: Map<String, String> = emptyMap()
    )

    data class NewTrip(
        val legs: List<NewLeg>,
        val plannedDepartureMs: Long?,
        val emergencyName: String?, val emergencyPhone: String?,
        val cloudEnabled: Boolean,
        /** Six digits chosen by the traveller; blank asks for a random one. */
        val passcode: String,
        /** DRIVER or PASSENGER; null takes the mode's default (car/bike: driver). */
        val role: String? = null
    ) {
        val first: NewLeg get() = legs.first()
        val last: NewLeg get() = legs.last()
    }

    companion object {
        /** A saved place this close is a better name for a point than a road is. */
        private const val NEAR_PLACE_M = 500.0
        /** A saved place this close is named over a station: "Home" is right by the metro. */
        private const val SAVED_BEATS_STATION_M = 150.0
        /** Moving about within this many arrival radii of the destination is still being there. */
        private const val STILL_THERE_FACTOR = 1.5
        /** A fix rougher than this cannot say which station a train is at. */
        private const val METRO_FIX_ACCURACY_M = 150f
        /** A last fix older than this, when a ride ends, may be a station back. */
        private const val METRO_STALE_FIX_MS = 60_000L
        /** How long after a ride a good fix may still say where it ended. */
        private const val METRO_EXIT_SETTLE_MS = 3 * 60_000L

        /** Auto-named ends we never keep as a reusable destination. */
        private val DEST_PLACEHOLDERS = setOf("Destination", "Pinned destination", "En route")

        /** Retained for callers that still ask the old question. */
        val PRIVATE_MODES: Set<String> = TransportCatalog.PRIVATE_KEYS
    }

    /** Creates a journey, generating credentials, legs and an initial route. */
    suspend fun createTrip(n: NewTrip): ActiveTripEntity = lock.withLock {
        require(n.legs.isNotEmpty()) { "A journey needs at least one leg" }
        // One live journey per phone, enforced here rather than only on the
        // screen that offers the button. Two would mean two simultaneous
        // claims about where one person is, and someone following would have
        // no way to tell which of them to believe -- which is the one thing
        // this app cannot afford to be wrong about.
        db.tripDao().activeTrip()?.let {
            throw JourneyAlreadyRunning(it.tripId, it.destName)
        }
        val now = System.currentTimeMillis()
        val tripId = TripCredentials.newTripId()
        val secret = n.passcode.trim().ifBlank { TripCredentials.newPasscode() }
        val accessKey = TripCredentials.accessKey(tripId, secret)

        val firstLeg = n.first
        val lastLeg = n.last
        val route = routing.route(firstLeg.from, firstLeg.to)
        currentRoute = route
        routeFetchedAtMs = now

        // The headline distance spans every leg, so a two-mode journey shows a
        // believable total rather than only its first hop.
        val plannedDistanceM = n.legs.sumOf {
            Geo.haversineM(it.from, it.to) * cfg.roadDistanceFactor
        }

        val t = ActiveTripEntity(
            tripId = tripId, secret = secret, accessKey = accessKey,
            originName = firstLeg.fromName, originLat = firstLeg.from.lat, originLng = firstLeg.from.lng,
            destName = lastLeg.toName, destLat = lastLeg.to.lat, destLng = lastLeg.to.lng,
            emergencyName = n.emergencyName, emergencyPhone = n.emergencyPhone,
            createdAtMs = now, plannedDepartureMs = n.plannedDepartureMs,
            startedAtMs = null, completedAtMs = null, expiresAtMs = null,
            status = "CREATED",
            cloudEnabled = n.cloudEnabled && cloud.isAvailable(),
            metaSynced = false,
            totalRouteDistanceM = route?.distanceM?.takeIf { n.legs.size == 1 } ?: plannedDistanceM,
            ownerUid = null,
            transportMode = firstLeg.mode,
            fuelType = if (TransportCatalog.isPrivate(firstLeg.mode)) firstLeg.fuelType else null,
            activeLegIndex = 0,
            arrivedAtMs = null,
            endedByOwner = false
        )
        db.tripDao().upsert(t)
        trip = t

        // Remember this destination durably so the next journey can reuse it in
        // one tap — kept even after the trip row itself is swept.
        lastLeg.toName.trim().let { destName ->
            if (destName.isNotBlank() && destName !in DEST_PLACEHOLDERS) {
                runCatching {
                    db.recentDestinationDao().upsert(
                        RecentDestinationEntity(
                            placeKey = RecentDestinationEntity.keyOf(lastLeg.to.lat, lastLeg.to.lng),
                            name = destName, lat = lastLeg.to.lat, lng = lastLeg.to.lng, lastUsedMs = now
                        )
                    )
                }
            }
        }

        // Capture who and what this device is, at the outset, so a report has
        // it even if the phone never reports again. The synchronous part goes
        // in now; the public IP, which needs the network, follows in the
        // background and updates the row when it lands.
        captureDossier(t.tripId)

        val legRows = n.legs.mapIndexed { index, leg ->
            TripLegEntity(
                tripId = tripId, legIndex = index, mode = leg.mode,
                fromName = leg.fromName, fromLat = leg.from.lat, fromLng = leg.from.lng,
                toName = leg.toName, toLat = leg.to.lat, toLng = leg.to.lng,
                fuelType = if (TransportCatalog.isPrivate(leg.mode)) leg.fuelType else null,
                plannedDepartureMs = leg.plannedDepartureMs,
                startedAtMs = null, completedAtMs = null,
                bookingRef = leg.details[DetailKeys.PNR],
                seat = leg.details[DetailKeys.SEAT],
                boardingPoint = leg.boardingPoint,
                detailsJson = LegDetails.toJson(leg.details)
            )
        }
        db.legDao().upsertAll(legRows)
        legs = legRows

        val s = freshState(t, now)
        db.stateDao().upsert(s)
        state = s

        plans.append(
            tripId,
            JourneyPlans.initial(
                now, lastLeg.toName, firstLeg.mode,
                WellbeingCoach.Role.fromKey(n.role)?.name ?: WellbeingCoach.defaultRole(firstLeg.mode).name
            )
        )

        insertEvent(
            t.tripId, EventTypes.TRIP_CREATED, EventSource.DRIVER_MANUAL, now,
            firstLeg.from.lat, firstLeg.from.lng,
            mapOf(
                "origin" to firstLeg.fromName,
                "destination" to lastLeg.toName,
                "legs" to n.legs.size
            ), false
        )
        t
    }

    /** Marks the journey active and starts the journey clock. */
    /**
     * Starts the journey. When the traveller pressed Start after already
     * setting off, [actualStartMs] is when they really left and [here] is where
     * they are now: the journey's clock starts at the real departure, and the
     * road distance already covered (origin → here) is counted, so totals are
     * for the whole journey rather than from midway. That leading distance is
     * an estimate and is recorded as one.
     */
    suspend fun startTrip(tripId: String, actualStartMs: Long? = null, here: GeoPoint? = null) {
        val t0 = db.tripDao().byId(tripId) ?: return
        val now0 = System.currentTimeMillis()
        val lateStart = actualStartMs != null && actualStartMs < now0 - 60_000 && here != null
        val preDistanceM = if (lateStart && here != null) {
            val origin = GeoPoint(t0.originLat, t0.originLng)
            (runCatching { routing.route(origin, here)?.distanceM }.getOrNull()
                ?: Geo.haversineM(origin, here) * cfg.roadDistanceFactor)
        } else 0.0
        startTripLocked(tripId, if (lateStart) actualStartMs!! else null, preDistanceM)
    }

    private suspend fun startTripLocked(tripId: String, actualStartMs: Long?, preDistanceM: Double) = lock.withLock {
        val t = db.tripDao().byId(tripId) ?: return@withLock
        val recordedAt = System.currentTimeMillis()
        val now = actualStartMs ?: recordedAt
        val started = t.copy(status = "ACTIVE", startedAtMs = now)
        db.tripDao().update(started)
        trip = started
        legs = db.legDao().forTrip(tripId)

        val s = (state ?: db.stateDao().byId(tripId) ?: freshState(started, now)).copy(
            journey = JourneyStatus.READY.name,
            drivingSinceMs = now,
            connectivity = connectivityNow().name,
            legIndex = started.activeLegIndex,
            distanceCoveredM = preDistanceM,
            updatedAtMs = recordedAt
        )
        db.stateDao().upsert(s); state = s

        insertEvent(
            tripId, EventTypes.TRIP_STARTED, EventSource.DRIVER_MANUAL, now, null, null,
            if (actualStartMs != null) mapOf(
                "startedEarlier" to true, "recordedAtMs" to recordedAt,
                "estimatedDistanceBeforeTrackingM" to preDistanceM
            ) else mapOf("text" to "${ownerName() ?: "The traveller"} started a journey to ${started.destName}."), false
        )
        db.legDao().markStarted(tripId, started.activeLegIndex, now)
        legs = db.legDao().forTrip(tripId)
        announceLeg(started, started.activeLegIndex, now)

        // arm cloud meta + first live push
        if (started.cloudEnabled) appScope.launch {
            sync.ensureMeta(started, metaMap(started))
            sync.pushLiveState(started, stateMap(started, s), force = true)
        }
    }

    // -----------------------------------------------------------------------
    // Editing a journey while it is running
    // -----------------------------------------------------------------------

    /**
     * Whether this journey can still be changed.
     *
     * A completed journey is a record, and a record that can be edited after
     * the fact is worth nothing to the people who were watching it: the
     * timeline they followed and the summary they were sent must stay the
     * thing that actually happened. Every mutating entry point below asks this
     * first, so there is no path to a post-completion edit — not through the
     * UI, not through a stale screen still holding an old view model.
     */
    fun isEditable(t: ActiveTripEntity?): Boolean =
        t != null && !t.endedByOwner && t.status != "COMPLETED" && t.status != "EXPIRED"

    private fun editableTrip(): ActiveTripEntity? = trip?.takeIf { isEditable(it) }

    /**
     * The traveller has changed how they are travelling, mid-journey.
     *
     * This is what a multi-leg journey actually is. Someone gets off the train
     * at Bangalore and carries on by bus; the destination never changed, only
     * the vehicle did. So this asks nothing about where they are going -- the
     * journey already knows -- and nothing about where they are, because the
     * phone already knows that too. One question: what are you on now.
     *
     * The current stage is closed wherever they are standing, and a new one
     * opens from that point to the same destination the journey has always
     * had. Followers see a continuous line and one more entry in the timeline.
     *
     * Returns why it could not happen, or [SwitchResult.Ok].
     */
    suspend fun switchMode(
        newMode: String,
        details: Map<String, String> = emptyMap(),
        breakdown: Boolean = false
    ): SwitchResult = lock.withLock { switchModeLocked(newMode, details, breakdown) }

    /** [switchMode] with the lock already held. */
    private suspend fun switchModeLocked(
        newMode: String,
        details: Map<String, String>,
        breakdown: Boolean
    ): SwitchResult {
        val t = editableTrip() ?: return SwitchResult.NotEditable
        val s0 = state ?: return SwitchResult.NotEditable

        // Without a fix there is no honest place to end the current stage, and
        // inventing one would put a line on the map that nobody travelled.
        val lat = s0.lat
        val lng = s0.lng
        if (lat == null || lng == null) return SwitchResult.NoLocationYet

        val current = activeLeg()
        val previousMode = current?.mode ?: t.transportMode
        // The same change tapped twice in a row is one change.
        val startedCurrent = current?.startedAtMs
        if (current != null && newMode.equals(current.mode, ignoreCase = true) &&
            startedCurrent != null && System.currentTimeMillis() - startedCurrent < SAME_SWITCH_MS) {
            return SwitchResult.Ok
        }
        if (!TravelDetails.isComplete(newMode, details)) {
            return SwitchResult.MissingDetails(
                TravelDetails.missingRequired(newMode, details, market()).map { it.label }
            )
        }

        val now = System.currentTimeMillis()
        val here = GeoPoint(lat, lng)
        val hereName = nameForPoint(here, listOfNotNull(current?.mode, newMode).filter { rideKind(it) != null || TransportCatalog.profile(it).key == TransportCatalog.TRAIN.key })

        // A metro ride that ends here is counted to this station at least.
        var coveredNow = s0.distanceCoveredM
        val endedKind = rideKind(current?.mode)
        if (current != null && endedKind != null) {
            val net = TransitData.load(appContext)
            val anchor = metroAnchorFor(t, current, net, s0.distanceCoveredM, now)
            val end = net.nearestStation(here, endedKind)
            if (anchor != null && end != null) {
                net.ride(anchor.station, end)?.let { coveredNow = maxOf(coveredNow, anchor.coveredBeforeM + it.metres) }
                // A stale last fix may have been taken a station or more back.
                val stale = s0.lastLocationAtMs?.let { now - it > METRO_STALE_FIX_MS } ?: true
                val named = hereName == com.trippulse.app.domain.TransitNetwork.label(net.stations[end])
                metroExit = if (stale && named) MetroExit(t.tripId, current.legIndex, current.legIndex + 1, anchor, end, hereName, now) else null
            }
            if (coveredNow > s0.distanceCoveredM) lastDistancePoint = here
        }
        metroAnchor = null

        // The stage that ends here ends *here*: the cab that dropped you at the
        // metro went to the metro, not to the destination it was heading for.
        current?.let {
            db.legDao().upsert(it.copy(completedAtMs = now, toName = hereName, toLat = lat, toLng = lng))
            fareOpportunity(t.tripId, it.mode, it.fromName, hereName, now)
        }

        // Where this new stage is heading: the point the current stage was
        // already going to. On a single-stage journey that is the destination;
        // on one with stages planned ahead it is the next waypoint, so
        // switching vehicles part-way through never skips what came after.
        val toName = current?.toName ?: t.destName
        val toLat = current?.toLat ?: t.destLat
        val toLng = current?.toLng ?: t.destLng

        // The new stage takes the place immediately after the current one, and
        // anything planned beyond it shifts up rather than being lost.
        val insertAt = (current?.legIndex ?: -1) + 1
        val shifted = legs.filter { it.legIndex >= insertAt }
            .sortedByDescending { it.legIndex }
            .map { it.copy(legIndex = it.legIndex + 1) }
        if (shifted.isNotEmpty()) db.legDao().upsertAll(shifted)

        db.legDao().upsert(
            TripLegEntity(
                tripId = t.tripId, legIndex = insertAt, mode = newMode,
                fromName = hereName, fromLat = lat, fromLng = lng,
                toName = toName, toLat = toLat, toLng = toLng,
                fuelType = details[DetailKeys.FUEL_TYPE]
                    ?.takeIf { TransportCatalog.isPrivate(newMode) },
                plannedDepartureMs = null, startedAtMs = now, completedAtMs = null,
                bookingRef = details[DetailKeys.PNR], seat = details[DetailKeys.SEAT],
                boardingPoint = null, detailsJson = LegDetails.toJson(details)
            )
        )
        legs = db.legDao().forTrip(t.tripId)

        val nextIndex = insertAt
        // A change made at the destination does not undo having arrived:
        // the walk from the cab to the office door is not a new journey.
        val arrived = atDestination(t, s0) && legs.none { it.legIndex > nextIndex }
        val updated = t.copy(
            activeLegIndex = nextIndex,
            transportMode = newMode,
            arrivedAtMs = if (arrived) t.arrivedAtMs else null
        )
        db.tripDao().update(updated); trip = updated
        if (!arrived) arrivalPromptShown = false

        val profile = TransportCatalog.profile(newMode)
        val vehicle = TravelDetails.summary(newMode, details)
        val modeChanged = !newMode.equals(previousMode, ignoreCase = true)
        insertEvent(
            t.tripId, EventTypes.LEG_STARTED, EventSource.DRIVER_MANUAL, now, lat, lng,
            buildMap<String, Any?> {
                put("legIndex", nextIndex); put("mode", newMode)
                put("vehicle", vehicle); put("breakdown", breakdown)
                put("text", buildString {
                    if (breakdown) append("Vehicle trouble — continuing ")
                    else append("Continuing ")
                    append(profile.travellingSuffix)
                    if (vehicle.isNotBlank()) append(" ($vehicle)")
                })
                // The follower hears this once, as the plan change below.
                if (modeChanged) put("announcedAs", EventTypes.TRAVEL_MODE_CHANGED)
            }, false
        )

        var s = s0.copy(legIndex = nextIndex, arrivalPromptDue = arrived && s0.arrivalPromptDue, updatedAtMs = now,
            distanceCoveredM = coveredNow, progressPct = progress(coveredNow, s0.distanceRemainingM))
        rideKind(newMode)?.let { kind ->
            TransitData.load(appContext).nearestStation(here, kind)?.let { metroAnchor = MetroAnchor(nextIndex, it, coveredNow, kind) }
        }
        if (modeChanged) {
            // A new mode is a revision of the plan, and the coach re-reads it
            // at once: car → train ends driving-break guidance immediately.
            revisePlan(updated, s, now, mode = newMode, role = WellbeingCoach.defaultRole(newMode).name)
            coachTick(updated, s, now)
        }
        // Movement is judged at the new stage's pace: walking is going somewhere.
        detector = StopDetector(cfg.forMode(newMode))
        persistAndPush(updated, s, force = true)
        state = s
        if (updated.cloudEnabled) appScope.launch { sync.writeMetaUpdate(updated, metaMap(updated)) }
        onSamplingChanged?.invoke()
        if (hereName == EN_ROUTE) nameSwitchPointLater(t.tripId, lat, lng, current?.legIndex, nextIndex)
        return SwitchResult.Ok
    }

    /**
     * A change made away from any saved place is first called "En route";
     * the phone's geocoder is asked afterwards, outside the lock, and the
     * two stages that meet there take its name when it answers.
     */
    private fun nameSwitchPointLater(tripId: String, lat: Double, lng: Double, endedIndex: Int?, startedIndex: Int) {
        appScope.launch {
            val name = runCatching { resolvePlace(lat, lng) }.getOrNull() ?: return@launch
            lock.withLock {
                if (trip?.tripId != tripId) return@withLock
                val rows = db.legDao().forTrip(tripId)
                rows.firstOrNull { it.legIndex == startedIndex && it.fromName == EN_ROUTE }
                    ?.let { db.legDao().upsert(it.copy(fromName = name)) }
                endedIndex?.let { i ->
                    rows.firstOrNull { it.legIndex == i && it.toName == EN_ROUTE }
                        ?.let { db.legDao().upsert(it.copy(toName = name)) }
                }
                legs = db.legDao().forTrip(tripId)
            }
            runCatching { relabelFares(tripId) }
        }
    }

    /**
     * What to call the point a stage was switched at.
     *
     * Reverse geocoding would be the obvious answer and is the wrong one here:
     * it needs the network at the moment the traveller is changing vehicles,
     * which is exactly when they may not have it, and a stage that failed to
     * be created because a lookup timed out would be indefensible. A saved
     * place nearby is free and often better anyway -- "Home" beats a road
     * name. Otherwise the honest answer is that they were between places.
     */
    private suspend fun nameForPoint(p: GeoPoint, modes: Collection<String> = emptyList()): String {
        val saved = runCatching { db.savedPlaceDao().all() }.getOrNull().orEmpty()
            .map { PlaceResolver.SavedPlace(it.name, it.lat, it.lng) }
        // Getting on or off a metro or a train happens at a station, and the
        // station is what a rider calls it: "Habsiguda Metro", not the area
        // the geocoder files it under. A saved place right there still wins.
        if (modes.isNotEmpty()) {
            PlaceResolver.nearestSavedLabel(saved, p.lat, p.lng, SAVED_BEATS_STATION_M)?.let { return it }
            TransitData.load(appContext).stationLabel(p, modes)?.let { return it }
        }
        return PlaceResolver.nearestSavedLabel(saved, p.lat, p.lng, NEAR_PLACE_M) ?: EN_ROUTE
    }

    /** At the journey's destination: arrived, or standing within its radius. */
    private fun atDestination(t: ActiveTripEntity, s: TripStateEntity): Boolean {
        if (t.arrivedAtMs != null || s.journey == JourneyStatus.ARRIVED.name) return true
        val lat = s.lat ?: return false
        val lng = s.lng ?: return false
        return Geo.haversineM(GeoPoint(lat, lng), GeoPoint(t.destLat, t.destLng)) <= cfg.arrivalRadiusM
    }

    /** The network a mode rides on (the metro; a water metro or ferry), if any. */
    private fun rideKind(mode: String?) = com.trippulse.app.domain.TransitNetwork.kindOf(mode)

    /**
     * Never less than the track: on a metro ride, a fix near a station
     * raises what the journey has covered to at least what it had before the
     * ride plus the track from the boarding station to this one. The fixes
     * on a train are few and far apart, and the straight lines between them
     * cut every bend; a count may run a little long, never short.
     */
    private suspend fun metroFloor(t: ActiveTripEntity, covered: Double, fix: Fix): Double {
        val leg = activeLeg()
        val kind = rideKind(leg?.mode)
        if (leg == null || kind == null || leg.startedAtMs == null) { metroAnchor = null; return covered }
        if (fix.accuracyM > METRO_FIX_ACCURACY_M) return covered
        val net = TransitData.current.takeIf { !it.isEmpty } ?: return covered
        val anchor = metroAnchorFor(t, leg, net, covered, fix.timeMs)?.also { metroAnchor = it } ?: return covered
        val here = net.nearestStation(fix.point, kind) ?: return covered
        val ride = net.ride(anchor.station, here)?.metres ?: return covered
        return maxOf(covered, anchor.coveredBeforeM + ride)
    }

    /**
     * Where the metro ride [leg] began, and what the journey had covered
     * before it. Known from the moment of boarding; when picked up mid-ride
     * (after a restart) it is what is counted now less the ride's own fixes.
     */
    private suspend fun metroAnchorFor(
        t: ActiveTripEntity, leg: TripLegEntity, net: com.trippulse.app.domain.TransitNetwork, coveredNowM: Double, nowMs: Long
    ): MetroAnchor? {
        metroAnchor?.takeIf { it.legIndex == leg.legIndex }?.let { return it }
        val kind = rideKind(leg.mode) ?: return null
        val station = net.nearestStation(GeoPoint(leg.fromLat, leg.fromLng), kind) ?: return null
        val started = leg.startedAtMs ?: return null
        val ridden = StageRepair.pathLengthM(db.locationDao().allForTrip(t.tripId), { it.tMs }, { it.lat }, { it.lng }, started, nowMs) ?: 0.0
        return MetroAnchor(leg.legIndex, station, (coveredNowM - ridden).coerceAtLeast(0.0), kind)
    }

    /**
     * The first good fix after a metro ride that ended on a stale one: if it
     * is at a station further down the line than the one the ride was named
     * after, that is where the ride really ended -- both stages that meet
     * there take its name, and the count its track.
     */
    private suspend fun settleMetroExit(covered: Double, fix: Fix, now: Long): Double {
        val x = metroExit ?: return covered
        if (now - x.atMs > METRO_EXIT_SETTLE_MS || trip?.tripId != x.tripId) { metroExit = null; return covered }
        if (fix.accuracyM > METRO_FIX_ACCURACY_M) return covered
        metroExit = null
        val net = TransitData.current.takeIf { !it.isEmpty } ?: return covered
        val station = net.nearestStation(fix.point, x.anchor.kind) ?: return covered
        if (station == x.station) return covered
        val was = net.ride(x.anchor.station, x.station)?.metres ?: return covered
        val further = net.ride(x.anchor.station, station)?.metres ?: return covered
        if (further <= was) return covered
        val label = com.trippulse.app.domain.TransitNetwork.label(net.stations[station])
        val rows = db.legDao().forTrip(x.tripId)
        rows.firstOrNull { it.legIndex == x.endedIndex && it.toName == x.label }?.let { db.legDao().upsert(it.copy(toName = label)) }
        rows.firstOrNull { it.legIndex == x.startedIndex && it.fromName == x.label }?.let { db.legDao().upsert(it.copy(fromName = label)) }
        legs = db.legDao().forTrip(x.tripId)
        runCatching { relabelFares(x.tripId) }
        return maxOf(covered, x.anchor.coveredBeforeM + further)
    }

    /** Why a mid-journey mode change did or did not happen. */
    sealed interface SwitchResult {
        data object Ok : SwitchResult
        /** The journey is finished; nothing about it can change any more. */
        data object NotEditable : SwitchResult
        /** No fix yet, so there is no honest point to switch at. */
        data object NoLocationYet : SwitchResult
        data class MissingDetails(val labels: List<String>) : SwitchResult
    }

    /**
     * Corrects the vehicle details of a stage that is still running.
     *
     * Only the details -- never the destination, never the mode. Those are
     * plan changes, and go through [changeDestination] and [switchMode], which
     * record them as new plan revisions everyone following is told about;
     * nothing here may quietly re-point the journey.
     *
     * This exists for the ordinary case of getting on a train and only then
     * reading the coach number off the ticket.
     */
    suspend fun updateLegDetails(legIndex: Int, details: Map<String, String>): Boolean =
        lock.withLock {
            val t = editableTrip() ?: return@withLock false
            val existing = legs.firstOrNull { it.legIndex == legIndex } ?: return@withLock false
            // A finished stage is history and history does not get corrected.
            if (existing.completedAtMs != null) return@withLock false
            if (!TravelDetails.isComplete(existing.mode, details)) return@withLock false

            db.legDao().upsert(
                existing.copy(
                    detailsJson = LegDetails.toJson(details),
                    seat = details[DetailKeys.SEAT],
                    bookingRef = details[DetailKeys.PNR],
                    fuelType = details[DetailKeys.FUEL_TYPE]
                        ?.takeIf { TransportCatalog.isPrivate(existing.mode) }
                )
            )
            legs = db.legDao().forTrip(t.tripId)

            val summary = TravelDetails.summary(existing.mode, details)
            if (summary.isNotBlank()) {
                insertEvent(
                    t.tripId, EventTypes.QUICK_NOTE, EventSource.DRIVER_MANUAL,
                    System.currentTimeMillis(), existing.fromLat, existing.fromLng,
                    mapOf("text" to "Travelling on $summary"), false
                )
            }
            true
        }

    suspend fun advanceToNextLeg() = lock.withLock {
        val t = editableTrip() ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        val nextIndex = t.activeLegIndex + 1
        val next = legs.firstOrNull { it.legIndex == nextIndex } ?: return@withLock

        db.legDao().markCompleted(t.tripId, t.activeLegIndex, now)
        activeLeg()?.let { fareOpportunity(t.tripId, it.mode, it.fromName, it.toName, now) }
        insertEvent(
            t.tripId, EventTypes.LEG_COMPLETED, EventSource.DRIVER_MANUAL, now, s.lat, s.lng,
            mapOf("legIndex" to t.activeLegIndex, "mode" to (activeLeg()?.mode ?: t.transportMode)), false
        )

        val moved = t.copy(activeLegIndex = nextIndex, transportMode = next.mode, fuelType = next.fuelType)
        db.tripDao().update(moved)
        db.legDao().markStarted(t.tripId, nextIndex, now)
        trip = moved
        legs = db.legDao().forTrip(t.tripId)

        // A new leg is a new road: reset the detector and refetch the route so
        // no state leaks across a change of vehicle.
        detector = StopDetector(cfg.forMode(next.mode))
        currentRoute = routing.route(GeoPoint(next.fromLat, next.fromLng), GeoPoint(next.toLat, next.toLng))
        routeFetchedAtMs = now

        s = s.copy(
            legIndex = nextIndex, journey = JourneyStatus.READY.name,
            drivingSinceMs = now, updatedAtMs = now
        )
        // A planned stage is not a change of plan: keep the plan's mode and
        // role in step for the coach without announcing anything new.
        val plan = planFor(moved)
        if (!plan.mode.equals(next.mode, ignoreCase = true)) {
            plans.append(
                t.tripId,
                plan.copy(
                    version = plan.version + 1, atMs = now, mode = next.mode,
                    role = WellbeingCoach.defaultRole(next.mode).name, reason = EventTypes.LEG_STARTED
                )
            )
        }
        announceLeg(moved, nextIndex, now)
        if (moved.cloudEnabled) appScope.launch { sync.writeMetaUpdate(moved, metaMap(moved)) }
        persistAndPush(moved, s, force = true); state = s
        onSamplingChanged?.invoke()
    }

    private suspend fun announceLeg(t: ActiveTripEntity, index: Int, now: Long) {
        val leg = legs.firstOrNull { it.legIndex == index } ?: return
        val profile = TransportCatalog.profile(leg.mode)
        insertEvent(
            t.tripId, EventTypes.LEG_STARTED, EventSource.DRIVER_MANUAL, now, leg.fromLat, leg.fromLng,
            mapOf(
                "legIndex" to index, "mode" to leg.mode,
                "from" to leg.fromName, "to" to leg.toName,
                "text" to "${profile.emoji} ${leg.fromName} → ${leg.toName}${profile.travellingSuffix}"
            ), false
        )
    }

    // -----------------------------------------------------------------------
    // Location + tick loop
    // -----------------------------------------------------------------------

    suspend fun onLocation(fix: Fix) = lock.withLock {
        val t = trip ?: return@withLock
        var s = state ?: return@withLock
        if (terminal(s)) return@withLock
        val now = fix.timeMs
        val profile = activeProfile()

        // rolling display speed
        val speedKmh = fix.speedMps?.takeIf { it >= 0f }?.let { it * 3.6 }
            ?: derivedSpeedKmh(fix)

        // accumulate covered distance only while actually moving (kills jitter);
        // "moving" is the stage's own pace -- a walk is not a car in traffic
        val moveKmh = cfg.forMode(activeLeg()?.mode ?: t.transportMode).restartSpeedKmh
        var covered = s.distanceCoveredM
        val ldp = lastDistancePoint
        if (ldp != null && lastFixMs > 0 && now - lastFixMs >= DistanceLedger.GAP_MS) {
            // The phone was not reporting. Whatever was driven in between is
            // credited now, from where the record stopped to here.
            val credit = DistanceLedger.gapCredit(ldp, fix.point, cfg.roadDistanceFactor, gapRoomM(fix.point, covered))
            if (credit > 0) {
                covered += credit; lastDistancePoint = fix.point
                if (s.distanceRemainingM > 0) gapCredit = GapCredit(now, Geo.haversineM(ldp, fix.point), credit, s.distanceRemainingM, covered)
                inferTollsAcrossSilence(t, ldp, fix.point, lastFixMs, now, profile)
            }
        } else if (ldp != null && speedKmh >= moveKmh) {
            covered += Geo.haversineM(ldp, fix.point)
        }
        if (speedKmh >= moveKmh || ldp == null) lastDistancePoint = fix.point
        lastFixMs = now

        // persist a location sample (throttled by time or distance)
        val movedEnough = lastPersistPoint?.let { Geo.haversineM(it, fix.point) >= 20 } ?: true
        if (now - lastPersistMs >= 8_000 || movedEnough) {
            db.locationDao().insert(
                LocationSampleEntity(
                    tripId = t.tripId, tMs = now, lat = fix.point.lat, lng = fix.point.lng,
                    accuracyM = fix.accuracyM.toDouble(), speedMps = fix.speedMps?.toDouble(),
                    bearing = fix.bearing?.toDouble(), syncStatus = "PENDING"
                )
            )
            lastPersistMs = now
            lastPersistPoint = fix.point
        }

        // ----- toll plazas passed on the way (car and bike) -----
        maybeTollFromLocation(t, fix, now, profile)

        // ----- movement / stop detection -----
        val move = detector.onFix(fix)
        s = applyMovement(t, s, move, fix, now, profile)

        // ----- route refresh + remaining distance/time -----
        maybeRefreshRoute(t, fix.point, now)
        val remainingM = remainingDistanceM(fix.point)
        val remainingS = remainingTravelSeconds(remainingM)
        covered = refineGapCredit(covered, remainingM, now)
        covered = metroFloor(t, covered, fix)
        covered = settleMetroExit(covered, fix, now)

        // ----- arrival detection -----
        s = maybeArrival(t, s, fix.point, now)

        // ----- assemble state -----
        val progress = progress(covered, remainingM)
        s = s.copy(
            lat = fix.point.lat, lng = fix.point.lng, accuracyM = fix.accuracyM.toDouble(),
            speedKmh = speedKmh, bearing = fix.bearing?.toDouble(),
            lastLocationAtMs = now, batteryPct = fix.batteryPct ?: s.batteryPct,
            distanceCoveredM = covered, distanceRemainingM = remainingM,
            progressPct = progress,
            connectivity = connectivityNow().name, updatedAtMs = now
        )

        // ETA every ~60s or right after a break/stop change
        if (now - lastEtaCalcMs >= 60_000) {
            s = recomputeEta(t, s, remainingS, remainingM, now)
            lastEtaCalcMs = now
        }

        persistAndPush(t, s)
        state = s
    }

    /** Periodic tick (~30s) for time-based transitions and heartbeats. */
    /**
     * The periodic pass, split so the lock is released before anything that
     * needs to take it again. recordBackOnline() is one such thing, and
     * calling it from inside the locked body would deadlock the journey.
     */
    suspend fun onTick() {
        // In its own child, so the watchdog can cancel a stuck pass without
        // ending the service's tick loop with it.
        kotlinx.coroutines.coroutineScope { launch { onTickLocked() } }
        if (backOnlineDue) {
            backOnlineDue = false
            recordBackOnline()
        }
    }

    private suspend fun onTickLocked() = lock.withLock {
        val t = trip ?: return@withLock
        var s = state ?: return@withLock
        if (terminal(s)) return@withLock
        val now = System.currentTimeMillis()
        val profile = activeProfile()

        // dwell-based stop maturation / long-stop
        val move = detector.onTick(now)
        if (move != null) s = applyMovement(t, s, move, null, now, profile)

        expenseTick(t, s)

        // Arrived and not yet closed: one stronger reminder, then — after
        // sustained arrival with no answer — close it for them, pending review.
        if (closeWatch(t, s, now)) return@withLock
        checkSimChange(t, now)
        // A tick is proof the app is alive, so a journey still flagged dark
        // has plainly come back -- most often after a reboot, where the
        // shutdown was recorded and BOOT_COMPLETED restarted us.
        if (t.wentDarkAtMs != null) backOnlineDue = true

        // The wellbeing coach (water, food, breaks — per travel mode) and the
        // circle's hourly update. Replaces the old fixed meal-window prompt.
        coachTick(t, s, now)

        // battery-low (edge triggered)
        val bat = s.batteryPct
        if (bat != null && bat <= cfg.lowBatteryPct && !batteryLowFired) {
            batteryLowFired = true
            insertEvent(t.tripId, EventTypes.BATTERY_LOW, EventSource.SENSOR_OBSERVED, now, s.lat, s.lng,
                mapOf("battery" to bat), false)
        }
        if (bat != null && bat > cfg.lowBatteryPct + 5) batteryLowFired = false

        s = s.copy(connectivity = connectivityNow().name, updatedAtMs = now)
        if (now - storyMadeAtMs >= STORY_EVERY_MS) refreshStory(t, s, now)
        persistAndPush(t, s, heartbeat = true)
        state = s
    }

    // -----------------------------------------------------------------------
    // The story so far, told by the traveller's phone and pushed with the live
    // state, so the follower app and the web viewer show the same words.
    // -----------------------------------------------------------------------

    @Volatile private var storyCache: Map<String, Any?>? = null
    @Volatile private var storyMadeAtMs = 0L

    private suspend fun refreshStory(t: ActiveTripEntity, s: TripStateEntity, now: Long) {
        storyMadeAtMs = now
        runCatching {
            val events = db.eventDao().allForTrip(t.tripId).filterNot { it.sensitive }.map(EventCodec::toDomain)
            // One fix a minute is plenty for the story; the full log can be thousands of rows.
            val samples = ArrayList<com.trippulse.app.domain.report.JourneyStory.Sample>()
            var last = Long.MIN_VALUE / 2
            for (p in db.locationDao().allForTrip(t.tripId)) {
                if (p.tMs - last >= 60_000L) { samples += com.trippulse.app.domain.report.JourneyStory.Sample(p.tMs, p.lat, p.lng); last = p.tMs }
            }
            val input = com.trippulse.app.domain.report.JourneyStory.Input(
                who = ownerName(), origin = t.originName, destination = t.destName,
                originLat = t.originLat, originLng = t.originLng, destLat = t.destLat, destLng = t.destLng,
                mode = t.transportMode, startedAtMs = t.startedAtMs ?: t.createdAtMs, endedAtMs = t.completedAtMs,
                nowMs = now, events = events, samples = samples, distanceM = s.distanceCoveredM,
                routeDistanceM = t.totalRouteDistanceM.takeIf { it > 0 }, fuelType = t.fuelType, seedKey = t.tripId,
                measures = measures(), tollPassName = market().tollPassName
            )
            // Names from what the phone already knows; no network from inside the tick.
            val book = com.trippulse.app.domain.report.PlaceBook()
            runCatching { db.savedPlaceDao().all() }.getOrNull().orEmpty().forEach { book.add(it.lat, it.lng, it.name, com.trippulse.app.domain.report.PlaceBook.Rank.SAVED) }
            com.trippulse.app.domain.report.JourneyStory.seed(input, book)
            val story = com.trippulse.app.domain.report.JourneyStory.build(input, book)
            storyCache = com.trippulse.app.domain.report.StoryCodec.encode(story, input, now)
        }.onFailure { android.util.Log.w("TripManager", "story not refreshed", it) }
    }

    // -----------------------------------------------------------------------
    // Traveller actions
    // -----------------------------------------------------------------------

    data class Checkpoint(
        val water: Boolean = false, val food: Boolean = false, val toilet: Boolean = false,
        val rest: Boolean = false, val fuel: Boolean = false, val charge: Boolean = false,
        val tea: Boolean = false, val snack: Boolean = false,
        val other: Boolean = false,
        /** Set when the traveller explicitly named the meal; null asks the app. */
        val mealKind: Nourishment? = null
    ) {
        val isEmpty: Boolean
            get() = !water && !food && !toilet && !rest && !fuel && !charge && !tea && !snack && !other
    }

    /**
     * Records what happened at a stop, or — on public transport — simply what
     * the traveller consumed.
     *
     * The distinction matters and is the profile's to make: for a car, this is
     * a break (the vehicle halted, and the break record feeds the ETA budget);
     * on a train, eating lunch is a wellbeing note and nothing more. A break
     * row is therefore only written when [TransportProfile.wellbeingIsBreak].
     */
    /**
     * The break currently under way at a stop, while the vehicle is stationary.
     * Items logged during the same stop join it; driving off closes it with
     * its real duration. Kept in memory: after a restart the break simply
     * keeps the last time something was logged as its end.
     */
    private data class OpenBreak(
        val breakId: String, val startMs: Long, val lat: Double?, val lng: Double?,
        val place: String?, val items: Map<String, Any?>, val countsAsBreak: Boolean
    )
    private var openBreak: OpenBreak? = null

    /**
     * Logs a break (or, on public transport, simply what was had).
     *
     * One stop is one break: tapping 💧 then 🍪 then 🚻 at the same halt adds
     * to that break instead of creating three. Each break records where it was
     * (named on the phone), when it began and how long it lasted. Start and
     * duration come from stop detection when the vehicle is halted; otherwise
     * the traveller supplies them ([startAtMs], [durationS]).
     *
     * Tea/coffee and snacks count as a break but never as water — water is
     * only ever what the traveller said was water.
     */
    suspend fun submitCheckpoint(
        c: Checkpoint, startAtMs: Long? = null, durationS: Long? = null,
        /** The refuel's amount is being recorded with it, so don't ask again. */
        fuelAmountKnown: Boolean = false
    ) {
        val lat0 = state?.lat; val lng0 = state?.lng
        // Name the place outside the lock: the geocoder can take a second.
        val placeName = if (openBreak?.place != null && startAtMs == null) openBreak?.place else resolvePlace(lat0, lng0)
        lock.withLock {
            submitCheckpointLocked(c, startAtMs, durationS, placeName)
            // Having eaten is wellbeing; having *paid* is a separate question,
            // asked when it's safe — never assumed from "ate something".
            val t = editableTrip() ?: return@withLock
            val now = System.currentTimeMillis()
            val at = placeName?.let { " · $it" }.orEmpty()
            if (c.food || c.tea || c.snack) addOpportunity(t.tripId, Expenses.Category.FOOD, now, "Food$at")
            if ((c.fuel || c.charge) && !fuelAmountKnown) {
                addOpportunity(t.tripId, Expenses.Category.FUEL, now, (if (c.charge) "Charging" else "Fuel") + at)
            }
        }
    }

    private suspend fun submitCheckpointLocked(c: Checkpoint, startAtMs: Long?, durationS: Long?, placeName: String?) {
        val t = editableTrip() ?: return
        var s = state ?: return
        if (c.isEmpty) return
        val now = System.currentTimeMillis()
        val profile = activeProfile()
        val countsAsBreak = profile.wellbeingIsBreak
        val stoppedNow = s.stopStartedAtMs != null && s.journey in STATIONARY_STATES

        val meal = if (c.food) (c.mealKind ?: inferMeal(t.tripId, now)) else null
        val added = linkedMapOf<String, Any?>(
            "water" to c.water, "food" to c.food, "toilet" to c.toilet, "rest" to c.rest,
            "fuel" to c.fuel, "charge" to c.charge, "tea" to c.tea, "snack" to c.snack, "other" to c.other
        )

        // ---- when did this break happen, and is it still going on? ----
        val explicit = startAtMs != null || durationS != null
        val startMs = (startAtMs ?: s.checkpointStopStartMs ?: s.stopStartedAtMs?.takeIf { stoppedNow } ?: now)
            .coerceAtMost(now)
        val endMs: Long? = when {
            durationS != null -> (startMs + durationS * 1000).coerceAtMost(now)
            s.checkpointStopEndMs != null -> s.checkpointStopEndMs
            stoppedNow -> null            // still at the stop: closes when the vehicle moves off
            else -> now
        }

        // ---- join the break already under way at this stop ----
        val current = openBreak?.takeIf { ob ->
            countsAsBreak && !explicit &&
                (ob.lat == null || s.lat == null || s.lng == null ||
                    Geo.haversineM(GeoPoint(ob.lat, ob.lng ?: 0.0), GeoPoint(s.lat!!, s.lng!!)) < BREAK_MERGE_RADIUS_M)
        }
        val breakId = current?.breakId ?: UUID.randomUUID().toString()
        val breakStart = minOf(current?.startMs ?: startMs, startMs)
        val merged = LinkedHashMap<String, Any?>().apply {
            added.forEach { (k, v) -> put(k, v == true || current?.items?.get(k) == true) }
            put("meal", meal?.key ?: current?.items?.get("meal"))
        }
        val newlyAdded = added.filter { (k, v) -> v == true && current?.items?.get(k) != true }.keys

        if (countsAsBreak) {
            val place = placeName ?: current?.place
            insertEvent(
                t.tripId, EventTypes.BREAK_CHECKPOINT, EventSource.DRIVER_CONFIRMATION, now, s.lat, s.lng,
                merged + mapOf(
                    "breakId" to breakId, "place" to place, "startMs" to breakStart, "endMs" to endMs,
                    "durationS" to endMs?.let { ((it - breakStart) / 1000).coerceAtLeast(0) },
                    "open" to (endMs == null), "countsAsBreak" to true
                ), false
            )
            db.breakDao().upsert(
                BreakRecordEntity(
                    breakId = breakId, tripId = t.tripId,
                    startMs = breakStart, endMs = endMs,
                    durationS = endMs?.let { ((it - breakStart) / 1000).coerceAtLeast(0) },
                    lat = s.lat, lng = s.lng,
                    water = merged["water"] == true, food = merged["food"] == true, toilet = merged["toilet"] == true,
                    rest = merged["rest"] == true, fuel = merged["fuel"] == true, charge = merged["charge"] == true,
                    other = merged["other"] == true, confirmationSource = "DRIVER_CONFIRMATION",
                    tea = merged["tea"] == true, snack = merged["snack"] == true, mealKind = merged["meal"] as? String
                )
            )
            openBreak = if (endMs == null) OpenBreak(breakId, breakStart, s.lat, s.lng, place, merged, true) else null
        } else {
            // Public transport: eating on a train is a wellbeing note, not a break.
            insertEvent(
                t.tripId, EventTypes.BREAK_CHECKPOINT, EventSource.DRIVER_CONFIRMATION, now, s.lat, s.lng,
                added + mapOf("meal" to meal?.key, "countsAsBreak" to false), false
            )
        }

        // Individual items, tagged with their break so timelines can fold them in.
        val tag: Map<String, Any?> = if (countsAsBreak) mapOf("breakId" to breakId) else emptyMap()
        val itemTime = if (explicit) startMs else now
        if ("water" in newlyAdded || (!countsAsBreak && c.water)) reportWellbeing(t, EventTypes.WATER_REPORTED, itemTime, s.lat, s.lng, tag)
        if ("food" in newlyAdded || (!countsAsBreak && c.food)) reportWellbeing(
            t, EventTypes.FOOD_REPORTED, itemTime, s.lat, s.lng, tag + mapOf("meal" to (meal ?: Nourishment.SNACK).key)
        )
        if ("tea" in newlyAdded || (!countsAsBreak && c.tea)) reportWellbeing(t, EventTypes.TEA_COFFEE_REPORTED, itemTime, s.lat, s.lng, tag)
        if ("snack" in newlyAdded || (!countsAsBreak && c.snack)) reportWellbeing(t, EventTypes.SNACK_REPORTED, itemTime, s.lat, s.lng, tag)
        if ("toilet" in newlyAdded || (!countsAsBreak && c.toilet)) reportWellbeing(t, EventTypes.TOILET_REPORTED, itemTime, s.lat, s.lng, tag)
        if ("rest" in newlyAdded || (!countsAsBreak && c.rest)) reportWellbeing(t, EventTypes.REST_REPORTED, itemTime, s.lat, s.lng, tag)
        if ("fuel" in newlyAdded || (!countsAsBreak && c.fuel)) reportWellbeing(t, EventTypes.FUEL_STOP, itemTime, s.lat, s.lng, tag)
        if ("charge" in newlyAdded || (!countsAsBreak && c.charge)) reportWellbeing(t, EventTypes.CHARGE_STOP, itemTime, s.lat, s.lng, tag)

        if (c.water) notifier.cancelWellbeingNudge("water")
        if (c.food) notifier.cancelWellbeingNudge("food")
        s = s.copy(
            // Water is only ever water. Tea, coffee and snacks are a break, not hydration.
            waterAtMs = if (c.water) itemTime else s.waterAtMs,
            foodAtMs = if (c.food) itemTime else s.foodAtMs,
            toiletAtMs = if (c.toilet) itemTime else s.toiletAtMs,
            restAtMs = if (c.rest) itemTime else s.restAtMs,
            fuelAtMs = if (c.fuel) itemTime else s.fuelAtMs,
            lastBreakEndAtMs = if (countsAsBreak) (endMs ?: now) else s.lastBreakEndAtMs,
            checkpointDue = false, checkpointStopStartMs = null,
            checkpointStopEndMs = null, checkpointStopDurationS = null,
            updatedAtMs = now
        )
        // a break changes the ETA break budget
        s = recomputeEta(t, s, remainingTravelSeconds(s.distanceRemainingM), s.distanceRemainingM, now)
        persistAndPush(t, s, force = true)
        state = s
    }

    /** Closes the break under way when the vehicle drives off, with its real duration. */
    private suspend fun closeOpenBreak(tripId: String, endMs: Long) {
        val ob = openBreak ?: return
        openBreak = null
        val durationS = ((endMs - ob.startMs) / 1000).coerceAtLeast(0)
        insertEvent(
            tripId, EventTypes.BREAK_CHECKPOINT, EventSource.DRIVER_CONFIRMATION, endMs, ob.lat, ob.lng,
            ob.items + mapOf(
                "breakId" to ob.breakId, "place" to ob.place, "startMs" to ob.startMs, "endMs" to endMs,
                "durationS" to durationS, "open" to false, "countsAsBreak" to true
            ), false
        )
        db.breakDao().allForTrip(tripId).firstOrNull { it.breakId == ob.breakId }?.let {
            db.breakDao().upsert(it.copy(endMs = endMs, durationS = durationS))
        }
    }

    /** A short, human name for where the traveller is — on the phone, no network key. */
    private suspend fun resolvePlace(lat: Double?, lng: Double?): String? {
        if (lat == null || lng == null) return null
        // A saved place the user named ("Friend's house") beats a geocoded area
        // name, and is free and offline — the same rule the rest of the app uses.
        val saved = runCatching { db.savedPlaceDao().all() }.getOrNull().orEmpty()
            .map { PlaceResolver.SavedPlace(it.name, it.lat, it.lng) }
        PlaceResolver.nearestSavedLabel(saved, lat, lng)?.let { return it }
        // The platform geocoder blocks and can't be interrupted, so it runs on
        // its own and we stop *waiting* after 3 s — a break is saved without a
        // place name rather than not at all.
        val lookup = appScope.async(kotlinx.coroutines.Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                android.location.Geocoder(appContext).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { a ->
                    listOfNotNull(a.subLocality, a.locality ?: a.subAdminArea)
                        .distinct().joinToString(", ").ifBlank { null }
                }
            } catch (_: Exception) { null }
        }
        return kotlinx.coroutines.withTimeoutOrNull(3_000) { lookup.await() }
    }

    /**
     * One-tap wellbeing logging from the journey screen.
     *
     * Deliberately a thin wrapper over [submitCheckpoint] so a tap on
     * "☕ Tea" behaves identically whether the traveller reached it through the
     * break sheet or the quick row — one rule set, one code path.
     */
    suspend fun logNourishment(kind: Nourishment) {
        val checkpoint = when (kind) {
            Nourishment.WATER -> Checkpoint(water = true)
            Nourishment.TEA_COFFEE -> Checkpoint(tea = true)
            Nourishment.SNACK -> Checkpoint(snack = true)
            else -> Checkpoint(food = true, mealKind = kind)
        }
        submitCheckpoint(checkpoint)
    }

    /**
     * Which meal a bare "I ate" tap represents.
     *
     * The clock proposes (morning → breakfast, afternoon → lunch, night →
     * dinner) and the day's history disposes: a second meal inside the same
     * window is a snack, because the anchor meal was already had.
     */
    private suspend fun inferMeal(tripId: String, nowMs: Long): Nourishment {
        val dayKey = TimeFmt.dayKey(nowMs)
        val loggedToday = db.eventDao().allForTrip(tripId)
            .asSequence()
            .filter { it.type == EventTypes.FOOD_REPORTED && TimeFmt.dayKey(it.eventTimeMs) == dayKey }
            .mapNotNull { Nourishment.fromKey(EventCodec.payloadFromJson(it.payloadJson)["meal"] as? String) }
            .toSet()
        return MealClassifier.classifyFood(TimeFmt.hourOfDay(nowMs), loggedToday)
    }

    suspend fun skipCheckpoint() = lock.withLock {
        val t = editableTrip() ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        insertEvent(t.tripId, EventTypes.BREAK_CHECKPOINT_SKIPPED, EventSource.DRIVER_MANUAL, now, s.lat, s.lng, emptyMap(), false)
        s = s.copy(checkpointDue = false, checkpointStopStartMs = null,
            checkpointStopEndMs = null, checkpointStopDurationS = null, updatedAtMs = now)
        persistAndPush(t, s); state = s
    }

    // -----------------------------------------------------------------------
    // Halts: long stops the traveller has told us about
    // -----------------------------------------------------------------------

    /**
     * "Yes, taking a halt". Recorded as the traveller said it — a room, family,
     * a rest stop — never guessed. Followers hear one factual line:
     * "Prashobh has taken a room in Salem and is halting here for the night."
     */
    suspend fun confirmHalt(
        type: Halts.Type, expectedMinutes: Int? = null,
        /** When the halt began, if the traveller said; a halt logged late still started on time. */
        sinceMs: Long? = null
    ) {
        // Name the place outside the lock: the geocoder can take a second.
        val place = resolvePlace(state?.lat, state?.lng)
        lock.withLock {
            val t = editableTrip() ?: return@withLock
            var s = state ?: return@withLock
            val next = transition(s, JourneyInput.OVERNIGHT_CONFIRM) ?: return@withLock
            val now = System.currentTimeMillis()
            val since = sinceMs?.coerceIn(t.startedAtMs ?: 0L, now) ?: now
            val overnight = Halts.isOvernight(TimeFmt.hourOfDay(since), expectedMinutes)
            // Written now, even when the halt began hours ago: followers fetch
            // events after the last one they saw, so a back-dated event would
            // never reach them. Timelines show `sinceMs` (TimelineEdits).
            insertEvent(
                t.tripId, EventTypes.HALT_CONFIRMED, EventSource.DRIVER_CONFIRMATION, now, s.lat, s.lng,
                buildMap<String, Any?> {
                    put("haltType", type.name); put("source", "USER_CONFIRMED"); put("overnight", overnight)
                    if (since != now) put("sinceMs", since)
                    place?.let { put("place", it) }
                    expectedMinutes?.let { put("expectedMinutes", it) }
                    Halts.Duration.fromMinutes(expectedMinutes)?.let { put("duration", it.name) }
                    put("text", Halts.confirmedText(ownerName().orEmpty(), type, place, overnight))
                }, false
            )
            coachPrefs.edit()
                .putString(haltPlaceKey(t.tripId), place)
                .remove(etaBaselineKey(t.tripId))
                .apply()
            s = s.copy(
                journey = next.name, overnightType = type.name, overnightSinceMs = since, longStopPromptDue = false,
                etaMode = EtaMode.OVERNIGHT_PENDING.name, etaLowMs = null, etaHighMs = null, etaLikelyMs = null,
                updatedAtMs = now
            )
            notifier.cancelHaltQuestion()
            // A room confirmed is not a room paid for: ask, don't assume.
            if (type == Halts.Type.ROOM) {
                addOpportunity(t.tripId, Expenses.Category.ACCOMMODATION, now, "Room" + place?.let { " · $it" }.orEmpty())
            }
            persistAndPush(t, s, force = true); state = s
            onSamplingChanged?.invoke()
        }
    }

    // -----------------------------------------------------------------------
    // Corrections: the traveller re-times or removes an entry they wrote.
    // The log is append-only, so each is a new event (see TimelineEdits).
    // -----------------------------------------------------------------------

    /**
     * Changes when a break began and how long it lasted, or removes it.
     * A new version of the break (same breakId) is written; timelines keep
     * the latest version only, so the old line disappears everywhere.
     */
    suspend fun reviseBreak(breakId: String, startMs: Long?, durationS: Long?, removed: Boolean) = lock.withLock {
        val t = editableTrip() ?: return@withLock
        val s = state ?: return@withLock
        val now = System.currentTimeMillis()
        val last = db.eventDao().allForTrip(t.tripId)
            .filter { it.type == EventTypes.BREAK_CHECKPOINT }
            .map { it to EventCodec.payloadFromJson(it.payloadJson) }
            .filter { (_, p) -> p["breakId"] == breakId }
            .maxByOrNull { (e, _) -> e.eventTimeMs } ?: return@withLock
        val old = last.second
        val oldStart = (old["startMs"] as? Number)?.toLong() ?: last.first.eventTimeMs
        val oldDuration = (old["durationS"] as? Number)?.toLong()
        val newStart = (startMs ?: oldStart).coerceAtMost(now)
        val newDuration = durationS ?: oldDuration
        val newEnd = newDuration?.let { (newStart + it * 1000).coerceAtMost(now) }
        val payload = old + mapOf(
            "startMs" to newStart, "endMs" to newEnd,
            "durationS" to newEnd?.let { ((it - newStart) / 1000).coerceAtLeast(0) },
            "open" to (newEnd == null && !removed), "removed" to removed, "revised" to true
        )
        insertEvent(t.tripId, EventTypes.BREAK_CHECKPOINT, EventSource.DRIVER_CONFIRMATION, now,
            last.first.lat, last.first.lng, payload, false)
        if (removed) {
            db.breakDao().deleteById(breakId)
            if (openBreak?.breakId == breakId) openBreak = null
        } else {
            db.breakDao().allForTrip(t.tripId).firstOrNull { it.breakId == breakId }?.let {
                db.breakDao().upsert(it.copy(startMs = newStart, endMs = newEnd,
                    durationS = newEnd?.let { e -> ((e - newStart) / 1000).coerceAtLeast(0) }))
            }
            if (openBreak?.breakId == breakId) {
                openBreak = if (newEnd == null) openBreak?.copy(startMs = newStart) else null
            }
        }
        persistAndPush(t, s.copy(updatedAtMs = now), force = true); state = state?.copy(updatedAtMs = now)
    }

    /**
     * Re-times or removes any other entry the traveller wrote (a halt, a note,
     * a single logged item). The entry itself stays in the log; a correction
     * naming it is appended and every timeline applies it.
     */
    suspend fun editTimelineEntry(eventId: String, atMs: Long?, removed: Boolean) = lock.withLock {
        val t = editableTrip() ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        val target = db.eventDao().allForTrip(t.tripId).firstOrNull { it.eventId == eventId } ?: return@withLock
        if (target.type !in EventTypes.USER_EDITABLE) return@withLock
        val at = atMs?.coerceIn(t.startedAtMs ?: 0L, now)
        insertEvent(t.tripId, EventTypes.TIMELINE_EDIT, EventSource.DRIVER_CONFIRMATION, now, null, null,
            mapOf("targetEventId" to eventId, "targetType" to target.type, "atMs" to at, "removed" to removed), false)
        // A re-timed halt moves the halt itself, so "halting since" stays true.
        if (target.type == EventTypes.HALT_CONFIRMED && s.overnightType != null && at != null && !removed) {
            s = s.copy(overnightSinceMs = at)
        }
        persistAndPush(t, s.copy(updatedAtMs = now), force = true); state = s.copy(updatedAtMs = now)
    }

    /** "Just a long break" / "Not stopped yet": nothing is recorded or shared. */
    suspend fun declineHalt() = lock.withLock {
        val t = editableTrip() ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        if (s.journey == JourneyStatus.LONG_STOP.name) {
            transition(s, JourneyInput.OVERNIGHT_DECLINE)?.let { s = s.copy(journey = it.name) }
        }
        s = s.copy(longStopPromptDue = false, updatedAtMs = now)
        notifier.cancelHaltQuestion()
        persistAndPush(t, s); state = s
    }

    /** The halt was confirmed by mistake, or plans changed: still stopped, no longer halting. */
    suspend fun cancelHalt() = lock.withLock {
        val t = editableTrip() ?: return@withLock
        var s = state ?: return@withLock
        if (s.overnightType == null) return@withLock
        val now = System.currentTimeMillis()
        transition(s, JourneyInput.OVERNIGHT_DECLINE)?.let { s = s.copy(journey = it.name) }
        insertEvent(
            t.tripId, EventTypes.HALT_CANCELLED, EventSource.DRIVER_CONFIRMATION, now, s.lat, s.lng,
            mapOf("source" to "USER_CONFIRMED", "text" to Halts.cancelledText(ownerName().orEmpty())), false
        )
        s = s.copy(overnightType = null, overnightSinceMs = null, etaMode = EtaMode.NORMAL.name, updatedAtMs = now)
        lastEtaCalcMs = 0
        persistAndPush(t, s, force = true); state = s
        onSamplingChanged?.invoke()
    }

    /** "Resume journey" — the strongest signal a halt is over. */
    suspend fun resumeFromHalt() = lock.withLock {
        val t = editableTrip() ?: return@withLock
        var s = state ?: return@withLock
        if (s.overnightType == null) return@withLock
        val now = System.currentTimeMillis()
        transition(s, JourneyInput.RESTART)?.let { s = s.copy(journey = it.name) }
        s = endHalt(t, s, now, confirmed = true)
        persistAndPush(t, s, force = true); state = s
        onSamplingChanged?.invoke()
    }

    /** Close a halt, explicitly ([confirmed]) or because the vehicle moved off. */
    private suspend fun endHalt(t: ActiveTripEntity, s: TripStateEntity, now: Long, confirmed: Boolean): TripStateEntity {
        val place = coachPrefs.getString(haltPlaceKey(t.tripId), null)
        insertEvent(
            t.tripId, EventTypes.HALT_RESUMED,
            if (confirmed) EventSource.DRIVER_CONFIRMATION else EventSource.SYSTEM_INFERRED, now, s.lat, s.lng,
            buildMap<String, Any?> {
                put("source", if (confirmed) "USER_CONFIRMED" else "GPS_INFERRED")
                s.overnightType?.let { put("haltType", it) }
                s.overnightSinceMs?.let { put("haltMinutes", (now - it) / 60_000) }
                place?.let { put("place", it) }
                put("text", Halts.resumedText(ownerName().orEmpty(), place, confirmed))
            }, false
        )
        coachPrefs.edit().remove(haltPlaceKey(t.tripId)).remove(etaBaselineKey(t.tripId)).apply()
        lastEtaCalcMs = 0
        return s.copy(
            overnightType = null, overnightSinceMs = null, etaMode = EtaMode.NORMAL.name,
            drivingSinceMs = now, lastBreakEndAtMs = now, longStopPromptDue = false, updatedAtMs = now
        )
    }

    // -----------------------------------------------------------------------
    // The journey plan: destination, role and planned halt changes
    // -----------------------------------------------------------------------

    /** The current plan revision, for screens. */
    val planFlow: StateFlow<JourneyPlan?> get() = plans.current

    fun planHistory(tripId: String): List<JourneyPlan> = plans.history(tripId)

    /**
     * The traveller is now going somewhere else. The journey is re-pointed
     * and a new plan revision records it — the old destination stays in the
     * history — and followers are told: "Journey destination changed from
     * Thrissur to Kochi."
     */
    suspend fun changeDestination(name: String, to: GeoPoint): Boolean = lock.withLock {
        val t = editableTrip() ?: return@withLock false
        var s = state ?: return@withLock false
        val clean = name.trim().ifBlank { null } ?: return@withLock false
        val now = System.currentTimeMillis()
        legs.maxByOrNull { it.legIndex }?.let {
            db.legDao().upsert(it.copy(toName = clean, toLat = to.lat, toLng = to.lng))
        }
        legs = db.legDao().forTrip(t.tripId)
        val updated = t.copy(destName = clean, destLat = to.lat, destLng = to.lng, arrivedAtMs = null)
        db.tripDao().update(updated); trip = updated
        // A new destination is a new road: fetch a fresh route and ETA.
        currentRoute = null; routeFetchedAtMs = 0; lastEtaCalcMs = 0
        arrivalPromptShown = false
        coachPrefs.edit().remove(etaBaselineKey(t.tripId)).apply()
        s = s.copy(arrivalPromptDue = false, updatedAtMs = now)
        revisePlan(updated, s, now, destination = clean)
        persistAndPush(updated, s, force = true); state = s
        if (updated.cloudEnabled) appScope.launch { sync.writeMetaUpdate(updated, metaMap(updated)) }
        true
    }

    /** Driver or passenger. The coach re-reads it at once. */
    suspend fun setTravellerRole(role: WellbeingCoach.Role) = lock.withLock {
        val t = editableTrip() ?: return@withLock
        val s = state ?: return@withLock
        val now = System.currentTimeMillis()
        revisePlan(t, s, now, role = role.name) ?: return@withLock
        coachTick(t, s, now)
    }

    /** Plan (or change, or with null drop) where the traveller intends to halt. */
    suspend fun setPlannedHalt(place: String?) = lock.withLock {
        val t = editableTrip() ?: return@withLock
        val s = state ?: return@withLock
        revisePlan(t, s, System.currentTimeMillis(), halt = JourneyPlans.HaltChange(place))
    }

    private val plans by lazy { JourneyPlanStore(appContext) }

    /** The latest plan, created from the journey itself for journeys that predate plans. */
    private fun planFor(t: ActiveTripEntity): JourneyPlan =
        plans.latest(t.tripId) ?: run {
            val mode = activeLeg()?.mode ?: t.transportMode
            JourneyPlans.initial(t.startedAtMs ?: t.createdAtMs, t.destName, mode, WellbeingCoach.defaultRole(mode).name)
                .also { plans.append(t.tripId, it) }
        }

    private suspend fun revisePlan(
        t: ActiveTripEntity, s: TripStateEntity, now: Long,
        destination: String? = null, mode: String? = null, role: String? = null,
        halt: JourneyPlans.HaltChange? = null
    ): JourneyPlans.Revision? {
        val rev = JourneyPlans.revise(planFor(t), now, destination, mode, role, halt, who = ownerName().orEmpty()) ?: return null
        plans.append(t.tripId, rev.plan)
        insertEvent(t.tripId, rev.eventType, EventSource.DRIVER_MANUAL, now, s.lat, s.lng, rev.payload, false)
        return rev
    }

    /**
     * A quick note or a transport milestone ("Boarded the train").
     *
     * [text] is stored verbatim when supplied; mode-specific quick actions pass
     * their own sentence so the timeline reads naturally on the viewer's side
     * without the viewer needing to know the mode.
     */
    suspend fun addQuickNote(type: String, text: String?) = lock.withLock {
        val t = editableTrip() ?: return@withLock
        val s = state ?: return@withLock
        // "Toll crossed" is a toll like any other: counted, pass, expense.
        if (type == EventTypes.TOLL_CROSSED) { recordManualTollLocked(); return@withLock }
        val now = System.currentTimeMillis()
        // The same entry tapped twice in a row is one entry.
        val key = "$type|${text.orEmpty()}"
        if (key == lastNoteKey && now - lastNoteAtMs < SAME_NOTE_MS) return@withLock
        lastNoteKey = key; lastNoteAtMs = now
        val mode = activeLeg()?.mode ?: t.transportMode
        val sensitive = EventTypes.isSensitiveByDefault(type)
        val payload = buildMap<String, Any?> {
            if (!text.isNullOrBlank()) put("text", text)
            put("mode", mode)
        }
        insertEvent(t.tripId, type, EventSource.DRIVER_MANUAL, now, s.lat, s.lng, payload, sensitive)
        // Out of a vehicle someone else drives means on foot until the next
        // one: the stage changes by itself, so nobody plans a commute ahead.
        // At the destination there is no "until the next one": getting out
        // there is arriving, and the journey is not reopened as a walk.
        if (type == EventTypes.DEBOARDED && !TransportCatalog.isPrivate(mode) &&
            TransportCatalog.profile(mode).key != TransportCatalog.WALK.key && !atDestination(t, s)) {
            switchModeLocked(TransportCatalog.WALK.key, emptyMap(), false)
        }
        Unit
    }

    suspend fun activateSos() = lock.withLock {
        val t = editableTrip() ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        insertEvent(t.tripId, EventTypes.SOS_ACTIVATED, EventSource.DRIVER_MANUAL, now, s.lat, s.lng,
            mapOf("battery" to s.batteryPct, "speedKmh" to s.speedKmh, "journey" to s.journey), false)
        s = s.copy(sosActive = true, sosAtMs = now, updatedAtMs = now)
        persistAndPush(t, s, force = true); state = s
        notifier.showSosActive()
        onSamplingChanged?.invoke()
        if (t.cloudEnabled) appScope.launch { sync.drain(t) }
    }

    suspend fun resolveSos() = lock.withLock {
        val t = editableTrip() ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        insertEvent(t.tripId, EventTypes.SOS_RESOLVED, EventSource.DRIVER_CONFIRMATION, now, s.lat, s.lng, emptyMap(), false)
        s = s.copy(sosActive = false, updatedAtMs = now)
        persistAndPush(t, s, force = true); state = s
        onSamplingChanged?.invoke()
    }

    suspend fun pause() = lock.withLock {
        val t = editableTrip() ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        transition(s, JourneyInput.PAUSE)?.let { s = s.copy(journey = it.name) }
        insertEvent(t.tripId, EventTypes.TRIP_PAUSED, EventSource.DRIVER_MANUAL, now, s.lat, s.lng, emptyMap(), false)
        s = s.copy(updatedAtMs = now); persistAndPush(t, s, force = true); state = s
        onSamplingChanged?.invoke()
    }

    suspend fun resume() = lock.withLock {
        val t = editableTrip() ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        transition(s, JourneyInput.RESUME)?.let { s = s.copy(journey = it.name) }
        insertEvent(t.tripId, EventTypes.TRIP_RESUMED, EventSource.DRIVER_MANUAL, now, s.lat, s.lng, emptyMap(), false)
        s = s.copy(drivingSinceMs = now, updatedAtMs = now); persistAndPush(t, s, force = true); state = s
        onSamplingChanged?.invoke()
    }

    // -----------------------------------------------------------------------
    // Going dark
    // -----------------------------------------------------------------------

    /**
     * The device is powering off, and we have seconds.
     *
     * Everything here is ordered by what a family would need if this turned
     * out to be the last thing the phone ever said: the position first, then
     * the battery that tells them whether it died or was switched off, then
     * the push. Called from a broadcast receiver with a hard deadline, so it
     * writes locally before it attempts anything over the network -- a record
     * that survives on the device beats one that was halfway to a server.
     *
     * The journey is emphatically *not* closed. A phone going off is not a
     * person arriving, and only the traveller ends a journey.
     */
    suspend fun recordShutdown(restart: Boolean) = lock.withLock {
        val t = editableTrip() ?: return@withLock
        val s = state ?: return@withLock
        val now = System.currentTimeMillis()
        val battery = s.batteryPct ?: readBatteryPct()

        insertEvent(
            t.tripId, EventTypes.DEVICE_SHUTDOWN, EventSource.SENSOR_OBSERVED, now, s.lat, s.lng,
            mapOf(
                "restart" to restart,
                "battery" to battery,
                "accuracyM" to s.accuracyM,
                "lastFixAtMs" to s.lastLocationAtMs,
                "text" to buildString {
                    append(if (restart) "Phone restarting" else "Phone switched off")
                    if (battery != null) append(" — battery $battery%")
                }
            ), false
        )

        val reason =
            if (battery != null && battery <= Darkness.FLAT_BATTERY_PCT) DarkReason.BATTERY_DIED
            else DarkReason.POWERED_OFF
        val marked = t.copy(wentDarkAtMs = now, darkReason = reason.name)
        db.tripDao().update(marked); trip = marked

        // Best effort, and genuinely best effort: if the system pulls the
        // power mid-request the local row is already written and the next
        // drain -- possibly days later, possibly from a recovered phone --
        // will carry it.
        if (marked.cloudEnabled) {
            runCatching {
                sync.pushLiveState(marked, stateMap(marked, s), force = true)
                sync.drain(marked)
            }
        }
    }

    /**
     * The device is back after a silence.
     *
     * Reported as its own event rather than left for people to infer from a
     * gap in the timeline, because "they're back" is the single thing anyone
     * watching wants to be told, and it should arrive as a notification rather
     * than as the absence of one.
     */
    suspend fun recordBackOnline() = lock.withLock {
        val t = trip ?: return@withLock
        val darkSince = t.wentDarkAtMs ?: return@withLock
        if (!isEditable(t)) return@withLock
        val s = state
        val now = System.currentTimeMillis()
        val gap = now - darkSince

        insertEvent(
            t.tripId, EventTypes.DEVICE_BACK_ONLINE, EventSource.SENSOR_OBSERVED, now,
            s?.lat, s?.lng,
            mapOf(
                "gapMs" to gap,
                "text" to "Phone back online after ${TimeFmt.durationShort(gap / 1000)}"
            ), false
        )
        // The dark markers are cleared, but the events are not: the shutdown
        // and the return both stay in the timeline, because a journey that
        // went dark for six hours and came back is a different journey from
        // one that never did, and the PDF should say so.
        val cleared = t.copy(wentDarkAtMs = null, darkReason = null)
        db.tripDao().update(cleared); trip = cleared
        if (cleared.cloudEnabled && s != null) {
            appScope.launch {
                runCatching {
                    sync.pushLiveState(cleared, stateMap(cleared, s), force = true)
                    sync.drain(cleared)
                }
            }
        }
    }

    /**
     * Notices that somebody has put a different SIM in the phone.
     *
     * Recorded once per journey. Reporting does not depend on the SIM -- the
     * journey's credentials are in the app's own storage and updates go over
     * whatever network is reachable -- so this does not change what the app
     * can do. It changes what the family knows, which is the point: a phone
     * whose SIM changed mid-journey has been opened by somebody.
     */
    /**
     * Records the device dossier for [tripId], then enriches it with the
     * public IP off the main path.
     *
     * The fingerprint baseline is set here too, so a SIM change is measured
     * from the journey's start rather than from the first tick.
     */
    /**
     * Re-captures the dossier for whatever journey is live, from outside the
     * normal flow — called on boot, where the fresh public IP is the point.
     */
    suspend fun refreshDossierForActiveTrip() {
        val active = db.tripDao().activeTrip() ?: return
        captureDossier(active.tripId)
    }

    private fun captureDossier(tripId: String) {
        val snapshot = DeviceDossier.capture(appContext)
        appScope.launch {
            val current = db.tripDao().byId(tripId) ?: return@launch
            var updated = current.copy(
                deviceJson = DeviceDossier.toJson(snapshot),
                simFingerprint = current.simFingerprint
                    ?: DeviceIdentity.simFingerprint(appContext)
            )
            db.tripDao().update(updated)
            if (trip?.tripId == tripId) trip = updated

            // The public IP is the single most useful field, and the slowest,
            // so it arrives second and never holds up the rest.
            val full = DeviceDossier.withPublicIp(appContext)
            val latest = db.tripDao().byId(tripId) ?: return@launch
            updated = latest.copy(
                deviceJson = DeviceDossier.toJson(full)
            )
            db.tripDao().update(updated)
            if (trip?.tripId == tripId) trip = updated
            if (updated.cloudEnabled) runCatching { sync.writeMetaUpdate(updated, metaMap(updated)) }
        }
    }

    private suspend fun checkSimChange(t: ActiveTripEntity, now: Long) {
        if (t.simChangedAtMs != null) return
        val remembered = t.simFingerprint
        if (remembered == null) {
            // First sighting: record the baseline rather than raise an alarm.
            val current = DeviceIdentity.simFingerprint(appContext) ?: return
            val stamped = t.copy(simFingerprint = current)
            db.tripDao().update(stamped); trip = stamped
            return
        }

        val message = when (DeviceIdentity.simEvent(appContext, remembered)) {
            DeviceIdentity.SimEvent.NONE -> return
            DeviceIdentity.SimEvent.CHANGED -> "A different SIM is in this phone"
            // The gap I could close without privileged permissions: a pulled
            // SIM is the commonest tamper, and the first move of an in-place
            // same-carrier swap the carrier fingerprint alone cannot see.
            DeviceIdentity.SimEvent.REMOVED -> "The SIM was removed from this phone"
        }

        val current = DeviceIdentity.simFingerprint(appContext)
        val changed = t.copy(simChangedAtMs = now, simFingerprint = current ?: remembered)
        db.tripDao().update(changed); trip = changed
        insertEvent(
            t.tripId, EventTypes.SIM_CHANGED, EventSource.SENSOR_OBSERVED, now,
            state?.lat, state?.lng,
            mapOf("text" to message), false
        )
        notifier.showJourneyAttention(changed.destName, message)
        if (changed.cloudEnabled) {
            val snapshot = state
            if (snapshot != null) appScope.launch {
                runCatching {
                    sync.pushLiveState(changed, stateMap(changed, snapshot), force = true)
                    sync.drain(changed)
                }
            }
        }
    }

    private fun readBatteryPct(): Int? = runCatching {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
    }.getOrNull()

    /** Dismiss the "you seem to have arrived" prompt without ending anything. */
    suspend fun dismissArrivalPrompt() = lock.withLock {
        val t = editableTrip() ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()

        // "No, I'm not there yet" has to mean it. Clearing only the flag left
        // the journey stuck in ARRIVED, so a traveller who stopped at a
        // friend's house on the way would never be asked again when they
        // genuinely did arrive -- and would meanwhile be reminded to close a
        // journey they were still on.
        transition(s, JourneyInput.STOP_CONFIRMED)?.let { s = s.copy(journey = it.name) }
        s = s.copy(arrivalPromptDue = false, updatedAtMs = now)
        reopenAfterArrival(t, now, s.lat, s.lng, confirmed = true)

        val cleared = t.copy(arrivedAtMs = null)
        db.tripDao().update(cleared); trip = cleared
        persistAndPush(cleared, s); state = s
    }

    /** "I'm still travelling", or movement away from the destination: nothing closes. */
    private fun reopenAfterArrival(t: ActiveTripEntity, now: Long, lat: Double?, lng: Double?, confirmed: Boolean) {
        arrivalPromptShown = false
        coachPrefs.edit().remove(arrivalSinceKey(t.tripId)).remove(closeReminderKey(t.tripId)).apply()
        notifier.cancelClosePrompt()
        trip = trip?.copy(arrivedAtMs = null)
        appScope.launch {
            db.tripDao().byId(t.tripId)?.let { db.tripDao().update(it.copy(arrivedAtMs = null)) }
            insertEvent(t.tripId, EventTypes.JOURNEY_REOPENED,
                if (confirmed) EventSource.DRIVER_CONFIRMATION else EventSource.SYSTEM_INFERRED, now, lat, lng,
                mapOf("source" to if (confirmed) "USER_CONFIRMED" else "GPS_INFERRED"), false)
        }
    }

    private fun arrivalSinceKey(tripId: String) = "$tripId|arrivalSince"
    private fun closeReminderKey(tripId: String) = "$tripId|closeReminder"

    /**
     * An arrived journey that is still open: one stronger reminder, then —
     * after sustained arrival with no answer and a fresh fix still at the
     * destination — close it for the traveller, pending their review. Never
     * published: followers hear nothing until the traveller approves.
     * Returns true when it closed the journey.
     */
    private suspend fun closeWatch(t: ActiveTripEntity, s: TripStateEntity, now: Long): Boolean {
        if (s.journey != JourneyStatus.ARRIVED.name) return false
        val since = coachPrefs.getLong(arrivalSinceKey(t.tripId), 0L).takeIf { it > 0 } ?: return false
        if (JourneyClosure.reminderDue(since, now, coachPrefs.getBoolean(closeReminderKey(t.tripId), false))) {
            coachPrefs.edit().putBoolean(closeReminderKey(t.tripId), true).apply()
            notifier.showClosePrompt(t.destName, stronger = true)
            insertEvent(t.tripId, EventTypes.JOURNEY_CLOSE_PROMPTED, EventSource.SYSTEM_INFERRED, now, s.lat, s.lng,
                mapOf("reminder" to true), false)
        }
        val within = s.lat != null && s.lng != null &&
            Geo.haversineM(GeoPoint(s.lat, s.lng), GeoPoint(t.destLat, t.destLng)) <= cfg.arrivalRadiusM
        if (!JourneyClosure.autoCloseDue(since, now, stillArrived = true, lastFixAtMs = s.lastLocationAtMs, lastFixWithinRadius = within)) {
            return false
        }
        closeOpenBreak(t.tripId, now)
        closeInternal(t, s, now, closingNote = null, endAtMs = t.arrivedAtMs ?: since, auto = true)
        return true
    }

    /**
     * "End journey": the traveller closes it. Tracking stops and the journey
     * waits for their review — nothing reaches followers until they approve
     * it ([approveJourney]). The only other path to closed is [closeWatch]'s
     * auto-close of a forgotten journey, which is recorded as such.
     */
    suspend fun completeTrip(closingNote: String? = null, endAtMs: Long? = null) = lock.withLock {
        // Idempotent: a double tap, or a screen that lingered, must not append
        // a second completion to a journey that is already closed.
        val t = editableTrip() ?: return@withLock
        val s = state ?: return@withLock
        val now = System.currentTimeMillis()
        val started = t.startedAtMs ?: t.createdAtMs
        val endAt = endAtMs?.takeIf { it in started..now }
        closeOpenBreak(t.tripId, endAt ?: now)
        closeInternal(t, s, now, closingNote, endAt, auto = false)
    }

    /**
     * When the traveller actually reached the destination, if they closed the
     * journey later than that: the arrival Koode detected, or else the start
     * of the final stretch of fixes inside the destination radius. Null when
     * there is nothing to suggest (still far away, or they closed on arrival).
     */
    suspend fun suggestedEndMs(tripId: String): Long? {
        val t = db.tripDao().byId(tripId) ?: return null
        val now = System.currentTimeMillis()
        val minGap = 5 * 60_000L
        t.arrivedAtMs?.let { if (now - it > minGap) return it }
        val dest = GeoPoint(t.destLat, t.destLng)
        val samples = db.locationDao().allForTrip(tripId)
        if (samples.isEmpty()) return null
        var firstInside: Long? = null
        for (i in samples.indices.reversed()) {
            val p = samples[i]
            if (Geo.haversineM(GeoPoint(p.lat, p.lng), dest) <= cfg.arrivalRadiusM) firstInside = p.tMs else break
        }
        return firstInside?.takeIf { now - it > minGap }
    }

    // -----------------------------------------------------------------------
    // Sampling interval (read by the foreground service)
    // -----------------------------------------------------------------------

    /**
     * How often to take the next fix.
     *
     * Three inputs, in priority order: an active SOS (always the fastest), the
     * battery and the user's cadence setting, then the mode of transport. A
     * twelve-hour train ride should not sample like a mountain drive — that is
     * the difference between a phone that lasts the journey and one that dies
     * halfway, which for a safety app is the whole ball game.
     */
    fun currentSamplingIntervalMs(): Long {
        val s = state
        if (s?.sosActive == true) return cfg.samplingSosS * 1000

        val prefs = settings.current
        // The mode's default is a ceiling on precision, not a floor: a traveller
        // who explicitly asked for battery saver gets battery saver everywhere.
        val modeDefault = activeProfile().defaultCadence
        val chosen = prefs.locationCadence
        var cadence = if (chosen.movingS >= modeDefault.movingS) chosen else modeDefault

        val bat = s?.batteryPct
        if (bat != null && bat <= prefs.batterySaverBelowPct) {
            cadence = com.trippulse.app.core.LocationCadence.SAVER
        }

        return when (s?.journey) {
            JourneyStatus.OVERNIGHT.name -> cfg.samplingOvernightS * 1000
            JourneyStatus.PAUSED.name -> cfg.samplingPausedS * 1000
            JourneyStatus.STOPPED.name, JourneyStatus.LONG_STOP.name, JourneyStatus.POSSIBLE_STOP.name,
            JourneyStatus.ARRIVED.name -> cadence.stationaryS * 1000
            else -> cadence.movingS * 1000
        }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /** How often the story in the live state is retold. */
    private val STORY_EVERY_MS = 5 * 60_000L

    /** How long a silence's credit waits for the route before the guess stands. */
    private val GAP_REFINE_MS = 15 * 60_000L

    /** A repeat of the same quick entry or the same change of mode within this is a double tap. */
    private val SAME_NOTE_MS = 2 * 60_000L
    private val SAME_SWITCH_MS = 60_000L

    /** What a change of stage away from any saved place is called until the geocoder names it. */
    private val EN_ROUTE = StageRepair.EN_ROUTE

    private val STATIONARY_STATES = setOf(
        JourneyStatus.POSSIBLE_STOP.name, JourneyStatus.STOPPED.name, JourneyStatus.LONG_STOP.name,
        JourneyStatus.ARRIVED.name, JourneyStatus.OVERNIGHT.name
    )
    private val BREAK_MERGE_RADIUS_M = 500.0

    private fun terminal(s: TripStateEntity) =
        s.journey == JourneyStatus.COMPLETED.name || s.journey == JourneyStatus.EXPIRED.name

    private fun transition(s: TripStateEntity, input: JourneyInput): JourneyStatus? =
        JourneyStateMachine.next(JourneyStatus.valueOf(s.journey), input)

    private fun applyMovement(
        t: ActiveTripEntity, s0: TripStateEntity, move: StopDetector.Movement?, fix: Fix?,
        now: Long, profile: TransportProfile
    ): TripStateEntity {
        var s = s0
        // Walking about at the destination (the office lobby, the car park,
        // the gate) is not leaving it: an arrived journey reopens only when
        // the traveller has actually gone from there.
        if (s0.journey == JourneyStatus.ARRIVED.name && fix != null &&
            (move is StopDetector.Movement.DrivingStarted || move is StopDetector.Movement.StopEnded) &&
            Geo.haversineM(fix.point, GeoPoint(t.destLat, t.destLng)) <= cfg.arrivalRadiusM * STILL_THERE_FACTOR) {
            return s
        }
        when (move) {
            is StopDetector.Movement.DrivingStarted -> {
                transition(s, JourneyInput.MOVING)?.let { s = s.copy(journey = it.name) }
                if (s.drivingSinceMs == null) s = s.copy(drivingSinceMs = now)
                appScope.launch { insertEvent(t.tripId, EventTypes.DRIVING_STARTED, EventSource.SENSOR_OBSERVED, now, fix?.point?.lat, fix?.point?.lng, emptyMap(), false) }
            }
            is StopDetector.Movement.StopStarted -> {
                transition(s, JourneyInput.STOP_CONFIRMED)?.let { s = s.copy(journey = it.name) }
                // Stopping is the answer to "take a break".
                notifier.cancelWellbeingNudge("break")
                val began = detector.stopStartedAtMs() ?: now
                s = s.copy(stopStartedAtMs = began)
                // Prompt for the break log WHILE stationary — a driver can't log
                // anything while moving, so a confirmed stop is exactly when
                // their hands are free. Public transport is different: buses and
                // trains halt on schedule constantly, so stop-triggered prompts
                // would nag; those journeys are prompted at meal windows instead.
                if (profile.stopPromptsEnabled) {
                    s = s.copy(checkpointDue = true, checkpointStopStartMs = began)
                    notifier.showBreakPrompt(true)
                }
                if (profile.stopPromptsEnabled) {
                    appScope.launch { insertEvent(t.tripId, EventTypes.STOP_STARTED, EventSource.SYSTEM_INFERRED, now, fix?.point?.lat ?: s0.lat, fix?.point?.lng ?: s0.lng, emptyMap(), false) }
                }
                onSamplingChanged?.invoke()
            }
            is StopDetector.Movement.StopEnded -> {
                // Arrived, then moved on: the journey continues and any pending
                // close (prompt, reminder, auto-close) is cancelled.
                if (s0.journey == JourneyStatus.ARRIVED.name) {
                    reopenAfterArrival(t, now, fix?.point?.lat ?: s0.lat, fix?.point?.lng ?: s0.lng, confirmed = false)
                    s = s.copy(arrivalPromptDue = false)
                }
                transition(s, JourneyInput.RESTART)?.let { s = s.copy(journey = it.name) }
                val began = s.stopStartedAtMs ?: (now - move.durationS * 1000)
                // A break already logged at this stop answers the checkpoint:
                // close it with its real duration instead of asking again.
                val loggedHere = openBreak != null
                if (loggedHere) appScope.launch { lock.withLock { closeOpenBreak(t.tripId, now) } }
                // Continuous driving is what the break coach measures, so only a
                // stop long enough to rest in (or one where a break was logged)
                // resets it. Traffic and a quick pause do not.
                val halted = s0.overnightType != null
                val meaningful = halted || loggedHere || move.durationS >= cfg.meaningfulBreakS
                if (halted) {
                    // Moved off without saying so: resumed, inferred — and worded so.
                    val place = coachPrefs.getString(haltPlaceKey(t.tripId), null)
                    val payload = buildMap<String, Any?> {
                        put("source", "GPS_INFERRED")
                        s0.overnightType?.let { put("haltType", it) }
                        s0.overnightSinceMs?.let { put("haltMinutes", (now - it) / 60_000) }
                        place?.let { put("place", it) }
                        put("text", Halts.resumedText(ownerName().orEmpty(), place, confirmed = false))
                    }
                    coachPrefs.edit().remove(haltPlaceKey(t.tripId)).remove(etaBaselineKey(t.tripId)).apply()
                    appScope.launch {
                        insertEvent(t.tripId, EventTypes.HALT_RESUMED, EventSource.SYSTEM_INFERRED, now,
                            fix?.point?.lat ?: s0.lat, fix?.point?.lng ?: s0.lng, payload, false)
                    }
                    s = s.copy(overnightType = null, overnightSinceMs = null, etaMode = EtaMode.NORMAL.name)
                    lastEtaCalcMs = 0
                }
                s = s.copy(
                    stopStartedAtMs = null,
                    drivingSinceMs = if (meaningful) now else (s.drivingSinceMs ?: now),
                    lastBreakEndAtMs = if (profile.wellbeingIsBreak && meaningful) now else s.lastBreakEndAtMs,
                    // Nobody logs a "break" after a night's halt.
                    checkpointDue = profile.stopPromptsEnabled && !loggedHere && !halted,
                    checkpointStopStartMs = if (profile.stopPromptsEnabled) began else null,
                    checkpointStopEndMs = if (profile.stopPromptsEnabled) now else null,
                    checkpointStopDurationS = if (profile.stopPromptsEnabled) move.durationS else null,
                    longStopPromptDue = false
                )
                if (profile.stopPromptsEnabled) {
                    appScope.launch { insertEvent(t.tripId, EventTypes.STOP_ENDED, EventSource.SYSTEM_INFERRED, now, fix?.point?.lat ?: s0.lat, fix?.point?.lng ?: s0.lng, mapOf("durationSeconds" to move.durationS), false) }
                }
                onSamplingChanged?.invoke()
            }
            is StopDetector.Movement.LongStop -> {
                transition(s, JourneyInput.LONG_STOP)?.let { s = s.copy(journey = it.name) }
                // Only someone in their own vehicle is asked "are you taking a
                // halt?" — a long halt on a train is a station, not a decision.
                // A long stop is never presented as a problem, and never guessed at.
                s = s.copy(longStopPromptDue = profile.stopPromptsEnabled)
                appScope.launch { insertEvent(t.tripId, EventTypes.LONG_STOP, EventSource.SYSTEM_INFERRED, now, s0.lat, s0.lng, emptyMap(), false) }
                if (profile.stopPromptsEnabled && s0.overnightType == null) {
                    notifier.showHaltQuestion()
                    appScope.launch {
                        insertEvent(t.tripId, EventTypes.HALT_SUGGESTED, EventSource.SYSTEM_INFERRED, now, s0.lat, s0.lng,
                            mapOf("kind" to "LONG_STOP", "text" to "Looks like you've stopped for a while. Are you taking a halt?"), false)
                    }
                }
                onSamplingChanged?.invoke()
            }
            is StopDetector.Movement.None -> {}
            null -> {}
        }
        return s
    }

    /**
     * Notices arrival and asks about it. Never acts on it.
     *
     * The journey stays live, the credentials stay valid and the viewers keep
     * seeing a live journey until the traveller taps "End journey".
     */
    private fun maybeArrival(t: ActiveTripEntity, s0: TripStateEntity, p: GeoPoint, now: Long): TripStateEntity {
        var s = s0
        if (s.journey == JourneyStatus.ARRIVED.name) return s
        val dest = GeoPoint(t.destLat, t.destLng)
        val dist = Geo.haversineM(p, dest)
        if (dist <= cfg.arrivalRadiusM && detector.isStationary()) {
            val began = detector.stopStartedAtMs() ?: now
            if (now - began >= cfg.arrivalConfirmS * 1000) {
                transition(s, JourneyInput.ARRIVED)?.let { s = s.copy(journey = it.name) }
                s = s.copy(arrivalPromptDue = true)
                val stamped = t.copy(arrivedAtMs = t.arrivedAtMs ?: now)
                trip = stamped
                val shouldNotify = !arrivalPromptShown
                arrivalPromptShown = true
                // The 30-minute window runs from confirmed arrival, not from
                // the first fix inside the radius, and survives a restart.
                if (coachPrefs.getLong(arrivalSinceKey(t.tripId), 0L) == 0L) {
                    coachPrefs.edit().putLong(arrivalSinceKey(t.tripId), now)
                        .putBoolean(closeReminderKey(t.tripId), false).apply()
                }
                val who = ownerName().orEmpty()
                appScope.launch {
                    insertEvent(t.tripId, EventTypes.DESTINATION_REACHED, EventSource.SYSTEM_INFERRED, now, p.lat, p.lng,
                        mapOf("source" to "GPS_INFERRED", "text" to JourneyClosure.arrivalText(who, stamped.destName)), false)
                    db.tripDao().update(stamped)
                    if (shouldNotify) {
                        notifier.showClosePrompt(stamped.destName, stronger = false)
                        insertEvent(t.tripId, EventTypes.JOURNEY_CLOSE_PROMPTED, EventSource.SYSTEM_INFERRED, now, p.lat, p.lng,
                            mapOf("reminder" to false), false)
                    }
                }
            }
        }
        return s
    }

    private fun recomputeEta(
        t: ActiveTripEntity, s0: TripStateEntity, remainingS: Long, remainingM: Double, now: Long
    ): TripStateEntity {
        val f = EtaEngine.forecast(
            cfg,
            EtaEngine.Inputs(
                nowMs = now,
                remainingTravelSeconds = remainingS,
                remainingDistanceM = remainingM,
                journey = JourneyStatus.valueOf(s0.journey),
                wellbeing = WellbeingTimes(s0.waterAtMs, s0.foodAtMs, s0.toiletAtMs, s0.restAtMs, s0.fuelAtMs),
                drivingSinceMs = s0.drivingSinceMs,
                overnightPending = s0.journey == JourneyStatus.OVERNIGHT.name,
                distanceCoveredM = s0.distanceCoveredM,
                confidenceProvider = currentRoute?.provider ?: "fallback"
            )
        )
        return s0.copy(
            etaLowMs = f.lowMs, etaHighMs = f.highMs, etaLikelyMs = f.mostLikelyMs,
            etaMode = f.mode.name, etaConfidence = f.confidence,
            etaBreakdownJson = f.breakdown?.let { EventCodec.payloadToJson(breakdownMap(it)) }
        )
    }

    private fun breakdownMap(b: com.trippulse.app.domain.EtaBreakdown): Map<String, Any?> = mapOf(
        "travelSeconds" to b.travelSeconds,
        "breakBudgetSeconds" to b.breakBudgetSeconds,
        "uncertaintySeconds" to b.uncertaintySeconds,
        "components" to b.components.map { mapOf("label" to it.label, "seconds" to it.seconds) }
    )

    /**
     * Refreshes the route in the background. Called with the journey lock held,
     * so it must never wait on the network itself: a fetch that stalls on a
     * weak mobile signal would otherwise hold the lock, and every location,
     * heartbeat and break log behind it, until the app is restarted. The
     * fetch runs on its own; the result is applied under the lock when it lands.
     */
    private fun maybeRefreshRoute(t: ActiveTripEntity, from: GeoPoint, now: Long) {
        val stale = now - routeFetchedAtMs > cfg.routeRefreshMin * 60_000
        val driving = state?.journey == JourneyStatus.DRIVING.name
        if (!stale || !driving || !activeProfile().isRoadMode || routeRefreshInFlight) return
        routeRefreshInFlight = true
        val to = legDestination(t)
        val tripId = t.tripId
        appScope.launch {
            try {
                val r = kotlinx.coroutines.withTimeoutOrNull(40_000) { routing.route(from, to) }
                if (r != null) lock.withLock {
                    if (trip?.tripId == tripId) { currentRoute = r; routeFetchedAtMs = System.currentTimeMillis() }
                }
            } finally {
                routeRefreshInFlight = false
            }
        }
    }

    /** Where the CURRENT leg is heading — not necessarily the final stop. */
    private fun legDestination(t: ActiveTripEntity): GeoPoint =
        activeLeg()?.let { GeoPoint(it.toLat, it.toLng) } ?: GeoPoint(t.destLat, t.destLng)

    private fun remainingDistanceM(from: GeoPoint): Double {
        val t = trip ?: return 0.0
        val route = currentRoute
        // On a hybrid journey the legs still ahead are added on, so "distance
        // to go" always means to the final destination.
        val onwardM = legs.filter { it.legIndex > t.activeLegIndex }
            .sumOf { Geo.haversineM(GeoPoint(it.fromLat, it.fromLng), GeoPoint(it.toLat, it.toLng)) * cfg.roadDistanceFactor }
        val legRemaining = if (route != null && route.provider != "fallback" && route.polyline.size >= 2) {
            Geo.remainingAlongPathM(from, route.polyline)
        } else {
            Geo.haversineM(from, legDestination(t)) * cfg.roadDistanceFactor
        }
        return legRemaining + onwardM
    }

    private fun remainingTravelSeconds(remainingM: Double): Long {
        val route = currentRoute
        return if (route != null && route.distanceM > 0) {
            // scale the route duration by the fraction of distance remaining
            val frac = (remainingM / route.distanceM).coerceIn(0.0, 1.0)
            (route.durationS * frac).toLong()
        } else {
            val speedMps = cfg.fallbackAvgSpeedKmh / 3.6
            if (speedMps > 0) (remainingM / speedMps).toLong() else 0
        }
    }

    /**
     * How much of the planned route a silence could at most account for:
     * the route's length less what is already covered and what is still
     * ahead from [here]. Null when there is no plan to measure against.
     */
    private fun gapRoomM(here: GeoPoint, coveredM: Double): Double? {
        val t = trip ?: return null
        val route = currentRoute?.takeIf { it.provider != "fallback" && it.polyline.size >= 2 } ?: return null
        val planned = t.totalRouteDistanceM.takeIf { it > 0 } ?: return null
        return (planned - coveredM - Geo.remainingAlongPathM(here, route.polyline)).coerceAtLeast(0.0)
    }

    /**
     * Once the route is known again after a silence, the road between where
     * the phone fell silent and where it came back is what the plan had left
     * then, less what it has left now and what was driven since. That
     * replaces the straight-line guess, within the bounds a road can have.
     */
    private fun refineGapCredit(covered: Double, remainingM: Double, now: Long): Double {
        val g = gapCredit ?: return covered
        if (now - g.atMs > GAP_REFINE_MS) { gapCredit = null; return covered }
        val route = currentRoute ?: return covered
        if (route.provider == "fallback" || routeFetchedAtMs < g.atMs) return covered
        gapCredit = null
        val road = g.remainingBeforeM - remainingM - (covered - g.coveredAfterM)
        val better = road.coerceIn(g.lineM, g.lineM * cfg.roadDistanceFactor)
        return covered + (better - g.creditedM)
    }

    /**
     * Re-reads the distance a journey covered from its (possibly just
     * restored) record and keeps the larger figure. Returns what the journey
     * is now known by. Safe on a finished journey; pushed when live.
     */
    suspend fun reconcileDistance(tripId: String): Double = lock.withLock {
        val s = db.stateDao().byId(tripId) ?: return@withLock 0.0
        val samples = db.locationDao().allForTrip(tripId)
        val m = coveredDistanceM(s.distanceCoveredM, samples, cfg, db.legDao().forTrip(tripId), TransitData.load(appContext))
        if (m - s.distanceCoveredM < 50.0) return@withLock s.distanceCoveredM
        val fixed = s.copy(distanceCoveredM = m, progressPct = progress(m, s.distanceRemainingM))
        db.stateDao().upsert(fixed)
        val t = trip
        if (t != null && t.tripId == tripId) {
            state = fixed
            if (t.cloudEnabled) appScope.launch { runCatching { sync.pushLiveState(t, stateMap(t, fixed), force = true) } }
        }
        m
    }

    /**
     * After a restart the first fix must be measured from where the record
     * stopped, not thrown away; and a journey that lost a stretch before this
     * rule existed gets it back once, from the samples (see DistanceLedger).
     */
    private suspend fun pickUpDistance(t: ActiveTripEntity, s: TripStateEntity): TripStateEntity {
        val samples = db.locationDao().allForTrip(t.tripId)
        val last = samples.lastOrNull() ?: return s
        if (lastDistancePoint == null) { lastDistancePoint = GeoPoint(last.lat, last.lng); lastFixMs = last.tMs }
        val prefs = appContext.getSharedPreferences("tp_distance_ledger", Context.MODE_PRIVATE)
        if (prefs.getBoolean(t.tripId, false)) return s
        prefs.edit().putBoolean(t.tripId, true).apply()
        val m = coveredDistanceM(s.distanceCoveredM, samples, cfg, db.legDao().forTrip(t.tripId), TransitData.load(appContext))
        if (m - s.distanceCoveredM < 50.0) return s
        val fixed = s.copy(distanceCoveredM = m, progressPct = progress(m, s.distanceRemainingM))
        db.stateDao().upsert(fixed)
        return fixed
    }

    private fun progress(coveredM: Double, remainingM: Double): Double {
        val total = coveredM + remainingM
        if (total <= 0) return 0.0
        return (coveredM / total).coerceIn(0.0, 1.0)
    }

    private fun derivedSpeedKmh(fix: Fix): Double {
        val prev = lastPersistPoint ?: return 0.0
        val dtMs = fix.timeMs - lastPersistMs
        if (dtMs <= 0) return 0.0
        return (Geo.haversineM(prev, fix.point) / (dtMs / 1000.0)) * 3.6
    }

    private suspend fun reportWellbeing(
        t: ActiveTripEntity, type: String, now: Long, lat: Double?, lng: Double?,
        payload: Map<String, Any?>
    ) {
        insertEvent(t.tripId, type, EventSource.DRIVER_CONFIRMATION, now, lat, lng, payload, false)
    }

    /**
     * Closes the journey locally: tracking stops, the record is durable, and
     * it waits for the traveller's review. The live state followers see says
     * "wrapping up", never "ended"; the closing note is held until approval.
     */
    private suspend fun closeInternal(
        t: ActiveTripEntity, s0: TripStateEntity, recordedAt: Long, closingNote: String?,
        endAtMs: Long?, auto: Boolean
    ) {
        val now = endAtMs ?: recordedAt
        var s = s0
        val previous = s0.journey
        transition(s, JourneyInput.COMPLETE)?.let { s = s.copy(journey = it.name) }

        insertEvent(
            t.tripId, if (auto) EventTypes.JOURNEY_AUTO_CLOSED else EventTypes.JOURNEY_CLOSED,
            if (auto) EventSource.SYSTEM_INFERRED else EventSource.DRIVER_MANUAL, now, s.lat, s.lng,
            buildMap<String, Any?> {
                put("source", if (auto) "SYSTEM_AUTO_CLOSE" else "USER_CONFIRMED")
                if (auto) {
                    put("reason", "DESTINATION_REACHED_NO_RESPONSE")
                    put("text", JourneyClosure.AUTO_CLOSED_TEXT)
                }
                put("recordedAtMs", recordedAt)
            }, false
        )
        db.legDao().markCompleted(t.tripId, t.activeLegIndex, now)
        activeLeg()?.let { fareOpportunity(t.tripId, it.mode, it.fromName, t.destName, now) }

        saveClosure(
            t.tripId,
            JourneyClosure.Record(
                stage = JourneyClosure.Lifecycle.CLOSED_PENDING_REVIEW, closedAtMs = recordedAt,
                auto = auto, previousStatus = previous, closingNote = closingNote?.trim()?.ifBlank { null }
            )
        )
        coachPrefs.edit().remove(arrivalSinceKey(t.tripId)).remove(closeReminderKey(t.tripId)).apply()

        // Credentials are kept until the report is approved and shared; the
        // expiry clock starts then, so followers can read it.
        val closed = t.copy(status = "COMPLETED", completedAtMs = now, expiresAtMs = null, endedByOwner = true)
        db.tripDao().update(closed); trip = closed

        val reached = t.arrivedAtMs != null || endAtMs != null
        s = s.copy(
            etaMode = if (reached) EtaMode.ARRIVED.name else s.etaMode,
            arrivalPromptDue = false, updatedAtMs = recordedAt
        )
        db.stateDao().upsert(s); state = s

        // Delivery never holds the traveller (it once froze the app on
        // completion): the local record is already durable.
        if (closed.cloudEnabled) {
            val stateSnapshot = stateMap(closed, s)
            appScope.launch {
                runCatching {
                    sync.pushLiveState(closed, stateSnapshot, force = true)
                    sync.drain(closed)
                }
            }
        }
        notifier.cancelClosePrompt()
        notifier.showReviewPrompt(closed.destName, auto)
        onStopTrackingRequested?.invoke()
    }

    // -----------------------------------------------------------------------
    // Expenses: captured when they happen, never while driving
    // -----------------------------------------------------------------------

    private val expenseStore by lazy { ExpenseStore(appContext) }

    /** Changes whenever any journey's expense opportunities change. */
    val expenseVersion: StateFlow<Long> get() = expenseStore.version

    fun expenseOpportunities(tripId: String): List<Expenses.Opportunity> = expenseStore.all(tripId)

    private fun addOpportunity(tripId: String, category: Expenses.Category, atMs: Long, label: String) {
        val existing = expenseStore.all(tripId)
        if (Expenses.isDuplicate(existing, category, label, atMs)) return
        expenseStore.save(tripId, existing + Expenses.Opportunity(UUID.randomUUID().toString(), category, atMs, label))
    }

    /** A fare leg (cab, bus, metro, train, flight, ferry) just ended: its fare, if any. */
    private fun fareOpportunity(tripId: String, mode: String, from: String, to: String, atMs: Long) {
        val cat = Expenses.Category.fareFor(mode) ?: return
        addOpportunity(tripId, cat, atMs, "${cat.label} · $from → $to")
    }

    /**
     * Ask about one open expense when it is safe: never a driver on the move.
     * The prompt can always be skipped; skipping is not "no expense".
     */
    private fun expenseTick(t: ActiveTripEntity, s: TripStateEntity) {
        val next = expenseStore.all(t.tripId).firstOrNull { it.status == Expenses.Status.PENDING && !it.prompted } ?: return
        val role = WellbeingCoach.Role.fromKey(planFor(t).role) ?: WellbeingCoach.defaultRole(activeLeg()?.mode ?: t.transportMode)
        val moving = s.journey == JourneyStatus.DRIVING.name
        if (!Expenses.safeToAsk(driver = role == WellbeingCoach.Role.DRIVER, moving = moving)) return
        expenseStore.update(t.tripId, next.id) { it.copy(prompted = true) }
        notifier.showExpensePrompt(t.tripId, next)
    }

    /**
     * An amount for an opportunity: stored as an expense at the moment it
     * happened (not when it was typed), so the chronology stays true.
     */
    suspend fun recordExpenseAmount(tripId: String, opportunityId: String, amount: Double): Boolean = lock.withLock {
        if (amount < 0 || amount.isNaN()) return@withLock false
        val o = expenseStore.all(tripId).firstOrNull { it.id == opportunityId } ?: return@withLock false
        db.expenseDao().insert(
            ExpenseEntity(
                tripId = tripId, type = o.category.name, amount = amount, quantity = null, unit = null,
                note = "Source: entered by traveller", tMs = o.atMs, item = o.label
            )
        )
        expenseStore.update(tripId, opportunityId) { it.copy(status = Expenses.Status.RECORDED, amount = amount, prompted = true) }
        notifier.cancelExpensePrompt(opportunityId)
        true
    }

    /** "No expense": a real ₹0 the traveller stated. */
    suspend fun markNoExpense(tripId: String, opportunityId: String) = answerOpportunity(tripId, opportunityId, Expenses.Status.NO_EXPENSE)

    /** "Skip for now": still open, completed at the review. */
    suspend fun deferExpense(tripId: String, opportunityId: String) = answerOpportunity(tripId, opportunityId, Expenses.Status.DEFERRED)

    /** "Leave unknown": never shown as ₹0. */
    suspend fun leaveExpenseUnknown(tripId: String, opportunityId: String) = answerOpportunity(tripId, opportunityId, Expenses.Status.UNKNOWN)

    private suspend fun answerOpportunity(tripId: String, opportunityId: String, status: Expenses.Status) = lock.withLock {
        expenseStore.update(tripId, opportunityId) { it.copy(status = status, prompted = true) }
        notifier.cancelExpensePrompt(opportunityId)
    }

    // -----------------------------------------------------------------------
    // Review, approval and the private expense review
    // -----------------------------------------------------------------------

    private val closurePrefs by lazy {
        appContext.getSharedPreferences("tp_journey_closure", Context.MODE_PRIVATE)
    }

    fun closureRecord(tripId: String): JourneyClosure.Record? =
        JourneyClosure.Record.decode(closurePrefs.getString(tripId, null))

    private fun saveClosure(tripId: String, r: JourneyClosure.Record) {
        closurePrefs.edit().putString(tripId, r.encode()).apply()
    }

    /** The traveller opened the review: recorded once. */
    suspend fun startReview(tripId: String) = lock.withLock {
        val r = closureRecord(tripId)?.takeIf { it.stage == JourneyClosure.Lifecycle.CLOSED_PENDING_REVIEW } ?: return@withLock
        val now = System.currentTimeMillis()
        saveClosure(tripId, r.copy(stage = JourneyClosure.Lifecycle.ANALYTICS_REVIEW, reviewStartedAtMs = now))
        insertEvent(tripId, EventTypes.JOURNEY_REVIEW_STARTED, EventSource.DRIVER_MANUAL, now, null, null, emptyMap(), false)
    }

    /** ✎ The destination as it should be recorded. Only while under review. */
    suspend fun correctDestination(tripId: String, name: String): Boolean = lock.withLock {
        if (closureRecord(tripId)?.pendingReview != true) return@withLock false
        val clean = name.trim().ifBlank { null } ?: return@withLock false
        val t = db.tripDao().byId(tripId) ?: return@withLock false
        db.tripDao().update(t.copy(destName = clean))
        db.legDao().forTrip(tripId).maxByOrNull { it.legIndex }?.let { db.legDao().upsert(it.copy(toName = clean)) }
        if (trip?.tripId == tripId) trip = trip?.copy(destName = clean)
        true
    }

    /** ✎ When the journey really ended. Only while under review, and within the journey. */
    suspend fun correctEndTime(tripId: String, endAtMs: Long): Boolean = lock.withLock {
        val r = closureRecord(tripId)?.takeIf { it.pendingReview } ?: return@withLock false
        val t = db.tripDao().byId(tripId) ?: return@withLock false
        val started = t.startedAtMs ?: t.createdAtMs
        if (endAtMs !in started..r.closedAtMs) return@withLock false
        db.tripDao().update(t.copy(completedAtMs = endAtMs))
        if (trip?.tripId == tripId) trip = trip?.copy(completedAtMs = endAtMs)
        true
    }

    /**
     * "Approve & share journey". The only path to followers hearing the
     * journey ended: the verified completion (with the report's figures) is
     * published, the journey is finalized, and the followers' access runs for
     * [TripConfig.reportAccessMin] from now so they can open the report.
     */
    suspend fun approveJourney(tripId: String, safeConfirmed: Boolean): Boolean = lock.withLock {
        val r = closureRecord(tripId)?.takeIf { it.pendingReview } ?: return@withLock false
        val t = db.tripDao().byId(tripId) ?: return@withLock false
        val s = db.stateDao().byId(tripId)
        val now = System.currentTimeMillis()
        val end = t.completedAtMs ?: r.closedAtMs
        val who = ownerName()

        r.closingNote?.let {
            insertEvent(tripId, EventTypes.QUICK_NOTE, EventSource.DRIVER_MANUAL, end, s?.lat, s?.lng, mapOf("text" to it), false)
        }
        if (safeConfirmed) {
            insertEvent(tripId, EventTypes.TRAVELLER_CONFIRMED_SAFE, EventSource.DRIVER_CONFIRMATION, end, s?.lat, s?.lng,
                mapOf("source" to "USER_CONFIRMED"), false)
        }
        insertEvent(tripId, EventTypes.JOURNEY_ANALYTICS_APPROVED, EventSource.DRIVER_CONFIRMATION, now, null, null,
            mapOf("approvedAtMs" to now, "approvedBy" to (who ?: "traveller")), false)

        val events = db.eventDao().allForTrip(tripId).map { EventCodec.toDomain(it) }
        val started = t.startedAtMs ?: t.createdAtMs
        val summary = SummaryCalculator.compute(events, s?.distanceCoveredM ?: 0.0, started, end)
        insertEvent(tripId, EventTypes.TRIP_COMPLETED, EventSource.DRIVER_MANUAL, end, s?.lat, s?.lng,
            summaryMap(summary) + mapOf(
                "text" to JourneyClosure.endedText(who.orEmpty(), t.originName, t.destName, TimeFmt.clock(end), safeConfirmed),
                "reportApproved" to true, "approvedAtMs" to now,
                "autoClosed" to r.auto, "safeConfirmed" to safeConfirmed
            ), false)
        insertEvent(tripId, EventTypes.JOURNEY_FINALIZED, EventSource.DRIVER_MANUAL, now, null, null, emptyMap(), false)

        saveClosure(tripId, r.copy(
            stage = JourneyClosure.Lifecycle.FINALIZED, approvedAtMs = now, approvedBy = who, safeConfirmed = safeConfirmed
        ))
        val expires = now + cfg.reportAccessMin * 60_000
        val finalized = t.copy(expiresAtMs = expires)
        db.tripDao().update(finalized)
        if (trip?.tripId == tripId) trip = finalized

        if (finalized.cloudEnabled) {
            val snapshot = s?.let { stateMap(finalized, it) }
            appScope.launch {
                runCatching {
                    snapshot?.let { sync.pushLiveState(finalized, it, force = true) }
                    cloud.setExpiry(finalized.accessKey, expires)
                    sync.drain(finalized)
                }
            }
            // The server holds the completion notice until these analytics
            // arrive; the verified report follows them.
            onJourneyApproved?.invoke(
                com.trippulse.app.data.export.ApprovedJourneyPublisher.Approval(
                    tripId, now, who, safeConfirmed, r.auto
                )
            )
        }
        notifier.cancelReviewPrompt()
        true
    }

    /**
     * "Confirm expenses": private to the traveller. Recorded without amounts,
     * never sent to followers; the screen then saves the expense PDF on the
     * phone.
     */
    suspend fun approveExpenses(tripId: String): Boolean = lock.withLock {
        val now = System.currentTimeMillis()
        val r = closureRecord(tripId)
        if (r != null) saveClosure(tripId, r.copy(expensesApprovedAtMs = now))
        else saveClosure(tripId, JourneyClosure.Record(
            JourneyClosure.Lifecycle.FINALIZED, now, auto = false, previousStatus = JourneyStatus.COMPLETED.name,
            expensesApprovedAtMs = now
        ))
        insertEvent(tripId, EventTypes.TRAVEL_EXPENSES_APPROVED, EventSource.DRIVER_CONFIRMATION, now, null, null,
            emptyMap(), true)
        true
    }

    private suspend fun appendSosDelivered(tripId: String) {
        db.tripDao().byId(tripId) ?: return
        val now = System.currentTimeMillis()
        insertEvent(tripId, EventTypes.SOS_DELIVERED, EventSource.SERVER_DERIVED, now, null, null, emptyMap(), false)
        // don't recurse into drain here; the normal drain loop will pick it up
    }

    // -----------------------------------------------------------------------
    // Wellbeing coach, halt planning, ETA changes and the periodic update
    // -----------------------------------------------------------------------

    /**
     * Coach state, the halt place, the ETA baseline and the last periodic
     * update — all on the phone, so coaching works offline and a restart
     * never resets a reminder ladder.
     */
    private val coachPrefs by lazy {
        appContext.getSharedPreferences("tp_wellbeing_coach", Context.MODE_PRIVATE)
    }

    private fun haltPlaceKey(tripId: String) = "$tripId|haltPlace"
    private fun etaBaselineKey(tripId: String) = "$tripId|etaBaseline"

    private fun loadCoach(tripId: String) = WellbeingCoach.decode(coachPrefs.getString(tripId, null))

    private fun saveCoach(tripId: String, states: Map<WellbeingCoach.Need, WellbeingCoach.NeedState>) {
        coachPrefs.edit().putString(tripId, WellbeingCoach.encode(states)).apply()
    }

    private fun movementOf(s: TripStateEntity): WellbeingCoach.Movement = when (s.journey) {
        JourneyStatus.DRIVING.name -> WellbeingCoach.Movement.MOVING
        JourneyStatus.OVERNIGHT.name -> WellbeingCoach.Movement.HALTED
        JourneyStatus.PAUSED.name -> WellbeingCoach.Movement.PAUSED
        else -> WellbeingCoach.Movement.STOPPED
    }

    /**
     * One pass of the coach, on the journey tick (under [lock]).
     *
     * Suggestions go to the traveller only, with one-tap answers, and are
     * kept as traveller-only records. A need still unresolved after one
     * suggestion and one reminder becomes a neutral WELLBEING_ALERT for the
     * followers. The long-haul halt suggestion, significant ETA changes and
     * the periodic update ride the same tick.
     */
    private suspend fun coachTick(t: ActiveTripEntity, s: TripStateEntity, now: Long) {
        if (t.status != "ACTIVE" || !isEditable(t)) return
        val started = t.startedAtMs ?: return
        val plan = planFor(t)
        val modeKey = activeLeg()?.mode ?: t.transportMode
        val role = WellbeingCoach.Role.fromKey(plan.role) ?: WellbeingCoach.defaultRole(modeKey)
        val movement = movementOf(s)
        val halted = movement == WellbeingCoach.Movement.HALTED

        val result = WellbeingCoach.step(
            WellbeingCoach.WellbeingContext(
                nowMs = now, localHour = TimeFmt.hourOfDay(now), localMinuteOfDay = TimeFmt.minuteOfDay(now),
                mode = modeKey, role = role, movement = movement,
                journeyStartedAtMs = started, continuousSinceMs = s.drivingSinceMs,
                waterAtMs = s.waterAtMs, foodAtMs = s.foodAtMs,
                travellerName = ownerName().orEmpty()
            ),
            loadCoach(t.tripId)
        )
        saveCoach(t.tripId, result.states)
        result.expired.forEach { notifier.cancelWellbeingNudge(it.key) }
        for (d in result.decisions) {
            val payload = mapOf(
                "need" to d.need.key, "text" to d.body, "reasons" to d.reasons,
                "confidence" to d.confidence.name, "gapMinutes" to d.gapMin
            )
            if (d.followerVisible) {
                insertEvent(
                    t.tripId, EventTypes.WELLBEING_ALERT, EventSource.SYSTEM_INFERRED, now, s.lat, s.lng,
                    payload + ("reminders" to (result.states[d.need]?.reminders ?: 1)), false
                )
            } else {
                notifier.showWellbeingNudge(d.need.key, d.title, d.body)
                val (nudge, reminder, _) = EventTypes.coachTypes(d.need.key)
                insertEvent(
                    t.tripId, if (d.reminder) reminder else nudge, EventSource.SYSTEM_INFERRED, now, s.lat, s.lng,
                    payload + ("title" to d.title), false
                )
            }
        }

        // ---- long-haul: suggest planning an overnight halt, once ---------------
        val suggestedKey = "${t.tripId}|haltPlanSuggested"
        if (HaltPlanning.shouldSuggest(
                role, modeKey, plan.plannedHalt, coachPrefs.getBoolean(suggestedKey, false), halted,
                now, s.etaLikelyMs, s.etaLikelyMs?.let { TimeFmt.hourOfDay(it) }
            )
        ) {
            coachPrefs.edit().putBoolean(suggestedKey, true).apply()
            notifier.showHaltPlanSuggestion(HaltPlanning.TEXT)
            insertEvent(
                t.tripId, EventTypes.HALT_SUGGESTED, EventSource.SYSTEM_INFERRED, now, s.lat, s.lng,
                mapOf("kind" to "LONG_HAUL", "text" to HaltPlanning.TEXT), false
            )
        }

        // ---- a materially different arrival time ---------------------------------
        val etaKey = etaBaselineKey(t.tripId)
        val eta = s.etaLikelyMs
        if (!halted && eta != null && s.etaMode == EtaMode.NORMAL.name) {
            val baseline = coachPrefs.getLong(etaKey, 0L).takeIf { it > 0 }
            if (baseline == null) {
                coachPrefs.edit().putLong(etaKey, eta).apply()
            } else if (EtaShift.significant(baseline, eta, now)) {
                val shift = WellbeingCoach.duration(kotlin.math.abs(eta - baseline) / 60_000)
                val text = "Estimated arrival has changed to around ${TimeFmt.clockWithDay(eta, now)} " +
                    "(about $shift ${if (eta > baseline) "later" else "earlier"})."
                coachPrefs.edit().putLong(etaKey, eta).apply()
                insertEvent(
                    t.tripId, EventTypes.ETA_SIGNIFICANTLY_CHANGED, EventSource.SYSTEM_INFERRED, now, s.lat, s.lng,
                    mapOf("previousEtaMs" to baseline, "etaMs" to eta, "source" to "SYSTEM_DETECTED", "text" to text),
                    false
                )
            }
        }

        // ---- the periodic update: only when something meaningful changed ---------
        val key = "${t.tripId}|update"
        val snapKey = "${t.tripId}|updateSnap"
        val last = coachPrefs.getLong(key, 0L).takeIf { it > 0 }
        if (!halted && movement != WellbeingCoach.Movement.PAUSED && JourneyUpdates.due(last, started, now)) {
            val snap = JourneyUpdates.Snapshot(
                coveredM = s.distanceCoveredM, etaMs = s.etaLikelyMs,
                moving = movement == WellbeingCoach.Movement.MOVING,
                waterAtMs = s.waterAtMs, foodAtMs = s.foodAtMs, breakAtMs = s.lastBreakEndAtMs
            )
            val changes = JourneyUpdates.changes(JourneyUpdates.Snapshot.decode(coachPrefs.getString(snapKey, null)), snap)
            // Considered either way, so an unchanged journey is looked at again
            // an interval later rather than on every tick.
            coachPrefs.edit().putLong(key, now).apply()
            if (changes.isNotEmpty()) {
                val rules = WellbeingCoach.rulesFor(modeKey, role)
                val measures = measures()
                val text = JourneyUpdates.text(
                    JourneyUpdates.Facts(
                        nowMs = now, startedAtMs = started, moving = snap.moving,
                        driving = rules?.breakKind == WellbeingCoach.BreakKind.DRIVING,
                        riding = rules?.breakKind == WellbeingCoach.BreakKind.RIDING,
                        distanceLeft = s.distanceRemainingM.takeIf { it > 0 }?.let { measures.distance(it, modeKey) },
                        etaClock = s.etaLikelyMs?.let { TimeFmt.clockWithDay(it, now) },
                        waterAtMs = s.waterAtMs, foodAtMs = s.foodAtMs, breakAtMs = s.lastBreakEndAtMs
                    )
                )
                coachPrefs.edit().putString(snapKey, snap.encode()).apply()
                insertEvent(
                    t.tripId, EventTypes.JOURNEY_UPDATE, EventSource.SYSTEM_INFERRED, now, s.lat, s.lng,
                    mapOf("text" to text, "changes" to changes), false
                )
            }
        }
    }

    /** "Had water" / "Ate something" / "Taking a break" from a nudge notification. */
    suspend fun logNeedMet(need: WellbeingCoach.Need) {
        when (need) {
            WellbeingCoach.Need.WATER -> logNourishment(Nourishment.WATER)
            WellbeingCoach.Need.FOOD -> submitCheckpoint(Checkpoint(food = true))
            WellbeingCoach.Need.BREAK -> takingABreak()
        }
    }

    /**
     * "Taking a break": the traveller means to stop. The stop itself resets
     * the break cycle; if none follows, one gentle reminder later.
     */
    private suspend fun takingABreak() = lock.withLock {
        val t = trip ?: return@withLock
        val s = state ?: return@withLock
        val now = System.currentTimeMillis()
        saveCoach(t.tripId, WellbeingCoach.acknowledge(loadCoach(t.tripId), WellbeingCoach.Need.BREAK, now))
        insertEvent(
            t.tripId, EventTypes.BREAK_ACKNOWLEDGED, EventSource.DRIVER_CONFIRMATION, now, s.lat, s.lng,
            mapOf("need" to WellbeingCoach.Need.BREAK.key, "source" to "USER_CONFIRMED"), false
        )
    }

    /** "Remind me later": one reminder after the snooze, then the usual path. */
    suspend fun snoozeNudge(need: WellbeingCoach.Need) = lock.withLock {
        val t = trip ?: return@withLock
        saveCoach(t.tripId, WellbeingCoach.snooze(loadCoach(t.tripId), need, System.currentTimeMillis()))
    }

    /**
     * Record a toll crossing parsed from a FASTag SMS onto the active journey.
     *
     * A FASTag SMS is evidence a toll was crossed, not proof of the route: it
     * only becomes a journey event when there is an active journey it plausibly
     * belongs to. Guards: the SMS vehicle (when present) must match the
     * journey's registered plate; the crossing time must not clearly predate
     * the journey; and the same crossing is never recorded twice (idempotent on
     * the crossing's dedup key). A qualifying annual-pass crossing then
     * decrements the user's pass balance, if they configured one.
     */
    suspend fun recordTollCrossing(crossing: TollCrossing) = lock.withLock {
        val s = state ?: return@withLock
        recordTollLocked(crossing, EventSource.FASTAG_SMS, s.lat, s.lng)
    }

    /**
     * A toll plaza on the vehicle's path, from location (no SMS). Car and bike
     * journeys only, and only while the traveller keeps toll counting on.
     * Called with the lock held.
     */
    private suspend fun maybeTollFromLocation(t: ActiveTripEntity, fix: Fix, now: Long, profile: TransportProfile) {
        // Plazas can only be noticed where Koode knows where they are (see TollSystem).
        if (!profile.isPrivateVehicle || !settings.current.tollDetectionEnabled || !market().canDetectTolls) { lastTollFix = null; return }
        if (!com.trippulse.app.domain.TollPlazas.usable(fix.accuracyM.toDouble())) return
        val prev = lastTollFix
        lastTollFix = fix
        if (prev == null || fix.timeMs - prev.timeMs > 5 * 60_000L) return
        val index = tollPlazas()
        if (index.size == 0) return
        val plaza = com.trippulse.app.domain.TollPlazas.crossed(
            index, prev.point.lat, prev.point.lng, fix.point.lat, fix.point.lng, now, recentTolls(t)
        ) ?: return
        val plate = activeRegistrationPlate()
        recordTollLocked(
            TollCrossing(plaza.name, plate, journeyPassType(plate), now, issuer = null),
            EventSource.SYSTEM_INFERRED, plaza.lat, plaza.lng, osmId = plaza.id
        )
    }

    /**
     * The plazas on the road the car took while the phone was silent. The
     * road is asked of the router between where the record stopped and where
     * it resumed; every known plaza on it is a crossing, timed by its share
     * of the road. Each is marked as worked out, not seen. Called with the
     * lock held; the router is asked outside it.
     */
    private fun inferTollsAcrossSilence(t: ActiveTripEntity, from: GeoPoint, to: GeoPoint, fromMs: Long, toMs: Long, profile: TransportProfile) {
        if (!profile.isPrivateVehicle || !settings.current.tollDetectionEnabled || !market().canDetectTolls) return
        if (Geo.haversineM(from, to) < DistanceLedger.GAP_MIN_M) return
        val tripId = t.tripId
        appScope.launch {
            val plan = runCatching { kotlinx.coroutines.withTimeoutOrNull(30_000) { routing.route(from, to) } }.getOrNull() ?: return@launch
            if (plan.provider == "fallback" || plan.polyline.size < 2) return@launch
            lock.withLock {
                val cur = trip ?: return@withLock
                if (cur.tripId != tripId) return@withLock
                val recorded = recentTolls(cur).filter { it.atMs in (fromMs - 60 * 60_000L)..(toMs + 60 * 60_000L) }
                val found = com.trippulse.app.domain.TollPlazas.alongPath(tollPlazas(), plan.polyline, fromMs, toMs, recorded)
                val plate = activeRegistrationPlate()
                for (h in found) {
                    recordTollLocked(
                        TollCrossing(h.plaza.name, plate, journeyPassType(plate), h.atMs, issuer = null),
                        EventSource.SYSTEM_INFERRED, h.plaza.lat, h.plaza.lng, osmId = h.plaza.id, inferred = true
                    )
                }
            }
        }
    }

    /** "Toll crossed", tapped by the traveller. Called with the lock held. */
    private suspend fun recordManualTollLocked() {
        val t = trip ?: return
        val s = state ?: return
        val now = System.currentTimeMillis()
        // A second tap, or a plaza Koode already counted a moment ago: once.
        if (recentTolls(t).any { now - it.atMs in 0..3 * 60_000L }) return
        val plate = activeRegistrationPlate()
        recordTollLocked(
            TollCrossing(null, plate, journeyPassType(plate), now, issuer = null),
            EventSource.DRIVER_MANUAL, s.lat, s.lng
        )
    }

    /** Annual pass when the journey's own vehicle is on one; otherwise unknown. */
    private suspend fun journeyPassType(plate: String?): com.trippulse.app.domain.fastag.TollPassType {
        plate ?: return com.trippulse.app.domain.fastag.TollPassType.UNKNOWN
        val v = runCatching { db.vehicleDao().all() }.getOrNull().orEmpty().firstOrNull {
            com.trippulse.app.domain.fastag.TollSmsParser.normalizePlate(it.registration) == plate
        } ?: return com.trippulse.app.domain.fastag.TollPassType.UNKNOWN
        return if (com.trippulse.app.domain.fastag.FastagMode.fromKey(v.fastagMode) ==
            com.trippulse.app.domain.fastag.FastagMode.ANNUAL_PASS)
            com.trippulse.app.domain.fastag.TollPassType.ANNUAL_PASS
        else com.trippulse.app.domain.fastag.TollPassType.UNKNOWN
    }

    /** Where and when tolls were recorded on [t], loaded once per journey. */
    private suspend fun recentTolls(t: ActiveTripEntity): MutableList<com.trippulse.app.domain.TollPlazas.Recent> {
        tollRecent?.takeIf { tollRecentTrip == t.tripId }?.let { return it }
        val list = runCatching { db.eventDao().allForTrip(t.tripId) }.getOrDefault(emptyList())
            .filter { it.type == EventTypes.TOLL_CROSSED }
            .mapNotNull { e ->
                val p = EventCodec.payloadFromJson(e.payloadJson)
                val lat = (p["plazaLat"] as? Number)?.toDouble() ?: e.lat ?: return@mapNotNull null
                val lng = (p["plazaLng"] as? Number)?.toDouble() ?: e.lng ?: return@mapNotNull null
                com.trippulse.app.domain.TollPlazas.Recent(lat, lng, e.eventTimeMs)
            }
            .toMutableList()
        tollRecent = list
        tollRecentTrip = t.tripId
        return list
    }

    /**
     * Records one toll crossing, whatever told us about it: a FASTag SMS
     * (older builds), the vehicle's path through a known plaza, or the
     * traveller's tap. Called with the lock held.
     */
    private suspend fun recordTollLocked(
        crossing: TollCrossing, source: EventSource, atLat: Double?, atLng: Double?, osmId: String? = null,
        /** Worked out from the road while the phone was silent, not seen from its position. */
        inferred: Boolean = false
    ) {
        val t = trip ?: return
        val s = state ?: return
        if (terminal(s)) return

        // Vehicle match: a plate in the SMS that isn't this journey's is another
        // vehicle's toll and must not attach here. No plate → accept.
        val tripPlate = activeRegistrationPlate()
        val smsPlate = crossing.vehicle?.let { com.trippulse.app.domain.fastag.TollSmsParser.normalizePlate(it) }
        if (smsPlate != null && tripPlate != null && smsPlate != tripPlate) return

        // Temporal sanity: ignore a crossing clearly before this journey began.
        val started = t.startedAtMs ?: t.createdAtMs
        if (crossing.crossedAtMs < started - 15 * 60_000L) return

        // Idempotency: never record the same crossing twice.
        val key = crossing.dedupKey()
        val tolls = runCatching {
            db.eventDao().allForTrip(t.tripId).filter { it.type == EventTypes.TOLL_CROSSED }
        }.getOrDefault(emptyList())
        if (tolls.any { it.payloadJson.contains(key) }) return
        // Toll count and any pass balance are separate facts: the count is
        // always known, a balance only if the traveller configured one.
        val count = tolls.size + 1

        // Annual pass: a crossing consumed, never a price. Otherwise a known
        // debit is a paid toll; an unknown one is asked about later.
        val passCovered = applyCrossingToVehicle(crossing)
        val payload = buildMap<String, Any?> {
            crossing.plaza?.let { put("plaza", it) }
            // The plate is what an SMS named; the journey's own vehicle needs no mention.
            if (source == EventSource.FASTAG_SMS) crossing.vehicle?.let { put("vehicle", it) }
            put("passType", crossing.passType.name)
            put("passCovered", passCovered)
            put("source", source.name)
            put("dedupKey", key)
            if (atLat != null && atLng != null && source != EventSource.FASTAG_SMS) {
                put("plazaLat", atLat); put("plazaLng", atLng)
            }
            osmId?.let { put("osmId", it) }
            crossing.issuer?.let { put("issuer", it) }
            put("tollsOnJourney", count)
            if (inferred) { put("inferred", true); put("atMs", crossing.crossedAtMs) }
            put("text", buildString {
                append(crossing.plaza?.let { "Toll crossed at $it" } ?: "Toll crossed")
                if (inferred) append(" while the phone was out of contact · worked out from the road")
                append(" · $count ${if (count == 1) "toll" else "tolls"} recorded on this journey")
            })
        }
        // An SMS gives no coordinate: the vehicle's last known point is used,
        // never an invented one. A plaza found on the path is where it is.
        // A crossing worked out after a silence is written now, with when it
        // happened in the payload, so followers polling for news receive it.
        insertEvent(
            t.tripId, EventTypes.TOLL_CROSSED, source,
            if (inferred) System.currentTimeMillis() else crossing.crossedAtMs, atLat ?: s.lat, atLng ?: s.lng, payload, false
        )
        if (atLat != null && atLng != null) {
            recentTolls(t).add(com.trippulse.app.domain.TollPlazas.Recent(atLat, atLng, crossing.crossedAtMs))
        }
        val tollLabel = "Toll" + crossing.plaza?.let { " · $it" }.orEmpty()
        when {
            passCovered -> Unit
            crossing.amount != null -> db.expenseDao().insert(
                ExpenseEntity(
                    tripId = t.tripId, type = Expenses.Category.TOLL.name, amount = crossing.amount,
                    quantity = null, unit = null, note = "Source: FASTag SMS", tMs = crossing.crossedAtMs,
                    item = tollLabel
                )
            )
            else -> addOpportunity(t.tripId, Expenses.Category.TOLL, crossing.crossedAtMs, tollLabel)
        }
    }

    /**
     * Count a recorded crossing off the matching vehicle's FASTag balance.
     *
     * The vehicle is found by plate: the SMS's normalised registration against
     * the registry the traveller keeps in their profile. Only that vehicle is
     * touched, and only in its own currency of balance — a crossing off an
     * annual pass, or the debited rupees off a prepaid amount. No plate in the
     * SMS, no registered match, or no balance configured → nothing changes.
     * FASTag details are entirely optional, so this is a no-op for anyone who
     * never filled them in.
     */
    /** Applies a crossing to the matching vehicle's balance; true when an annual pass covered it. */
    private suspend fun applyCrossingToVehicle(crossing: TollCrossing): Boolean {
        val smsPlate = crossing.vehicle
            ?.let { com.trippulse.app.domain.fastag.TollSmsParser.normalizePlate(it) }
            ?.takeIf { it.isNotBlank() } ?: return false
        val vehicles = runCatching { db.vehicleDao().all() }.getOrNull().orEmpty()
        val match = vehicles.firstOrNull {
            it.registration.isNotBlank() &&
                com.trippulse.app.domain.fastag.TollSmsParser.normalizePlate(it.registration) == smsPlate
        } ?: return false

        val now = System.currentTimeMillis()
        val updated = when (com.trippulse.app.domain.fastag.FastagMode.fromKey(match.fastagMode)) {
            com.trippulse.app.domain.fastag.FastagMode.ANNUAL_PASS -> {
                if (!com.trippulse.app.domain.fastag.PassLedger.qualifies(crossing.passType)) return false
                val left = match.passCrossingsLeft ?: return true
                match.copy(passCrossingsLeft = (left - 1).coerceAtLeast(0), updatedAtMs = now)
            }
            com.trippulse.app.domain.fastag.FastagMode.AMOUNT -> {
                val left = match.amountLeft ?: return false
                val debited = crossing.amount ?: return false
                match.copy(
                    amountLeft = com.trippulse.app.domain.fastag.PassLedger.amountAfter(left, debited),
                    updatedAtMs = now
                )
            }
            com.trippulse.app.domain.fastag.FastagMode.NONE -> return false
        }
        runCatching { db.vehicleDao().upsert(updated) }
        return com.trippulse.app.domain.fastag.FastagMode.fromKey(match.fastagMode) ==
            com.trippulse.app.domain.fastag.FastagMode.ANNUAL_PASS
    }

    /** The active journey's registered plate, normalised, or null. */
    private fun activeRegistrationPlate(): String? =
        LegDetails.fromJson(activeLeg()?.detailsJson)[DetailKeys.REGISTRATION]
            ?.takeIf { it.isNotBlank() }
            ?.let { com.trippulse.app.domain.fastag.TollSmsParser.normalizePlate(it) }

    /** Inserts an event into the durable log and nudges the sync engine. */
    private suspend fun insertEvent(
        tripId: String, type: String, source: EventSource, eventTimeMs: Long,
        lat: Double?, lng: Double?, payload: Map<String, Any?>, sensitive: Boolean
    ) {
        val e = EventCodec.toEntity(
            TripEvent(UUID.randomUUID().toString(), tripId, type, eventTimeMs, lat, lng, null, source, payload),
            System.currentTimeMillis(), sensitive
        )
        db.eventDao().insert(e)
        val t = trip
        if (t != null && t.cloudEnabled && connectivityNow() != Connectivity.OFFLINE) {
            appScope.launch { sync.drain(t) }
        }
    }

    private suspend fun persistAndPush(
        t: ActiveTripEntity, s: TripStateEntity, force: Boolean = false, heartbeat: Boolean = false
    ) {
        db.stateDao().upsert(s)
        if (t.cloudEnabled) {
            appScope.launch {
                sync.pushLiveState(t, stateMap(t, s), force = force)
                if (!heartbeat) sync.drain(t)
            }
        }
    }

    private fun connectivityNow(): Connectivity =
        if (connectivity.online.value) Connectivity.ONLINE else Connectivity.OFFLINE

    private fun freshState(t: ActiveTripEntity, now: Long): TripStateEntity = TripStateEntity(
        tripId = t.tripId, journey = JourneyStatus.READY.name, connectivity = connectivityNow().name,
        lat = t.originLat, lng = t.originLng, accuracyM = null, speedKmh = 0.0, bearing = null,
        lastLocationAtMs = null, batteryPct = null, distanceCoveredM = 0.0,
        distanceRemainingM = t.totalRouteDistanceM, progressPct = 0.0,
        etaLowMs = null, etaHighMs = null, etaLikelyMs = null, etaMode = EtaMode.UNKNOWN.name,
        etaBreakdownJson = null, etaConfidence = null, drivingSinceMs = null, stopStartedAtMs = null,
        lastBreakEndAtMs = null, waterAtMs = null, foodAtMs = null, toiletAtMs = null, restAtMs = null,
        fuelAtMs = null, sosActive = false, sosAtMs = null, overnightType = null, overnightSinceMs = null,
        checkpointDue = false, checkpointStopStartMs = null,
        checkpointStopEndMs = null, checkpointStopDurationS = null, longStopPromptDue = false,
        possibleIncidentDue = false, updatedAtMs = now,
        arrivalPromptDue = false, legIndex = t.activeLegIndex
    )

    // ---- cloud maps ----

    /** The traveller's display name ("Prashobh's Journey" on family screens). */
    private fun ownerName(): String? =
        appContext.getSharedPreferences("koode_profile", Context.MODE_PRIVATE)
            .getString("name", null)?.ifBlank { null }

    private fun metaMap(t: ActiveTripEntity): Map<String, Any?> = mapOf(
        "ownerName" to ownerName(),
        "transportMode" to (activeLeg()?.mode ?: t.transportMode),
        "tripId" to t.tripId,
        "origin" to t.originName, "destination" to t.destName,
        "originLat" to t.originLat, "originLng" to t.originLng,
        "destLat" to t.destLat, "destLng" to t.destLng,
        "createdAt" to t.createdAtMs, "startedAt" to t.startedAtMs,
        "plannedDeparture" to t.plannedDepartureMs,
        // Until the traveller ends the journey the capability must outlive any
        // plausible journey length, so a follower is never locked out of a
        // journey that is simply taking longer than expected.
        "expiresAt" to (t.expiresAtMs ?: (t.createdAtMs + LIVE_CAPABILITY_MS)),
        "totalRouteDistanceM" to t.totalRouteDistanceM,
        // The traveller's own road units, so the people following see what they see.
        "units" to measures().units.key,
        "legCount" to legs.size,
        "activeLeg" to t.activeLegIndex,
        "legs" to legs.map {
            mapOf(
                "index" to it.legIndex, "mode" to it.mode,
                "from" to it.fromName, "to" to it.toName,
                "fromLat" to it.fromLat, "fromLng" to it.fromLng,
                "toLat" to it.toLat, "toLng" to it.toLng,
                "startedAt" to it.startedAtMs, "completedAt" to it.completedAtMs
            )
        },
        // The dossier travels with the journey so the family holds it even if
        // the phone never comes back. It carries no location, so it is meta
        // (rarely re-read) rather than live state (polled constantly).
        "device" to DeviceDossier.fromJson(t.deviceJson)
    )

    private fun stateMap(t: ActiveTripEntity, s: TripStateEntity): Map<String, Any?> = buildMap {
        // Closed but not yet approved: followers see "wrapping up", never
        // "ended", until the traveller approves the journey.
        val pending = closureRecord(t.tripId)?.takeIf { it.pendingReview }
        put("status", pending?.previousStatus ?: s.journey)
        put("connectivity", s.connectivity)
        put("endedByOwner", t.endedByOwner && pending == null)
        if (pending != null) put("wrappingUp", true)
        put("legIndex", s.legIndex)
        s.lat?.let { put("lat", it) }; s.lng?.let { put("lng", it) }
        // The next two kilometres of the planned road, so a follower's map can
        // glide the vehicle round the bends between fixes instead of cutting
        // the corner. Only the road ahead, never the road behind.
        val here = s.lat?.let { la -> s.lng?.let { lo -> GeoPoint(la, lo) } }
        if (here != null && TransportCatalog.profile(t.transportMode).isRoadMode) {
            com.trippulse.app.domain.MapStages.roadAhead(_routeAhead.value, here)
                .takeIf { it.size >= 2 }
                ?.let { road -> put("roadAhead", road.map { listOf(Math.round(it.lat * 1e5) / 1e5, Math.round(it.lng * 1e5) / 1e5) }) }
        }
        s.accuracyM?.let { put("accuracy", it) }
        s.speedKmh?.let { put("speedKmh", it) }
        s.bearing?.let { put("bearing", it) }
        s.lastLocationAtMs?.let { put("lastLocationAt", it) }
        s.batteryPct?.let { put("battery", it) }
        put("distanceCoveredM", s.distanceCoveredM)
        put("distanceRemainingM", s.distanceRemainingM)
        put("progress", s.progressPct)
        s.etaLowMs?.let { put("etaLow", it) }
        s.etaHighMs?.let { put("etaHigh", it) }
        s.etaLikelyMs?.let { put("etaLikely", it) }
        put("etaMode", s.etaMode)
        s.etaConfidence?.let { put("etaConfidence", it) }
        s.etaBreakdownJson?.let { put("etaBreakdown", EventCodec.payloadFromJson(it)) }
        s.drivingSinceMs?.let { put("drivingSince", it) }
        s.lastBreakEndAtMs?.let { put("lastBreakEndAt", it) }
        s.waterAtMs?.let { put("waterAt", it) }
        s.foodAtMs?.let { put("foodAt", it) }
        s.toiletAtMs?.let { put("toiletAt", it) }
        s.restAtMs?.let { put("restAt", it) }
        s.fuelAtMs?.let { put("fuelAt", it) }
        put("sosActive", s.sosActive)
        s.sosAtMs?.let { put("sosAt", it) }
        s.overnightType?.let { put("overnightType", it) }
        s.overnightSinceMs?.let { put("overnightSince", it) }
        // Going dark: pushed so a follower can tell "switched off with charge
        // left" from "ran out of battery" without having to guess from a gap.
        t.wentDarkAtMs?.let { put("wentDarkAt", it) }
        t.darkReason?.let { put("darkReason", it) }
        t.simChangedAtMs?.let { put("simChangedAt", it) }
        storyCache?.let { put("story", it) }
        put("updatedAt", s.updatedAtMs)
    }

    private fun summaryMap(s: com.trippulse.app.domain.TripSummary): Map<String, Any?> = mapOf(
        "distanceKm" to s.distanceKm, "drivingSeconds" to s.drivingSeconds,
        "totalSeconds" to s.totalSeconds, "stops" to s.stops, "foodBreaks" to s.foodBreaks,
        "waterConfirmations" to s.waterConfirmations, "toiletBreaks" to s.toiletBreaks,
        "restBreaks" to s.restBreaks, "fuelStops" to s.fuelStops,
        "teaCoffee" to s.teaCoffee, "snacks" to s.snacks,
        "longestLegSeconds" to s.longestLegSeconds, "longestBreakSeconds" to s.longestBreakSeconds,
        "days" to s.days
    )
}

/**
 * How long a live journey's capability stays valid before the traveller ends
 * it. Seven days is far longer than any journey the app plans for — which is
 * the point: running out of capability mid-journey would look, to everyone
 * watching, exactly like the thing this app exists to prevent.
 */
private const val LIVE_CAPABILITY_MS = 7L * 24 * 3600 * 1000
