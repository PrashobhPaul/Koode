package com.trippulse.app.data

import android.content.Context
import com.trippulse.app.core.Geo
import com.trippulse.app.core.TripCredentials
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.data.local.BreakRecordEntity
import com.trippulse.app.data.local.EventEntity
import com.trippulse.app.data.local.LocationSampleEntity
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
import com.trippulse.app.domain.RouteDeviationDetector
import com.trippulse.app.domain.RoutePlan
import com.trippulse.app.domain.StopDetector
import com.trippulse.app.domain.SummaryCalculator
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
 * Central journey orchestrator (docs/spec/09-22, 45, 73-135). Owns the trip
 * state, drives detection engines from location fixes and ticks, records events
 * into the durable local log, recomputes the realistic ETA, and hands work to
 * the two-lane [SyncEngine]. Every mutation goes through [lock] so location
 * updates, ticks and driver actions never race.
 */
class TripManager(
    private val appContext: Context,
    private val db: TripPulseDb,
    private val cloud: TripCloud,
    private val routing: RoutingProvider,
    private val sync: SyncEngine,
    private val connectivity: ConnectivityObserver,
    private val notifier: Notifier,
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

    private var detector = StopDetector(cfg)
    private var deviation = RouteDeviationDetector(cfg)

    private var currentRoute: RoutePlan? = null
    private var routeFetchedAtMs: Long = 0
    private var lastPersistMs: Long = 0
    private var lastPersistPoint: GeoPoint? = null
    private var lastDistancePoint: GeoPoint? = null
    private var lastEtaCalcMs: Long = 0
    private var batteryLowFired = false
    private var arrivedAtMs: Long? = null

    init {
        sync.onSosDelivered = { tripId -> appendSosDelivered(tripId) }
    }

    // ---- flows for UI ----
    fun activeTripFlow(): Flow<ActiveTripEntity?> = db.tripDao().activeTripFlow()
    fun stateFlow(tripId: String): Flow<TripStateEntity?> = db.stateDao().flow(tripId)
    fun eventsFlow(tripId: String): Flow<List<EventEntity>> = db.eventDao().eventsFlow(tripId)
    fun pendingCountFlow(): Flow<Int> = db.eventDao().pendingCountFlow()

    fun cloudAvailable(): Boolean = cloud.isAvailable()
    fun currentTripIdOrNull(): String? = trip?.tripId

    /** Corroborating in-vehicle hint from Activity Recognition. */
    suspend fun onActivityHint(inVehicle: Boolean) = lock.withLock {
        detector.onActivityHint(inVehicle)
    }

    /** Reload the active trip from disk (after process/service restart). */
    suspend fun loadActive(): ActiveTripEntity? = lock.withLock {
        val t = db.tripDao().activeTrip() ?: return@withLock null
        trip = t
        state = db.stateDao().byId(t.tripId)
        arrivedAtMs = if (state?.journey == JourneyStatus.ARRIVED.name) state?.updatedAtMs else null
        // detectors restart clean; persisted journey state is authoritative
        detector = StopDetector(cfg)
        deviation = RouteDeviationDetector(cfg)
        t
    }

    // -----------------------------------------------------------------------
    // Trip lifecycle
    // -----------------------------------------------------------------------

    data class NewTrip(
        val originName: String, val origin: GeoPoint,
        val destName: String, val destination: GeoPoint,
        val plannedDepartureMs: Long?,
        val emergencyName: String?, val emergencyPhone: String?,
        val cloudEnabled: Boolean,
        val transportMode: String = "CAR",
        val fuelType: String? = null
    )

    companion object {
        val PRIVATE_MODES = setOf("CAR", "BIKE")
    }

    /** Creates a trip, generating credentials and an initial route. */
    suspend fun createTrip(n: NewTrip): ActiveTripEntity = lock.withLock {
        val now = System.currentTimeMillis()
        val tripId = TripCredentials.newTripId()
        val secret = TripCredentials.newSecret()
        val accessKey = TripCredentials.accessKey(tripId, secret)

        val route = routing.route(n.origin, n.destination)
        currentRoute = route
        routeFetchedAtMs = now

        val t = ActiveTripEntity(
            tripId = tripId, secret = secret, accessKey = accessKey,
            originName = n.originName, originLat = n.origin.lat, originLng = n.origin.lng,
            destName = n.destName, destLat = n.destination.lat, destLng = n.destination.lng,
            emergencyName = n.emergencyName, emergencyPhone = n.emergencyPhone,
            createdAtMs = now, plannedDepartureMs = n.plannedDepartureMs,
            startedAtMs = null, completedAtMs = null, expiresAtMs = null,
            status = "CREATED",
            cloudEnabled = n.cloudEnabled && cloud.isAvailable(),
            metaSynced = false,
            totalRouteDistanceM = route?.distanceM ?: 0.0,
            ownerUid = null,
            transportMode = n.transportMode,
            fuelType = if (n.transportMode in PRIVATE_MODES) n.fuelType else null
        )
        db.tripDao().upsert(t)
        trip = t

        val s = freshState(t, now)
        db.stateDao().upsert(s)
        state = s

        insertEvent(
            t.tripId, EventTypes.TRIP_CREATED, EventSource.DRIVER_MANUAL, now,
            n.origin.lat, n.origin.lng,
            mapOf("origin" to n.originName, "destination" to n.destName), false
        )
        t
    }

    /** Marks the trip active and starts the journey clock. */
    suspend fun startTrip(tripId: String) = lock.withLock {
        val t = db.tripDao().byId(tripId) ?: return@withLock
        val now = System.currentTimeMillis()
        val started = t.copy(status = "ACTIVE", startedAtMs = now)
        db.tripDao().update(started)
        trip = started

        val s = (state ?: db.stateDao().byId(tripId) ?: freshState(started, now)).copy(
            journey = JourneyStatus.READY.name,
            drivingSinceMs = now,
            connectivity = connectivityNow().name,
            updatedAtMs = now
        )
        db.stateDao().upsert(s); state = s

        insertEvent(tripId, EventTypes.TRIP_STARTED, EventSource.DRIVER_MANUAL, now, null, null, emptyMap(), false)

        // arm cloud meta + first live push
        if (started.cloudEnabled) appScope.launch {
            sync.ensureMeta(started, metaMap(started))
            sync.pushLiveState(started, stateMap(started, s), force = true)
        }
    }

    // -----------------------------------------------------------------------
    // Location + tick loop
    // -----------------------------------------------------------------------

    suspend fun onLocation(fix: Fix) = lock.withLock {
        val t = trip ?: return@withLock
        var s = state ?: return@withLock
        if (terminal(s)) return@withLock
        val now = fix.timeMs

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
        s = applyMovement(t, s, move, fix, now)

        // ----- route refresh + remaining distance/time -----
        maybeRefreshRoute(t, fix.point, now)
        val remainingM = remainingDistanceM(fix.point)
        val remainingS = remainingTravelSeconds(remainingM)

        // ----- route deviation (only with a real polyline) -----
        val route = currentRoute
        if (route != null && route.provider != "fallback" && route.polyline.size >= 2 &&
            s.journey == JourneyStatus.DRIVING.name
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

        // dwell-based stop maturation / long-stop
        val move = detector.onTick(now)
        if (move != null) s = applyMovement(t, s, move, null, now)

        // battery-low (edge triggered)
        val bat = s.batteryPct
        if (bat != null && bat <= cfg.lowBatteryPct && !batteryLowFired) {
            batteryLowFired = true
            insertEvent(t.tripId, EventTypes.BATTERY_LOW, EventSource.SENSOR_OBSERVED, now, s.lat, s.lng,
                mapOf("battery" to bat), false)
        }
        if (bat != null && bat > cfg.lowBatteryPct + 5) batteryLowFired = false

        // auto-complete after arrival grace
        if (s.journey == JourneyStatus.ARRIVED.name) {
            val at = arrivedAtMs ?: s.updatedAtMs
            if (now - at >= cfg.autoCompleteGraceS * 1000) {
                completeInternal(t, s, now, auto = true)
                return@withLock
            }
        }

        s = s.copy(connectivity = connectivityNow().name, updatedAtMs = now)
        persistAndPush(t, s, heartbeat = true)
        state = s
    }

    // -----------------------------------------------------------------------
    // Driver actions
    // -----------------------------------------------------------------------

    data class Checkpoint(
        val water: Boolean = false, val food: Boolean = false, val toilet: Boolean = false,
        val rest: Boolean = false, val fuel: Boolean = false, val charge: Boolean = false,
        val other: Boolean = false
    )

    suspend fun submitCheckpoint(c: Checkpoint) = lock.withLock {
        val t = trip ?: return@withLock
        var s = state ?: return@withLock
        val now = System.currentTimeMillis()
        val startMs = s.checkpointStopStartMs ?: (now - (s.checkpointStopDurationS ?: 0) * 1000)
        val endMs = s.checkpointStopEndMs ?: now

        insertEvent(t.tripId, EventTypes.BREAK_CHECKPOINT, EventSource.DRIVER_CONFIRMATION, now, s.lat, s.lng,
            mapOf("water" to c.water, "food" to c.food, "toilet" to c.toilet,
                "rest" to c.rest, "fuel" to c.fuel, "charge" to c.charge, "other" to c.other), false)

        if (c.water) reportWellbeing(t, EventTypes.WATER_REPORTED, now, s.lat, s.lng)
        if (c.food) reportWellbeing(t, EventTypes.FOOD_REPORTED, now, s.lat, s.lng)
        if (c.toilet) reportWellbeing(t, EventTypes.TOILET_REPORTED, now, s.lat, s.lng)
        if (c.rest) reportWellbeing(t, EventTypes.REST_REPORTED, now, s.lat, s.lng)
        if (c.fuel) reportWellbeing(t, EventTypes.FUEL_STOP, now, s.lat, s.lng)
        if (c.charge) reportWellbeing(t, EventTypes.CHARGE_STOP, now, s.lat, s.lng)

        db.breakDao().upsert(
            BreakRecordEntity(
                breakId = UUID.randomUUID().toString(), tripId = t.tripId,
                startMs = startMs, endMs = endMs,
                durationS = ((endMs - startMs) / 1000).coerceAtLeast(0),
                lat = s.lat, lng = s.lng,
                water = c.water, food = c.food, toilet = c.toilet, rest = c.rest,
                fuel = c.fuel, charge = c.charge, other = c.other,
                confirmationSource = "DRIVER_CONFIRMATION"
            )
        )

        s = s.copy(
            waterAtMs = if (c.water) now else s.waterAtMs,
            foodAtMs = if (c.food) now else s.foodAtMs,
            toiletAtMs = if (c.toilet) now else s.toiletAtMs,
            restAtMs = if (c.rest) now else s.restAtMs,
            fuelAtMs = if (c.fuel) now else s.fuelAtMs,
            lastBreakEndAtMs = endMs,
            checkpointDue = false, checkpointStopStartMs = null,
            checkpointStopEndMs = null, checkpointStopDurationS = null,
            updatedAtMs = now
        )
        // break changes the ETA break budget
        s = recomputeEta(t, s, remainingTravelSeconds(s.distanceRemainingM), s.distanceRemainingM, now)
        persistAndPush(t, s, force = true)
        state = s
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

    /** type: PASSENGER_JOINED | PASSENGER_LEFT | MEDICINE | VEHICLE_ISSUE | QUICK_NOTE | ... */
    suspend fun addQuickNote(type: String, text: String?) = lock.withLock {
        val t = trip ?: return@withLock
        val s = state ?: return@withLock
        val now = System.currentTimeMillis()
        val sensitive = EventTypes.isSensitiveByDefault(type)
        val payload = buildMap<String, Any?> { if (!text.isNullOrBlank()) put("text", text) }
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
            totalRouteDistanceM = route?.distanceM ?: t0.totalRouteDistanceM)
        db.tripDao().update(t); trip = t
        insertEvent(t.tripId, EventTypes.DESTINATION_CHANGED, EventSource.DRIVER_MANUAL, now, dest.lat, dest.lng,
            mapOf("destination" to destName), false)
        val remM = remainingDistanceM(from)
        s = recomputeEta(t, s, remainingTravelSeconds(remM), remM, now).copy(updatedAtMs = now)
        if (t.cloudEnabled) appScope.launch { sync.writeMetaUpdate(t, metaMap(t)) }
        persistAndPush(t, s, force = true); state = s
    }

    suspend fun completeTrip() = lock.withLock {
        val t = trip ?: return@withLock
        val s = state ?: return@withLock
        completeInternal(t, s, System.currentTimeMillis(), auto = false)
    }

    // -----------------------------------------------------------------------
    // Sampling interval (read by the foreground service)
    // -----------------------------------------------------------------------

    fun currentSamplingIntervalMs(): Long {
        val s = state
        if (s?.sosActive == true) return cfg.samplingSosS * 1000
        val bat = s?.batteryPct
        if (bat != null && bat <= cfg.lowBatteryPct) return cfg.lowBatterySamplingS * 1000
        return when (s?.journey) {
            JourneyStatus.OVERNIGHT.name -> cfg.samplingOvernightS * 1000
            JourneyStatus.PAUSED.name -> cfg.samplingPausedS * 1000
            JourneyStatus.STOPPED.name, JourneyStatus.LONG_STOP.name, JourneyStatus.POSSIBLE_STOP.name ->
                cfg.samplingStationaryS * 1000
            else -> cfg.samplingDrivingS * 1000
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
        t: ActiveTripEntity, s0: TripStateEntity, move: StopDetector.Movement?, fix: Fix?, now: Long
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
                // Rule: prompt for the break log WHILE stationary — a driver
                // can't log anything while moving, so the moment a genuine
                // stop is confirmed is exactly when their hands are free.
                s = s.copy(stopStartedAtMs = began, checkpointDue = true, checkpointStopStartMs = began)
                notifier.showBreakPrompt(t.transportMode in PRIVATE_MODES)
                appScope.launch { insertEvent(t.tripId, EventTypes.STOP_STARTED, EventSource.SYSTEM_INFERRED, now, fix?.point?.lat ?: s0.lat, fix?.point?.lng ?: s0.lng, emptyMap(), false) }
                onSamplingChanged?.invoke()
            }
            is StopDetector.Movement.StopEnded -> {
                transition(s, JourneyInput.RESTART)?.let { s = s.copy(journey = it.name) }
                val began = s.stopStartedAtMs ?: (now - move.durationS * 1000)
                s = s.copy(
                    stopStartedAtMs = null, drivingSinceMs = now, lastBreakEndAtMs = now,
                    checkpointDue = true, checkpointStopStartMs = began,
                    checkpointStopEndMs = now, checkpointStopDurationS = move.durationS,
                    longStopPromptDue = false
                )
                appScope.launch { insertEvent(t.tripId, EventTypes.STOP_ENDED, EventSource.SYSTEM_INFERRED, now, fix?.point?.lat ?: s0.lat, fix?.point?.lng ?: s0.lng, mapOf("durationSeconds" to move.durationS), false) }
                onSamplingChanged?.invoke()
            }
            is StopDetector.Movement.LongStop -> {
                transition(s, JourneyInput.LONG_STOP)?.let { s = s.copy(journey = it.name) }
                s = s.copy(longStopPromptDue = true)
                appScope.launch { insertEvent(t.tripId, EventTypes.LONG_STOP, EventSource.SYSTEM_INFERRED, now, s0.lat, s0.lng, emptyMap(), false) }
                onSamplingChanged?.invoke()
            }
            is StopDetector.Movement.None -> {}
            null -> {}
        }
        return s
    }

    private fun maybeArrival(t: ActiveTripEntity, s0: TripStateEntity, p: GeoPoint, now: Long): TripStateEntity {
        var s = s0
        if (s.journey == JourneyStatus.ARRIVED.name) return s
        val dest = GeoPoint(t.destLat, t.destLng)
        val dist = Geo.haversineM(p, dest)
        if (dist <= cfg.arrivalRadiusM && detector.isStationary()) {
            val began = detector.stopStartedAtMs() ?: now
            if (now - began >= cfg.arrivalConfirmS * 1000) {
                transition(s, JourneyInput.ARRIVED)?.let { s = s.copy(journey = it.name) }
                arrivedAtMs = now
                // Stamp the 30-min self-destruct the moment arrival is
                // detected, so the trip id dies on time even if the app never
                // runs the later auto-complete tick.
                val expires = now + cfg.expiryGraceMin * 60_000
                val stamped = t.copy(expiresAtMs = expires)
                trip = stamped
                appScope.launch {
                    insertEvent(t.tripId, EventTypes.ARRIVAL_DETECTED, EventSource.SYSTEM_INFERRED, now, p.lat, p.lng, emptyMap(), false)
                    db.tripDao().update(stamped)
                    if (stamped.cloudEnabled) cloud.setExpiry(stamped.accessKey, expires)
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
        if (stale && driving) {
            val r = routing.route(from, GeoPoint(t.destLat, t.destLng))
            if (r != null) { currentRoute = r; routeFetchedAtMs = now }
        }
    }

    private fun remainingDistanceM(from: GeoPoint): Double {
        val t = trip ?: return 0.0
        val route = currentRoute
        val dest = GeoPoint(t.destLat, t.destLng)
        return if (route != null && route.provider != "fallback" && route.polyline.size >= 2) {
            Geo.remainingAlongPathM(from, route.polyline)
        } else {
            Geo.haversineM(from, dest) * cfg.roadDistanceFactor
        }
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

    private suspend fun reportWellbeing(t: ActiveTripEntity, type: String, now: Long, lat: Double?, lng: Double?) {
        insertEvent(t.tripId, type, EventSource.DRIVER_CONFIRMATION, now, lat, lng, emptyMap(), false)
    }

    private suspend fun completeInternal(t: ActiveTripEntity, s0: TripStateEntity, now: Long, auto: Boolean) {
        var s = s0
        transition(s, JourneyInput.COMPLETE)?.let { s = s.copy(journey = it.name) }

        val events = db.eventDao().allForTrip(t.tripId).map { EventCodec.toDomain(it) }
        val started = t.startedAtMs ?: t.createdAtMs
        val summary = SummaryCalculator.compute(events, s.distanceCoveredM, started, now)

        insertEvent(t.tripId, EventTypes.TRIP_COMPLETED, sourceForCompletion(auto), now, s.lat, s.lng,
            summaryMap(summary), false)

        // The trip id self-destructs expiryGraceMin (30 min) after the driver
        // REACHED the destination — not after the later auto-complete tick —
        // falling back to completion time for manual completion mid-route.
        val expires = (arrivedAtMs ?: now) + cfg.expiryGraceMin * 60_000
        val completed = t.copy(status = "COMPLETED", completedAtMs = now, expiresAtMs = expires)
        db.tripDao().update(completed); trip = completed

        s = s.copy(etaMode = EtaMode.ARRIVED.name, updatedAtMs = now)
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
        val t = db.tripDao().byId(tripId) ?: return
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
        possibleIncidentDue = false, updatedAtMs = now
    )

    // ---- cloud maps ----

    /** The traveller's display name ("Prashobh's Journey" on family screens). */
    private fun ownerName(): String? =
        appContext.getSharedPreferences("koode_profile", Context.MODE_PRIVATE)
            .getString("name", null)?.ifBlank { null }

    private fun metaMap(t: ActiveTripEntity): Map<String, Any?> = mapOf(
        "ownerName" to ownerName(),
        "transportMode" to t.transportMode,
        "tripId" to t.tripId,
        "origin" to t.originName, "destination" to t.destName,
        "originLat" to t.originLat, "originLng" to t.originLng,
        "destLat" to t.destLat, "destLng" to t.destLng,
        "createdAt" to t.createdAtMs, "startedAt" to t.startedAtMs,
        "expiresAt" to (t.expiresAtMs ?: (t.createdAtMs + 36L * 3600 * 1000)),
        "totalRouteDistanceM" to t.totalRouteDistanceM
    )

    private fun stateMap(t: ActiveTripEntity, s: TripStateEntity): Map<String, Any?> = buildMap {
        put("status", s.journey)
        put("connectivity", s.connectivity)
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
        "longestLegSeconds" to s.longestLegSeconds, "longestBreakSeconds" to s.longestBreakSeconds,
        "days" to s.days
    )

    private fun sourceForCompletion(auto: Boolean): EventSource =
        if (auto) EventSource.SYSTEM_INFERRED else EventSource.DRIVER_MANUAL
}
