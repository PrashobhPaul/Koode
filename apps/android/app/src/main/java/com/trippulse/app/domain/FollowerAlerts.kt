package com.trippulse.app.domain

/**
 * Which journey events become a notification on a Journey Follower's phone,
 * and how each one maps to a stable notification id.
 *
 * Pure and device-free so the rules are unit-tested rather than reasoned about.
 * Followers are *informed*, not escalated to: they see every meaningful status
 * update and plan change the traveller makes — starts, breaks, meals, tolls,
 * confirmed halts, destination and mode changes, arrival — while GPS churn,
 * short stops and the traveller's own coaching never surface. Delivery stays
 * best-effort per follower device; this only decides *what* is worth a
 * notification and gives each event a stable id.
 */
object FollowerAlerts {

    /**
     * How much an event matters to the people following a journey.
     *
     *  0 INTERNAL        — GPS churn, small ETA moves, short stops: nobody is told.
     *  1 TRAVELLER_ONLY  — coaching suggestions: the traveller's business alone.
     *  2 MEANINGFUL      — started, a logged break, a toll, a confirmed halt, arrived.
     *  3 IMPORTANT       — the plan changed: destination, mode, halt, a big ETA shift.
     *  4 SAFETY_CRITICAL — SOS and incidents; they bypass every suppression.
     */
    enum class Level(val rank: Int) { INTERNAL(0), TRAVELLER_ONLY(1), MEANINGFUL(2), IMPORTANT(3), SAFETY_CRITICAL(4) }

    private val CRITICAL: Set<String> = setOf(
        EventTypes.SOS_ACTIVATED, EventTypes.INCIDENT, EventTypes.POSSIBLE_INCIDENT
    )

    private val IMPORTANT: Set<String> = setOf(
        EventTypes.DESTINATION_CHANGED, EventTypes.TRAVEL_MODE_CHANGED,
        EventTypes.PLANNED_HALT_CREATED, EventTypes.PLANNED_HALT_CHANGED, EventTypes.PLANNED_HALT_CANCELLED,
        EventTypes.ETA_SIGNIFICANTLY_CHANGED, EventTypes.JOURNEY_PLAN_REVISED,
        EventTypes.HALT_RESUMED, EventTypes.MORNING_RESUME, EventTypes.SOS_RESOLVED
    )

    private val TRAVELLER_ONLY: Set<String> = setOf(
        EventTypes.WELLBEING_NUDGE, EventTypes.HALT_SUGGESTED,
        EventTypes.WATER_NUDGE, EventTypes.WATER_REMINDER, EventTypes.WATER_ACKNOWLEDGED,
        EventTypes.FOOD_NUDGE, EventTypes.FOOD_REMINDER, EventTypes.FOOD_ACKNOWLEDGED,
        EventTypes.BREAK_NUDGE, EventTypes.BREAK_REMINDER, EventTypes.BREAK_ACKNOWLEDGED,
        // Closing is the traveller's business until they approve the journey;
        // followers then hear once, from TRIP_COMPLETED.
        EventTypes.JOURNEY_CLOSE_PROMPTED, EventTypes.JOURNEY_REOPENED, EventTypes.JOURNEY_CLOSED,
        EventTypes.JOURNEY_AUTO_CLOSED, EventTypes.JOURNEY_REVIEW_STARTED,
        EventTypes.JOURNEY_ANALYTICS_APPROVED, EventTypes.JOURNEY_FINALIZED,
        EventTypes.TRAVELLER_CONFIRMED_SAFE, EventTypes.TRAVEL_EXPENSES_APPROVED
    )

    /**
     * The meaningful, de-noised set (level 2 and above). Deliberately excludes
     * raw movement (`STOP_STARTED`/`STOP_ENDED`/`LONG_STOP`/`LOCATION_UPDATE`),
     * house-keeping (`NETWORK_*`, `ETA_UPDATED`, `BATTERY_LOW`), the going-dark
     * family (handled by the dedicated darkness/health watcher, not
     * double-announced here) and `MEDICINE` (private by default).
     */
    val NOTIFY_TYPES: Set<String> = setOf(
        EventTypes.TRIP_STARTED, EventTypes.TRIP_PAUSED, EventTypes.TRIP_RESUMED,
        EventTypes.TRIP_COMPLETED, EventTypes.ARRIVAL_DETECTED,
        EventTypes.TOLL_CROSSED, EventTypes.BREAK_CHECKPOINT,
        EventTypes.WATER_REPORTED, EventTypes.FOOD_REPORTED, EventTypes.TEA_COFFEE_REPORTED,
        EventTypes.SNACK_REPORTED, EventTypes.TOILET_REPORTED, EventTypes.REST_REPORTED,
        EventTypes.FUEL_STOP, EventTypes.CHARGE_STOP,
        EventTypes.LEG_STARTED, EventTypes.BOARDED, EventTypes.TRANSIT_HALTED,
        EventTypes.TRANSIT_RESUMED, EventTypes.DEBOARDED,
        EventTypes.OVERNIGHT_CONFIRMED, EventTypes.HALT_CONFIRMED, EventTypes.HALT_CANCELLED,
        EventTypes.QUICK_NOTE, EventTypes.PASSENGER_JOINED, EventTypes.PASSENGER_LEFT,
        EventTypes.VEHICLE_ISSUE,
        // A need still unresolved after a suggestion and a reminder, and the
        // periodic update (only written when something changed).
        EventTypes.WELLBEING_ALERT, EventTypes.JOURNEY_UPDATE,
        // The approved report, once stored: it replaces the completion notice.
        EventTypes.JOURNEY_REPORT_AVAILABLE
    ) + IMPORTANT + CRITICAL

    fun level(type: String, payload: Map<String, Any?> = emptyMap()): Level = when {
        type in CRITICAL -> Level.SAFETY_CRITICAL
        type in IMPORTANT -> Level.IMPORTANT
        type in TRAVELLER_ONLY -> Level.TRAVELLER_ONLY
        shouldNotify(type, payload) -> Level.MEANINGFUL
        else -> Level.INTERNAL
    }

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

    /**
     * The notification category an alert is filed under on the follower's
     * phone, so each kind can be switched off on its own (Android channels)
     * without touching the others. Safety alerts always stay separate.
     */
    enum class Category { SAFETY, JOURNEY, WELLBEING, COMPLETION }

    fun category(type: String): Category = when (type) {
        in CRITICAL -> Category.SAFETY
        EventTypes.TRIP_COMPLETED, EventTypes.JOURNEY_REPORT_AVAILABLE -> Category.COMPLETION
        EventTypes.WATER_REPORTED, EventTypes.FOOD_REPORTED, EventTypes.TEA_COFFEE_REPORTED,
        EventTypes.SNACK_REPORTED, EventTypes.TOILET_REPORTED, EventTypes.REST_REPORTED,
        EventTypes.BREAK_CHECKPOINT, EventTypes.WELLBEING_ALERT -> Category.WELLBEING
        else -> Category.JOURNEY
    }

    /** Events that should ring louder on the follower's phone. */
    fun isUrgent(type: String): Boolean = type in CRITICAL

    /** Whether this synced event should raise a notification on a follower. */
    fun shouldNotify(type: String, payload: Map<String, Any?>): Boolean {
        if (type !in NOTIFY_TYPES) return false
        if (EventTypes.isSensitiveByDefault(type)) return false
        // A break on a private vehicle is announced once, by the aggregate
        // BREAK_CHECKPOINT. On public transport there is no aggregate break
        // (countsAsBreak == false); there the individual items speak instead.
        // A correction to a break (re-timed or taken back) is not news: the
        // timeline shows the corrected line, nobody's phone buzzes for it.
        if (type == EventTypes.BREAK_CHECKPOINT) {
            return payload["countsAsBreak"] != false && payload["revised"] != true && payload["removed"] != true
        }
        if (type in BREAK_ITEM_TYPES && payload["breakId"] != null) return false
        // A stage change that is also a travel-mode change is announced once,
        // by TRAVEL_MODE_CHANGED.
        if (type == EventTypes.LEG_STARTED && payload["announcedAs"] != null) return false
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
    /**
     * Identity of one real-world event as seen by one follower. The server
     * push and the in-app follow service can both deliver the same event, and
     * the server re-sends anything it could not confirm — this key is how the
     * device shows it once regardless. Unlike [notificationId] it is not
     * collapsed per break: a break's later "closed" event is new information
     * and should update the tray entry.
     */
    fun dedupKey(ref: String, type: String, eventTimeMs: Long): String = "$ref|$type|$eventTimeMs"

    fun notificationId(ref: String, type: String, eventTimeMs: Long, payload: Map<String, Any?>): Int {
        val key = when (type) {
            EventTypes.BREAK_CHECKPOINT -> "$ref|BREAK|${payload["breakId"] ?: eventTimeMs}"
            // One end-of-journey notification per journey: the report, when it
            // is ready, updates the completion notice rather than ringing again.
            EventTypes.TRIP_COMPLETED, EventTypes.JOURNEY_REPORT_AVAILABLE -> "$ref|JOURNEY_END"
            else -> "$ref|$type|$eventTimeMs"
        }
        return 5_000 + (key.hashCode() and 0x7FFFFFFF) % 90_000
    }
}
