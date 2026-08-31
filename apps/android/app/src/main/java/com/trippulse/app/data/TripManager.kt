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
import com.trippulse.app.data.local.TripLegEntity
import com.trippulse.app.data.local.TripPulseDb
import com.trippulse.app.data.local.TripStateEntity
import com.trippulse.app.data.remote.TripCloud
import com.trippulse.app.data.routing.RoutingProvider
import com.trippulse.app.data.sync.ConnectivityObserver
import com.trippulse.app.data.sync.SyncEngine
import com.trippulse.app.domain.Connectivity
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
import com.trippulse.app.domain.RouteDeviationDetector
import com.trippulse.app.domain.RoutePlan
import com.trippulse.app.domain.StopDetector
import com.trippulse.app.domain.SummaryCalculator
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.Darkness
import com.trippulse.app.domain.DarkReason
import com.trippulse.app.core.DeviceIdentity
import com.trippulse.app.domain.TravelDetails
import com.trippulse.app.domain.DetailKeys
import com.trippulse.app.domain.LegDetails
import com.trippulse.app.domain.TransportProfile
import com.trippulse.app.domain.TripConfig
import com.trippulse.app.domain.TripEvent
import com.trippulse.app.domain.WellbeingTimes
import com.trippulse.app.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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
 *  2. **The mode of transport decides the rules.** Break prompts, deviation
 *     alerts, refuelling questions and sampling cadence all come from the
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
    private val cfg: TripConfig = TripConfig.DEFAULT
) {
    /** Set by the app/service: asked to stop the foreground service. */
    var onStopTrackingRequested: (() -> Unit)? = null
    /** Set by the service: asked to (re)apply the sampling interval. */
    var onSamplingChanged: (() -> Unit)? = null

    private val lock = Mutex()

    private var trip: ActiveTripEntity? = null
    private var state: TripStateEntity? = null
    private var legs: List<TripLegEntity> = emptyList()

    private var detector = StopDetector(cfg)
    private var deviation = RouteDeviationDetector(cfg)

    private var currentRoute: RoutePlan? = null
    private var routeFetchedAtMs: Long = 0
    private var lastPersistMs: Long = 0
    private var lastPersistPoint: GeoPoint? = null
    private var lastDistancePoint: GeoPoint? = null
    private var lastEtaCalcMs: Long = 0
    private var batteryLowFired = false
    private var lastMealWindowPrompted: String? = null
    private var arrivalPromptShown = false
    private var arrivalReminders = 0
    /**
     * Set inside the lock, acted on outside it. recordBackOnline() takes the
     * same mutex, so calling it from within onTick would deadlock.
     */
    private var backOnlineDue = false
    private var lastArrivalReminderMs = 0L

    init {
        sync.onSosDelivered = { tripId -> appendSosDelivered(tripId) }
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
        // detectors restart clean; persisted journey state is authoritative
        detector = StopDetector(cfg)
        deviation = RouteDeviationDetector(cfg)
        t
    }

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
        val passcode: String
    ) {
        val first: NewLeg get() = legs.first()
        val last: NewLeg get() = legs.last()
    }

    companion object {
        /** A saved place this close is a better name for a point than a road is. */
        private const val NEAR_PLACE_M = 500.0

        /** When to nudge after arrival, measured from arrival, widening each time. */
        private val ARRIVAL_REMINDER_DELAYS_MS = longArrayOf(
            15 * 60_000L, 45 * 60_000L, 120 * 60_000L
        )

        /** Never two nudges closer together than this, whatever the schedule says. */
        private const val MIN_REMINDER_GAP_MS = 10 * 60_000L

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
    suspend fun startTrip(tripId: String) = lock.withLock {
        val t = db.tripDao().byId(tripId) ?: return@withLock
        val now = System.currentTimeMillis()
        val started = t.copy(status = "ACTIVE", startedAtMs = now)
        db.tripDao().update(started)
        trip = started
        legs = db.legDao().forTrip(tripId)

        val s = (state ?: db.stateDao().byId(tripId) ?: freshState(started, now)).copy(
            journey = JourneyStatus.READY.name,
            drivingSinceMs = now,
            connectivity = connectivityNow().name,
            legIndex = started.activeLegIndex,
            updatedAtMs = now
        )
        db.stateDao().upsert(s); state = s

        insertEvent(tripId, EventTypes.TRIP_STARTED, EventSource.DRIVER_MANUAL, now, null, null, emptyMap(), false)
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
    ): SwitchResult = lock.withLock {
        val t = editableTrip() ?: return@withLock SwitchResult.NotEditable
        val s0 = state ?: return@withLock SwitchResult.NotEditable

        // Without a fix there is no honest place to end the current stage, and
        // inventing one would put a line on the map that nobody travelled.
        val lat = s0.lat
        val lng = s0.lng
        if (lat == null || lng == null) return@withLock SwitchResult.NoLocationYet

        val current = activeLeg()
        // A car journey has no natural stages -- you drive the whole way -- so
        // the only honest reason to be switching out of one is that the
        // vehicle stopped being an option.
        if (current != null && TransportCatalog.isPrivate(current.mode) && !breakdown) {
            return@withLock SwitchResult.PrivateVehicleNeedsBreakdown
        }
        if (!TravelDetails.isComplete(newMode, details)) {
            return@withLock SwitchResult.MissingDetails(
                TravelDetails.missingRequired(newMode, details).map { it.label }
            )
        }

        val now = System.currentTimeMillis()
        val hereName = nameForPoint(GeoPoint(lat, lng))

        current?.let { db.legDao().markCompleted(t.tripId, it.legIndex, now) }

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
        val updated = t.copy(
            activeLegIndex = nextIndex,
            transportMode = newMode,
            arrivedAtMs = null
        )
        db.tripDao().update(updated); trip = updated
        arrivalPromptShown = false

        val profile = TransportCatalog.profile(newMode)
        val vehicle = TravelDetails.summary(newMode, details)
        insertEvent(
            t.tripId, EventTypes.LEG_STARTED, EventSource.DRIVER_MANUAL, now, lat, lng,
            mapOf(
                "legIndex" to nextIndex, "mode" to newMode,
                "vehicle" to vehicle, "breakdown" to breakdown,
                "text" to buildString {
                    if (breakdown) append("Vehicle trouble — continuing ")
                    else append("Continuing ")
                    append(profile.travellingSuffix)
                    if (vehicle.isNotBlank()) append(" ($vehicle)")
                }
            ), false
        )

        var s = s0.copy(legIndex = nextIndex, arrivalPromptDue = false, updatedAtMs = now)
        persistAndPush(updated, s, force = true)
        state = s
        if (updated.cloudEnabled) appScope.launch { sync.writeMetaUpdate(updated, metaMap(updated)) }
        onSamplingChanged?.invoke()
        SwitchResult.Ok
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
    private suspend fun nameForPoint(p: GeoPoint): String {
        val near = runCatching { db.savedPlaceDao().all() }.getOrNull().orEmpty()
            .minByOrNull { Geo.haversineM(p, GeoPoint(it.lat, it.lng)) }
        if (near != null && Geo.haversineM(p, GeoPoint(near.lat, near.lng)) <= NEAR_PLACE_M) {
            return near.name
        }
        return "En route"
    }

    /** Why a mid-journey mode change did or did not happen. */
    sealed interface SwitchResult {
        data object Ok : SwitchResult
        /** The journey is finished; nothing about it can change any more. */
        data object NotEditable : SwitchResult
        /** No fix yet, so there is no honest point to switch at. */
        data object NoLocationYet : SwitchResult
        /** Leaving a private vehicle mid-journey needs a reason. */
        data object PrivateVehicleNeedsBreakdown : SwitchResult
        data class MissingDetails(val labels: List<String>) : SwitchResult
    }

    /**
     * Corrects the vehicle details of a stage that is still running.
     *
     * Only the details -- never the destination, never the mode. Where the
     * journey is going is fixed the moment it starts, because everyone
     * following was told where it was going, and quietly re-pointing it turns
     * the thing they agreed to watch into a different thing. Mode changes go
     * through [switchMode], which records them as the events they are.
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
        insertEvent(
            t.tripId, EventTypes.LEG_COMPLETED, EventSource.DRIVER_MANUAL, now, s.lat, s.lng,
            mapOf("legIndex" to t.activeLegIndex, "mode" to (activeLeg()?.mode ?: t.transportMode)), false
        )

        val moved = t.copy(activeLegIndex = nextIndex, transportMode = next.mode, fuelType = next.fuelType)
        db.tripDao().update(moved)
        db.legDao().markStarted(t.tripId, nextIndex, now)
        trip = moved
        legs = db.legDao().forTrip(t.tripId)

        // A new leg is a new road: reset the detectors and refetch the route so
        // no state leaks across a change of vehicle.
        detector = StopDetector(cfg)
        deviation = RouteDeviationDetector(cfg)
        currentRoute = routing.route(GeoPoint(next.fromLat, next.fromLng), GeoPoint(next.toLat, next.toLng))
        routeFetchedAtMs = now

        s = s.copy(
            legIndex = nextIndex, journey = JourneyStatus.READY.name,
            drivingSinceMs = now, deviationActive = false, updatedAtMs = now
        )
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

        // accumulate covered distance only while actually moving (kills jitter)
        var covered = s.distanceCoveredM
        val ldp = lastDistancePoint
        if (ldp != null && speedKmh >= cfg.restartSpeedKmh) {
            covered += Geo.haversineM(ldp, fix.point)
        }
        if (speedKmh >= cfg.restartSpeedKmh || ldp == null) lastDistancePoint = fix.point

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

        // ----- movement / stop detection -----
        val move = detector.onFix(fix)
        s = applyMovement(t, s, move, fix, now, profile)

        // ----- route refresh + remaining distance/time -----
        maybeRefreshRoute(t, fix.point, now)
        val remainingM = remainingDistanceM(fix.point)
        val remainingS = remainingTravelSeconds(remainingM)

        // ----- route deviation -----
        // Only where "off the usual route" is a real signal. A train cannot
        // leave its rails and a bus follows a fixed timetable route we do not
        // hold, so deviation there is pure noise in the family's timeline.
        val route = currentRoute
        if (profile.deviationEnabled && route != null && route.provider != "fallback" &&
            route.polyline.size >= 2 && s.journey == JourneyStatus.DRIVING.name
        ) {
            when (val d = deviation.onFix(fix.point, route.polyline, now)) {
                is RouteDeviationDetector.Signal.Deviated -> insertEvent(
                    t.tripId, EventTypes.ROUTE_DEVIATION, EventSource.SYSTEM_INFERRED, now,
                    fix.point.lat, fix.point.lng, mapOf("distanceM" to d.distanceM), false
                )
                is RouteDeviationDetector.Signal.Rejoined -> insertEvent(
                    t.tripId, EventTypes.ROUTE_REJOINED, EventSource.SYSTEM_INFERRED, now,
                    fix.point.lat, fix.point.lng, emptyMap(), false
                )
                null -> {}
            }
        }

        // ----- arrival detection -----
        s = maybeArrival(t, s, fix.point, now)

        // ----- assemble state -----
        val progress = progress(covered, remainingM)
        s = s.copy(
            lat = fix.point.lat, lng = fix.point.lng, accuracyM = fix.accuracyM.toDouble(),
            speedKmh = speedKmh, bearing = fix.bearing?.toDouble(),
            lastLocationAtMs = now, batteryPct = fix.batteryPct ?: s.batteryPct,
            distanceCoveredM = covered, distanceRemainingM = remainingM,
            progressPct = progress, deviationActive = deviation.active,
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
        onTickLocked()
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

        remindToCloseIfArrived(t, s, now)
        checkSimChange(t, now)
        // A tick is proof the app is alive, so a journey still flagged dark
        // has plainly come back -- most often after a reboot, where the
        // shutdown was recorded and BOOT_COMPLETED restarted us.
        if (t.wentDarkAtMs != null) backOnlineDue = true

        // Public transport: gentle wellbeing check-ins at meal windows only —
        // once per window, never at arbitrary intervals. Passengers aren't
        // driving, so timing courtesy matters more than stop detection.
        if (!profile.stopPromptsEnabled && !s.checkpointDue) {
            val hour = TimeFmt.hourOfDay(now)
            val window = when (hour) { 8 -> "breakfast"; 13 -> "lunch"; 16 -> "tea"; 20 -> "dinner"; else -> null }
            if (window != null && lastMealWindowPrompted != window) {
                lastMealWindowPrompted = window
                s = s.copy(checkpointDue = true, checkpointStopStartMs = now)
                notifier.showBreakPrompt(false)
            }
        }

        // battery-low (edge triggered)
        val bat = s.batteryPct
        if (bat != null && bat <= cfg.lowBatteryPct && !batteryLowFired) {
            batteryLowFired = true
            insertEvent(t.tripId, EventTypes.BATTERY_LOW, EventSource.SENSOR_OBSERVED, now, s.lat, s.lng,
                mapOf("battery" to bat), false)
        }
        if (bat != null && bat > cfg.lowBatteryPct + 5) batteryLowFired = false

        s = s.copy(connectivity = connectivityNow().name, updatedAtMs = now)
        persistAndPush(t, s, heartbeat = true)
        state = s
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
    suspend fun submitCheckpoint(c: Checkpoint) = lock.withLock {
        val t = editableTrip() ?: return@withLock
        var s = state ?: return@withLock
        if (c.isEmpty) return@withLock
        val now = System.currentTimeMillis()
        val profile = activeProfile()
        val countsAsBreak = profile.wellbeingIsBreak
        val startMs = s.checkpointStopStartMs ?: (now - (s.checkpointStopDurationS ?: 0) * 1000)
        val endMs = s.checkpointStopEndMs ?: now

        val meal = if (c.food) (c.mealKind ?: inferMeal(t.tripId, now)) else null

        insertEvent(
            t.tripId, EventTypes.BREAK_CHECKPOINT, EventSource.DRIVER_CONFIRMATION, now, s.lat, s.lng,
            mapOf(
                "water" to c.water, "food" to c.food, "toilet" to c.toilet,
                "rest" to c.rest, "fuel" to c.fuel, "charge" to c.charge,
                "tea" to c.tea, "snack" to c.snack, "other" to c.other,
                "meal" to meal?.key, "countsAsBreak" to countsAsBreak
            ), false
        )

        if (c.water) reportWellbeing(t, EventTypes.WATER_REPORTED, now, s.lat, s.lng, emptyMap())
        if (c.food) reportWellbeing(
            t, EventTypes.FOOD_REPORTED, now, s.lat, s.lng,
            mapOf("meal" to (meal ?: Nourishment.SNACK).key)
        )
        if (c.tea) reportWellbeing(t, EventTypes.TEA_COFFEE_REPORTED, now, s.lat, s.lng, emptyMap())
        if (c.snack) reportWellbeing(t, EventTypes.SNACK_REPORTED, now, s.lat, s.lng, emptyMap())
        if (c.toilet) reportWellbeing(t, EventTypes.TOILET_REPORTED, now, s.lat, s.lng, emptyMap())
        if (c.rest) reportWellbeing(t, EventTypes.REST_REPORTED, now, s.lat, s.lng, emptyMap())
        if (c.fuel) reportWellbeing(t, EventTypes.FUEL_STOP, now, s.lat, s.lng, emptyMap())
        if (c.charge) reportWellbeing(t, EventTypes.CHARGE_STOP, now, s.lat, s.lng, emptyMap())

        if (countsAsBreak) {
            db.breakDao().upsert(
                BreakRecordEntity(
                    breakId = UUID.randomUUID().toString(), tripId = t.tripId,
                    startMs = startMs, endMs = endMs,
                    durationS = ((endMs - startMs) / 1000).coerceAtLeast(0),
                    lat = s.lat, lng = s.lng,
                    water = c.water, food = c.food, toilet = c.toilet, rest = c.rest,
                    fuel = c.fuel, charge = c.charge, other = c.other,
                    confirmationSource = "DRIVER_CONFIRMATION",
                    tea = c.tea, snack = c.snack, mealKind = meal?.key
                )
            )
        }

        s = s.copy(
            waterAtMs = if (c.water) now else s.waterAtMs,
            foodAtMs = if (c.food) now else s.foodAtMs,
            toiletAtMs = if (c.toilet) now else s.toiletAtMs,
            restAtMs = if (c.rest) now else s.restAtMs,
            fuelAtMs = if (c.fuel) now else s.fuelAtMs,
            lastBreakEndAtMs = if (countsAsBreak) endMs else s.lastBreakEndAtMs,
            checkpointDue = false, checkpointStopStartMs = null,
            checkpointStopEndMs = null, checkpointStopDurationS = null,
            updatedAtMs = now
        )
        // a break changes the ETA break budget
        s = recomputeEta(t, s, remainingTravelSeconds(s.distanceRemainingM), s.distanceRemainingM, now)
        persistAndPush(t, s, force = true)
        state = s
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

    /** type: HOTEL | HOME | FAMILY | VEHICLE | CONTINUING */
    suspend fun answerOvernight(type: String) = lock.withLock {
        val t = editableTrip() ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        if (type == "CONTINUING") {
            transition(s, JourneyInput.OVERNIGHT_DECLINE)?.let { s = s.copy(journey = it.name) }
            s = s.copy(longStopPromptDue = false, updatedAtMs = now)
        } else {
            transition(s, JourneyInput.OVERNIGHT_CONFIRM)?.let { s = s.copy(journey = it.name) }
            insertEvent(t.tripId, EventTypes.OVERNIGHT_CONFIRMED, EventSource.DRIVER_CONFIRMATION, now, s.lat, s.lng,
                mapOf("type" to type), false)
            s = s.copy(overnightType = type, overnightSinceMs = now, longStopPromptDue = false,
                etaMode = EtaMode.OVERNIGHT_PENDING.name, etaLowMs = null, etaHighMs = null, etaLikelyMs = null,
                updatedAtMs = now)
            notifier.showOvernight(t.destName)
        }
        persistAndPush(t, s, force = true); state = s
        onSamplingChanged?.invoke()
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
        val now = System.currentTimeMillis()
        val sensitive = EventTypes.isSensitiveByDefault(type)
        val payload = buildMap<String, Any?> {
            if (!text.isNullOrBlank()) put("text", text)
            put("mode", activeLeg()?.mode ?: t.transportMode)
        }
        insertEvent(t.tripId, type, EventSource.DRIVER_MANUAL, now, s.lat, s.lng, payload, sensitive)
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
    private suspend fun checkSimChange(t: ActiveTripEntity, now: Long) {
        if (t.simChangedAtMs != null) return
        val current = DeviceIdentity.simFingerprint(appContext) ?: return
        val remembered = t.simFingerprint
        if (remembered == null) {
            // First sighting: record it as the baseline rather than an alarm.
            val stamped = t.copy(simFingerprint = current)
            db.tripDao().update(stamped); trip = stamped
            return
        }
        if (remembered == current) return

        val changed = t.copy(simChangedAtMs = now, simFingerprint = current)
        db.tripDao().update(changed); trip = changed
        insertEvent(
            t.tripId, EventTypes.SIM_CHANGED, EventSource.SENSOR_OBSERVED, now,
            state?.lat, state?.lng,
            mapOf("text" to "A different SIM is in this phone"), false
        )
        notifier.showJourneyAttention(changed.destName, "A different SIM is in this phone")
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
        arrivalPromptShown = false
        arrivalReminders = 0
        lastArrivalReminderMs = 0L

        val cleared = t.copy(arrivedAtMs = null)
        db.tripDao().update(cleared); trip = cleared
        persistAndPush(cleared, s); state = s
    }

    /**
     * Nudges a traveller who has arrived but not said so.
     *
     * Only they can end a journey, which is right, but it means a journey
     * whose traveller simply forgot stays live -- and everyone watching keeps
     * seeing a moving dot for someone who is already home and asleep. The
     * reminder widens rather than repeats, because the second nudge is
     * useful and the tenth is an app to be uninstalled: a quarter of an hour
     * after arrival, then three quarters, then two hours, and then it stops
     * and lets the 72-hour sweep have it.
     */
    private fun remindToCloseIfArrived(t: ActiveTripEntity, s: TripStateEntity, now: Long) {
        val arrived = t.arrivedAtMs ?: return
        if (s.journey != JourneyStatus.ARRIVED.name) return
        if (arrivalReminders >= ARRIVAL_REMINDER_DELAYS_MS.size) return

        val due = arrived + ARRIVAL_REMINDER_DELAYS_MS[arrivalReminders]
        if (now < due) return
        // Never two in the same stretch, however long the app was asleep.
        if (now - lastArrivalReminderMs < MIN_REMINDER_GAP_MS) return

        arrivalReminders++
        lastArrivalReminderMs = now
        appScope.launch { notifier.showArrivalDetected(t.destName) }
    }

    /**
     * Ends the journey. The only path to [JourneyStatus.COMPLETED].
     *
     * There is deliberately no automatic caller. A journey that looks finished
     * — parked at the destination, out of battery, out of coverage — is still
     * the traveller's to close, because everyone watching reads "ended" as
     * "they're safe and home", and the app must never say that on its own.
     */
    suspend fun completeTrip(closingNote: String? = null) = lock.withLock {
        // Idempotent: a double tap, or a screen that lingered, must not append
        // a second completion to a journey that is already closed.
        val t = editableTrip() ?: return@withLock
        val s = state ?: return@withLock
        completeInternal(t, s, System.currentTimeMillis(), closingNote)
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

    private fun terminal(s: TripStateEntity) =
        s.journey == JourneyStatus.COMPLETED.name || s.journey == JourneyStatus.EXPIRED.name

    private fun transition(s: TripStateEntity, input: JourneyInput): JourneyStatus? =
        JourneyStateMachine.next(JourneyStatus.valueOf(s.journey), input)

    private fun applyMovement(
        t: ActiveTripEntity, s0: TripStateEntity, move: StopDetector.Movement?, fix: Fix?,
        now: Long, profile: TransportProfile
    ): TripStateEntity {
        var s = s0
        when (move) {
            is StopDetector.Movement.DrivingStarted -> {
                transition(s, JourneyInput.MOVING)?.let { s = s.copy(journey = it.name) }
                if (s.drivingSinceMs == null) s = s.copy(drivingSinceMs = now)
                appScope.launch { insertEvent(t.tripId, EventTypes.DRIVING_STARTED, EventSource.SENSOR_OBSERVED, now, fix?.point?.lat, fix?.point?.lng, emptyMap(), false) }
            }
            is StopDetector.Movement.StopStarted -> {
                transition(s, JourneyInput.STOP_CONFIRMED)?.let { s = s.copy(journey = it.name) }
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
                transition(s, JourneyInput.RESTART)?.let { s = s.copy(journey = it.name) }
                val began = s.stopStartedAtMs ?: (now - move.durationS * 1000)
                s = s.copy(
                    stopStartedAtMs = null, drivingSinceMs = now,
                    lastBreakEndAtMs = if (profile.wellbeingIsBreak) now else s.lastBreakEndAtMs,
                    checkpointDue = profile.stopPromptsEnabled,
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
                // Only a driver is asked "are you stopping for the night?" — a
                // long halt on a train is a station, not a decision.
                s = s.copy(longStopPromptDue = profile.stopPromptsEnabled)
                appScope.launch { insertEvent(t.tripId, EventTypes.LONG_STOP, EventSource.SYSTEM_INFERRED, now, s0.lat, s0.lng, emptyMap(), false) }
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
                appScope.launch {
                    insertEvent(t.tripId, EventTypes.ARRIVAL_DETECTED, EventSource.SYSTEM_INFERRED, now, p.lat, p.lng, emptyMap(), false)
                    db.tripDao().update(stamped)
                    if (shouldNotify) notifier.showArrivalDetected(stamped.destName)
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

    private suspend fun maybeRefreshRoute(t: ActiveTripEntity, from: GeoPoint, now: Long) {
        val stale = now - routeFetchedAtMs > cfg.routeRefreshMin * 60_000
        val driving = state?.journey == JourneyStatus.DRIVING.name
        if (stale && driving && activeProfile().isRoadMode) {
            val r = routing.route(from, legDestination(t))
            if (r != null) { currentRoute = r; routeFetchedAtMs = now }
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

    private suspend fun completeInternal(
        t: ActiveTripEntity, s0: TripStateEntity, now: Long, closingNote: String? = null
    ) {
        var s = s0
        transition(s, JourneyInput.COMPLETE)?.let { s = s.copy(journey = it.name) }

        // The traveller's last word, recorded before the summary so it lands
        // in the timeline everyone (and the exported PDF) reads.
        if (!closingNote.isNullOrBlank()) {
            insertEvent(
                t.tripId, EventTypes.QUICK_NOTE, EventSource.DRIVER_MANUAL, now, s.lat, s.lng,
                mapOf("text" to closingNote.trim()), false
            )
        }

        val events = db.eventDao().allForTrip(t.tripId).map { EventCodec.toDomain(it) }
        val started = t.startedAtMs ?: t.createdAtMs
        val summary = SummaryCalculator.compute(events, s.distanceCoveredM, started, now)

        insertEvent(t.tripId, EventTypes.TRIP_COMPLETED, EventSource.DRIVER_MANUAL, now, s.lat, s.lng,
            summaryMap(summary), false)

        db.legDao().markCompleted(t.tripId, t.activeLegIndex, now)

        // Credentials self-destruct only AFTER the traveller closed the journey,
        // and with enough grace that a follower who opens the app right then
        // still sees the arrival rather than an empty screen.
        val expires = now + cfg.expiryGraceMin * 60_000
        val completed = t.copy(
            status = "COMPLETED", completedAtMs = now, expiresAtMs = expires, endedByOwner = true
        )
        db.tripDao().update(completed); trip = completed

        s = s.copy(etaMode = EtaMode.ARRIVED.name, arrivalPromptDue = false, updatedAtMs = now)
        db.stateDao().upsert(s); state = s

        // The journey is over the instant it is written locally. Everything
        // below is delivery, and delivery must never hold the traveller.
        //
        // This used to run inline, and it was the bug that froze the app on
        // completion: drain() walks every unsent event and every buffered
        // location batch one network round-trip at a time, so a journey whose
        // uploads had been failing all night had thousands of rows to push --
        // minutes of work, holding this mutex, with the screen showing nothing.
        // Pushed to the application scope it survives this screen, keeps its
        // ordering (the completion state first, so followers see the arrival
        // straight away, then the backlog), and cannot be lost: every row is
        // already durable in Room and a later drain picks up whatever this one
        // does not finish.
        if (completed.cloudEnabled) {
            val stateSnapshot = stateMap(completed, s)
            appScope.launch {
                runCatching {
                    sync.pushLiveState(completed, stateSnapshot, force = true)
                    cloud.setExpiry(completed.accessKey, expires)
                    sync.drain(completed)
                }
            }
        }
        notifier.showArrival(completed.destName)
        onStopTrackingRequested?.invoke()
    }

    private suspend fun appendSosDelivered(tripId: String) {
        db.tripDao().byId(tripId) ?: return
        val now = System.currentTimeMillis()
        insertEvent(tripId, EventTypes.SOS_DELIVERED, EventSource.SERVER_DERIVED, now, null, null, emptyMap(), false)
        // don't recurse into drain here; the normal drain loop will pick it up
    }

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
        deviationActive = false, checkpointDue = false, checkpointStopStartMs = null,
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
        }
    )

    private fun stateMap(t: ActiveTripEntity, s: TripStateEntity): Map<String, Any?> = buildMap {
        put("status", s.journey)
        put("connectivity", s.connectivity)
        put("endedByOwner", t.endedByOwner)
        put("legIndex", s.legIndex)
        s.lat?.let { put("lat", it) }; s.lng?.let { put("lng", it) }
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
        put("deviationActive", s.deviationActive)
        // Going dark: pushed so a follower can tell "switched off with charge
        // left" from "ran out of battery" without having to guess from a gap.
        t.wentDarkAtMs?.let { put("wentDarkAt", it) }
        t.darkReason?.let { put("darkReason", it) }
        t.simChangedAtMs?.let { put("simChangedAt", it) }
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
