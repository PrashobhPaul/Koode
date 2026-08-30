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
        val bookingRef: String? = null,
        val seat: String? = null,
        val boardingPoint: String? = null
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
        /** Retained for callers that still ask the old question. */
        val PRIVATE_MODES: Set<String> = TransportCatalog.PRIVATE_KEYS
    }

    /** Creates a journey, generating credentials, legs and an initial route. */
    suspend fun createTrip(n: NewTrip): ActiveTripEntity = lock.withLock {
        require(n.legs.isNotEmpty()) { "A journey needs at least one leg" }
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
                bookingRef = leg.bookingRef, seat = leg.seat, boardingPoint = leg.boardingPoint
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
    // Hybrid journeys: moving from one leg to the next
    // -----------------------------------------------------------------------

    /**
     * Closes the current leg and starts the next one. The route, the rule set
     * and the sampling cadence all follow the new leg's mode of transport.
     */
    suspend fun advanceToNextLeg() = lock.withLock {
        val t = trip ?: return@withLock
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
    suspend fun onTick() = lock.withLock {
        val t = trip ?: return@withLock
        var s = state ?: return@withLock
        if (terminal(s)) return@withLock
        val now = System.currentTimeMillis()
        val profile = activeProfile()

        // dwell-based stop maturation / long-stop
        val move = detector.onTick(now)
        if (move != null) s = applyMovement(t, s, move, null, now, profile)

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
        val t = trip ?: return@withLock
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
        val t = trip ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        insertEvent(t.tripId, EventTypes.BREAK_CHECKPOINT_SKIPPED, EventSource.DRIVER_MANUAL, now, s.lat, s.lng, emptyMap(), false)
        s = s.copy(checkpointDue = false, checkpointStopStartMs = null,
            checkpointStopEndMs = null, checkpointStopDurationS = null, updatedAtMs = now)
        persistAndPush(t, s); state = s
    }

    /** type: HOTEL | HOME | FAMILY | VEHICLE | CONTINUING */
    suspend fun answerOvernight(type: String) = lock.withLock {
        val t = trip ?: return@withLock
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
        val t = trip ?: return@withLock
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
        val t = trip ?: return@withLock
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
        val t = trip ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        insertEvent(t.tripId, EventTypes.SOS_RESOLVED, EventSource.DRIVER_CONFIRMATION, now, s.lat, s.lng, emptyMap(), false)
        s = s.copy(sosActive = false, updatedAtMs = now)
        persistAndPush(t, s, force = true); state = s
        onSamplingChanged?.invoke()
    }

    suspend fun pause() = lock.withLock {
        val t = trip ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        transition(s, JourneyInput.PAUSE)?.let { s = s.copy(journey = it.name) }
        insertEvent(t.tripId, EventTypes.TRIP_PAUSED, EventSource.DRIVER_MANUAL, now, s.lat, s.lng, emptyMap(), false)
        s = s.copy(updatedAtMs = now); persistAndPush(t, s, force = true); state = s
        onSamplingChanged?.invoke()
    }

    suspend fun resume() = lock.withLock {
        val t = trip ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        transition(s, JourneyInput.RESUME)?.let { s = s.copy(journey = it.name) }
        insertEvent(t.tripId, EventTypes.TRIP_RESUMED, EventSource.DRIVER_MANUAL, now, s.lat, s.lng, emptyMap(), false)
        s = s.copy(drivingSinceMs = now, updatedAtMs = now); persistAndPush(t, s, force = true); state = s
        onSamplingChanged?.invoke()
    }

    suspend fun changeDestination(destName: String, dest: GeoPoint) = lock.withLock {
        val t0 = trip ?: return@withLock
        val now = System.currentTimeMillis()
        var s = state ?: return@withLock
        val from = GeoPoint(s.lat ?: t0.originLat, s.lng ?: t0.originLng)
        val route = routing.route(from, dest)
        currentRoute = route; routeFetchedAtMs = now
        val t = t0.copy(destName = destName, destLat = dest.lat, destLng = dest.lng,
            totalRouteDistanceM = route?.distanceM ?: t0.totalRouteDistanceM,
            arrivedAtMs = null)
        db.tripDao().update(t); trip = t
        arrivalPromptShown = false
        insertEvent(t.tripId, EventTypes.DESTINATION_CHANGED, EventSource.DRIVER_MANUAL, now, dest.lat, dest.lng,
            mapOf("destination" to destName), false)
        val remM = remainingDistanceM(from)
        s = recomputeEta(t, s, remainingTravelSeconds(remM), remM, now)
            .copy(arrivalPromptDue = false, updatedAtMs = now)
        if (t.cloudEnabled) appScope.launch { sync.writeMetaUpdate(t, metaMap(t)) }
        persistAndPush(t, s, force = true); state = s
    }

    /** Dismiss the "you seem to have arrived" prompt without ending anything. */
    suspend fun dismissArrivalPrompt() = lock.withLock {
        val t = trip ?: return@withLock
        var s = state ?: return@withLock
        s = s.copy(arrivalPromptDue = false, updatedAtMs = System.currentTimeMillis())
        persistAndPush(t, s); state = s
    }

    /**
     * Ends the journey. The only path to [JourneyStatus.COMPLETED].
     *
     * There is deliberately no automatic caller. A journey that looks finished
     * — parked at the destination, out of battery, out of coverage — is still
     * the traveller's to close, because everyone watching reads "ended" as
     * "they're safe and home", and the app must never say that on its own.
     */
    suspend fun completeTrip() = lock.withLock {
        val t = trip ?: return@withLock
        val s = state ?: return@withLock
        completeInternal(t, s, System.currentTimeMillis())
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

    private suspend fun completeInternal(t: ActiveTripEntity, s0: TripStateEntity, now: Long) {
        var s = s0
        transition(s, JourneyInput.COMPLETE)?.let { s = s.copy(journey = it.name) }

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

        if (completed.cloudEnabled) {
            sync.pushLiveState(completed, stateMap(completed, s), force = true)
            sync.drain(completed)
            cloud.setExpiry(completed.accessKey, expires)
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
