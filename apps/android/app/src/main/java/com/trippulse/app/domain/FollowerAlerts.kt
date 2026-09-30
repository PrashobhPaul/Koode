package com.trippulse.app.domain

/**
 * Which followed-journey events become a notification on a Circle member's
 * phone, and how each one maps to a stable notification id.
 *
 * Pure and device-free so the rules are unit-tested rather than reasoned about.
 * The intent (chosen by the traveller's product owner) is that the Circle sees
 * *every meaningful status update the traveller makes* — starts, breaks, meals,
 * fuel, tolls, resumes, stage changes, arrival and completion — while the raw
 * GPS churn the spec forbids as noise (moving/stopped transitions, location
 * pings, battery/network chatter) never surfaces. Delivery itself stays
 * best-effort per follower device; this only decides *what* is worth a
 * notification and gives each event a stable id.
 */
object FollowerAlerts {

    /**
     * The meaningful, de-noised set. Deliberately excludes raw movement
     * (`STOP_STARTED`/`STOP_ENDED`/`LONG_STOP`/`LOCATION_UPDATE`), house-keeping
     * (`NETWORK_*`, `ETA_UPDATED`, `BATTERY_LOW`), the going-dark family
     * (handled by the dedicated darkness/health watcher, not double-announced
     * here) and `MEDICINE` (private by default).
     */
    val NOTIFY_TYPES: Set<String> = setOf(
        EventTypes.TRIP_STARTED, EventTypes.TRIP_PAUSED, EventTypes.TRIP_RESUMED,
        EventTypes.TRIP_COMPLETED, EventTypes.ARRIVAL_DETECTED, EventTypes.DESTINATION_CHANGED,
        EventTypes.TOLL_CROSSED, EventTypes.BREAK_CHECKPOINT,
        EventTypes.WATER_REPORTED, EventTypes.FOOD_REPORTED, EventTypes.TEA_COFFEE_REPORTED,
        EventTypes.SNACK_REPORTED, EventTypes.TOILET_REPORTED, EventTypes.REST_REPORTED,
        EventTypes.FUEL_STOP, EventTypes.CHARGE_STOP,
        EventTypes.LEG_STARTED, EventTypes.BOARDED, EventTypes.TRANSIT_HALTED,
        EventTypes.TRANSIT_RESUMED, EventTypes.DEBOARDED,
        EventTypes.OVERNIGHT_CONFIRMED, EventTypes.MORNING_RESUME,
        EventTypes.QUICK_NOTE, EventTypes.PASSENGER_JOINED, EventTypes.PASSENGER_LEFT,
        EventTypes.VEHICLE_ISSUE, EventTypes.INCIDENT, EventTypes.POSSIBLE_INCIDENT,
        EventTypes.SOS_ACTIVATED, EventTypes.SOS_RESOLVED
    )

    /**
     * Wellbeing/refuel items that are logged *as part of* a break. When they
     * carry a `breakId` they belong to an aggregate `BREAK_CHECKPOINT` that
     * already notifies, so notifying them again would be the duplicate the spec
     * warns against. Standalone (public-transport) items carry no `breakId` and
     * do notify.
     */
    private val BREAK_ITEM_TYPES: Set<String> = setOf(
        EventTypes.WATER_REPORTED, EventTypes.FOOD_REPORTED, EventTypes.TEA_COFFEE_REPORTED,
        EventTypes.SNACK_REPORTED, EventTypes.TOILET_REPORTED, EventTypes.REST_REPORTED,
        EventTypes.FUEL_STOP, EventTypes.CHARGE_STOP
    )

    /** Events that should ring louder on the follower's phone. */
    fun isUrgent(type: String): Boolean =
        type == EventTypes.SOS_ACTIVATED || type == EventTypes.INCIDENT ||
            type == EventTypes.POSSIBLE_INCIDENT

    /** Whether this synced event should raise a notification on a follower. */
    fun shouldNotify(type: String, payload: Map<String, Any?>): Boolean {
        if (type !in NOTIFY_TYPES) return false
        if (EventTypes.isSensitiveByDefault(type)) return false
        // A break on a private vehicle is announced once, by the aggregate
        // BREAK_CHECKPOINT. On public transport there is no aggregate break
        // (countsAsBreak == false); there the individual items speak instead.
        if (type == EventTypes.BREAK_CHECKPOINT) return payload["countsAsBreak"] != false
        if (type in BREAK_ITEM_TYPES && payload["breakId"] != null) return false
        return true
    }

    /**
     * A stable notification id for an event, so distinct events stack in the
     * tray rather than overwrite one another, while a re-seen event (or a break
     * that reappears as it closes) replaces itself instead of duplicating.
     *
     * All aggregate break events for one stop share the same id via their
     * `breakId`, so the "open" break and its later "closed" form collapse into
     * a single, updating notification. Ids sit in a band clear of the app's
     * fixed notification ids.
     */
    fun notificationId(ref: String, type: String, eventTimeMs: Long, payload: Map<String, Any?>): Int {
        val key = if (type == EventTypes.BREAK_CHECKPOINT)
            "$ref|BREAK|${payload["breakId"] ?: eventTimeMs}"
        else
            "$ref|$type|$eventTimeMs"
        return 5_000 + (key.hashCode() and 0x7FFFFFFF) % 90_000
    }
}
