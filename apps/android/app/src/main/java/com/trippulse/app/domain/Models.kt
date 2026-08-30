package com.trippulse.app.domain

/**
 * Core domain model for TripPulse. Pure Kotlin, no Android imports, so every
 * type here is unit-testable on the JVM and reusable by the (future) web
 * viewer if the domain is ever extracted into a shared module.
 *
 * Spec references: docs/spec/03_DOMAIN_EVENT_MODEL.md,
 * docs/spec/07_ETA_JOURNEY_INTELLIGENCE.md, docs/spec/12_API_DATA_CONTRACTS.md.
 */

/** Journey (trip) lifecycle state. Orthogonal to [Connectivity]. */
enum class JourneyStatus {
    READY,          // trip created / started, awaiting first movement
    DRIVING,
    POSSIBLE_STOP,  // stationary but not yet confirmed a real stop
    STOPPED,        // confirmed stop (break candidate)
    LONG_STOP,      // stop exceeding the long-stop threshold (>2h default)
    OVERNIGHT,      // driver confirmed an overnight halt
    PAUSED,         // driver manually paused tracking
    ARRIVED,        // within arrival radius — the app asks, it never decides
    COMPLETED,      // journey finished, and ONLY the traveller can put it here
    EXPIRED         // credentials revoked / access destroyed
}

/** Connectivity of the driver device as understood locally. */
enum class Connectivity { ONLINE, DEGRADED, OFFLINE }

/** Freshness of the data a viewer is looking at. Derived from last update age. */
enum class Freshness { LIVE, RECENT, STALE, OFFLINE, UNKNOWN, COMPLETED }

/**
 * Provenance of an event. This is fundamental to the product's trust model:
 * the viewer must always be able to tell whether a fact was observed by a
 * sensor, inferred by software, confirmed by the driver, manually entered, or
 * derived server-side. Never collapse these.
 */
enum class EventSource {
    SENSOR_OBSERVED,
    SYSTEM_INFERRED,
    DRIVER_CONFIRMATION,
    DRIVER_MANUAL,
    SERVER_DERIVED
}

/** Local sync lifecycle for a durable event or location sample. */
enum class SyncStatus {
    PENDING,
    UPLOADING,
    ACKED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    COMPACTED           // superseded location sample dropped from upload
}

/** ETA presentation mode. */
enum class EtaMode { NORMAL, OVERNIGHT_PENDING, ARRIVED, UNKNOWN }

/**
 * The complete event-type taxonomy. Stored as strings so the on-the-wire and
 * on-disk representation is stable and human-readable, and so unknown future
 * types round-trip without breaking older clients.
 */
object EventTypes {
    // lifecycle
    const val TRIP_CREATED = "TRIP_CREATED"
    const val TRIP_STARTED = "TRIP_STARTED"
    const val TRIP_PAUSED = "TRIP_PAUSED"
    const val TRIP_RESUMED = "TRIP_RESUMED"
    const val TRIP_COMPLETED = "TRIP_COMPLETED"
    const val TRIP_EXPIRED = "TRIP_EXPIRED"
    const val DESTINATION_CHANGED = "DESTINATION_CHANGED"

    // movement / detection
    const val LOCATION_UPDATE = "LOCATION_UPDATE"
    const val DRIVING_STARTED = "DRIVING_STARTED"
    const val STOP_STARTED = "STOP_STARTED"
    const val STOP_ENDED = "STOP_ENDED"
    const val LONG_STOP = "LONG_STOP"
    const val ROUTE_DEVIATION = "ROUTE_DEVIATION"
    const val ROUTE_REJOINED = "ROUTE_REJOINED"
    const val ARRIVAL_DETECTED = "ARRIVAL_DETECTED"

    // wellbeing checkpoint
    const val BREAK_CHECKPOINT = "BREAK_CHECKPOINT"
    const val BREAK_CHECKPOINT_SKIPPED = "BREAK_CHECKPOINT_SKIPPED"
    const val WATER_REPORTED = "WATER_REPORTED"
    const val FOOD_REPORTED = "FOOD_REPORTED"
    const val TOILET_REPORTED = "TOILET_REPORTED"
    const val REST_REPORTED = "REST_REPORTED"
    const val FUEL_STOP = "FUEL_STOP"
    const val CHARGE_STOP = "CHARGE_STOP"
    // Refreshments that are not a meal. Kept separate from FOOD_REPORTED so a
    // cup of tea never reads as "they have eaten" on a family member's screen.
    const val TEA_COFFEE_REPORTED = "TEA_COFFEE_REPORTED"
    const val SNACK_REPORTED = "SNACK_REPORTED"

    // public-transport journey milestones
    const val BOARDED = "BOARDED"
    const val TRANSIT_HALTED = "TRANSIT_HALTED"
    const val TRANSIT_RESUMED = "TRANSIT_RESUMED"
    const val DEBOARDED = "DEBOARDED"

    // hybrid journeys: one journey, several modes of transport
    const val LEG_STARTED = "LEG_STARTED"
    const val LEG_COMPLETED = "LEG_COMPLETED"

    // overnight
    const val OVERNIGHT_CANDIDATE = "OVERNIGHT_CANDIDATE"
    const val OVERNIGHT_CONFIRMED = "OVERNIGHT_CONFIRMED"
    const val MORNING_RESUME = "MORNING_RESUME"

    // notes / passenger / medicine
    const val QUICK_NOTE = "QUICK_NOTE"
    const val PASSENGER_JOINED = "PASSENGER_JOINED"
    const val PASSENGER_LEFT = "PASSENGER_LEFT"
    const val MEDICINE = "MEDICINE"
    const val VEHICLE_ISSUE = "VEHICLE_ISSUE"
    const val INCIDENT = "INCIDENT"

    // SOS
    const val POSSIBLE_INCIDENT = "POSSIBLE_INCIDENT"
    const val SOS_ACTIVATED = "SOS_ACTIVATED"
    const val SOS_RESOLVED = "SOS_RESOLVED"
    const val SOS_DELIVERED = "SOS_DELIVERED"

    // connectivity / system
    const val NETWORK_ONLINE = "NETWORK_ONLINE"
    const val NETWORK_OFFLINE = "NETWORK_OFFLINE"
    const val BATTERY_LOW = "BATTERY_LOW"
    const val ETA_UPDATED = "ETA_UPDATED"

    /** Events that carry a driver-visible line in the viewer timeline. */
    val TIMELINE_TYPES: Set<String> = setOf(
        TRIP_STARTED, TRIP_PAUSED, TRIP_RESUMED, TRIP_COMPLETED, DESTINATION_CHANGED,
        STOP_STARTED, STOP_ENDED, LONG_STOP, ROUTE_DEVIATION, ROUTE_REJOINED, ARRIVAL_DETECTED,
        BREAK_CHECKPOINT, WATER_REPORTED, FOOD_REPORTED, TOILET_REPORTED, REST_REPORTED,
        TEA_COFFEE_REPORTED, SNACK_REPORTED,
        BOARDED, TRANSIT_HALTED, TRANSIT_RESUMED, DEBOARDED, LEG_STARTED, LEG_COMPLETED,
        FUEL_STOP, CHARGE_STOP, OVERNIGHT_CONFIRMED, MORNING_RESUME,
        QUICK_NOTE, PASSENGER_JOINED, PASSENGER_LEFT, MEDICINE, VEHICLE_ISSUE, INCIDENT,
        POSSIBLE_INCIDENT, SOS_ACTIVATED, SOS_RESOLVED, BATTERY_LOW
    )

    /** Priority for sync ordering: lower = more urgent. */
    fun priorityFor(type: String): Int = when (type) {
        SOS_ACTIVATED, SOS_RESOLVED, POSSIBLE_INCIDENT -> 0
        BREAK_CHECKPOINT, WATER_REPORTED, FOOD_REPORTED, TOILET_REPORTED, REST_REPORTED,
        TEA_COFFEE_REPORTED, SNACK_REPORTED,
        BOARDED, TRANSIT_HALTED, TRANSIT_RESUMED, DEBOARDED, LEG_STARTED, LEG_COMPLETED,
        FUEL_STOP, CHARGE_STOP, STOP_STARTED, STOP_ENDED, LONG_STOP,
        OVERNIGHT_CONFIRMED, MORNING_RESUME, ARRIVAL_DETECTED, DESTINATION_CHANGED,
        QUICK_NOTE, PASSENGER_JOINED, PASSENGER_LEFT, MEDICINE, VEHICLE_ISSUE, INCIDENT,
        TRIP_STARTED, TRIP_COMPLETED, TRIP_PAUSED, TRIP_RESUMED -> 1
        else -> 2
    }

    /** Sensitive events whose content is not shared by default. */
    fun isSensitiveByDefault(type: String): Boolean = type == MEDICINE
}

/** A geographic point. */
data class GeoPoint(val lat: Double, val lng: Double)

/** A single location fix from the fused provider (or last-known). */
data class Fix(
    val point: GeoPoint,
    val accuracyM: Float,
    val speedMps: Float?,      // null when the provider gives no speed
    val bearing: Float?,
    val timeMs: Long,
    val batteryPct: Int?
)

/** An immutable journey event. [payload] holds type-specific structured data. */
data class TripEvent(
    val eventId: String,
    val tripId: String,
    val type: String,
    val eventTimeMs: Long,
    val lat: Double?,
    val lng: Double?,
    val accuracyM: Double?,
    val source: EventSource,
    val payload: Map<String, Any?> = emptyMap(),
    val schemaVersion: Int = 1
)

/** A computed route between origin and destination. */
data class RoutePlan(
    val distanceM: Double,
    val durationS: Long,
    val polyline: List<GeoPoint>,
    val provider: String,          // "osrm" | "fallback"
    val fetchedAtMs: Long
)

/** One line of the ETA explanation shown when the viewer taps the ETA. */
data class EtaComponent(val label: String, val seconds: Long)

/** A full ETA breakdown for transparency. */
data class EtaBreakdown(
    val travelSeconds: Long,
    val breakBudgetSeconds: Long,
    val uncertaintySeconds: Long,
    val components: List<EtaComponent>
)

/**
 * The realistic-arrival forecast. Times are nullable because an overnight halt
 * with no declared restart genuinely has no known arrival time — the product
 * must say "pending morning departure" rather than invent one.
 */
data class EtaForecast(
    val lowMs: Long?,
    val highMs: Long?,
    val mostLikelyMs: Long?,
    val mode: EtaMode,
    val breakdown: EtaBreakdown?,
    val confidence: String          // "LOW" | "MEDIUM" | "HIGH"
)

/** Timestamps (epoch ms) of the last confirmed wellbeing events. */
data class WellbeingTimes(
    val waterAtMs: Long? = null,
    val foodAtMs: Long? = null,
    val toiletAtMs: Long? = null,
    val restAtMs: Long? = null,
    val fuelAtMs: Long? = null
)

/** Aggregate statistics computed at journey completion. */
data class TripSummary(
    val distanceKm: Double,
    val drivingSeconds: Long,
    val totalSeconds: Long,
    val stops: Int,
    val foodBreaks: Int,
    val waterConfirmations: Int,
    val toiletBreaks: Int,
    val restBreaks: Int,
    val fuelStops: Int,
    val teaCoffee: Int,
    val snacks: Int,
    val longestLegSeconds: Long,
    val longestBreakSeconds: Long,
    val days: Int
)

/**
 * One stage of a journey, in one mode of transport.
 *
 * Real journeys are rarely single-mode: Thrissur to Bangalore by train, then
 * Bangalore to Hyderabad by bus. Each leg carries its own mode, so every rule
 * that keys off transport (break prompts, deviation, sampling cadence, quick
 * actions) switches automatically when the traveller changes vehicle — and the
 * viewer's timeline reads as one continuous story.
 *
 * A single-mode journey is simply a journey with one leg, so there is exactly
 * one code path.
 */
data class JourneyLeg(
    val index: Int,
    val mode: String,
    val fromName: String,
    val from: GeoPoint,
    val toName: String,
    val to: GeoPoint,
    val fuelType: String? = null,
    val startedAtMs: Long? = null,
    val completedAtMs: Long? = null,
    val plannedDepartureMs: Long? = null,
    val bookingRef: String? = null,
    val seat: String? = null,
    val boardingPoint: String? = null
) {
    val started: Boolean get() = startedAtMs != null
    val completed: Boolean get() = completedAtMs != null
}
