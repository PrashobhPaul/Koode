package com.trippulse.app.domain

/**
 * Turns an event into the sentence a person reads.
 *
 * This lives in the domain rather than in a screen because three different
 * places need the exact same words: the traveller's timeline, the follower's
 * timeline, and the exported PDF. If any of them phrased an event differently,
 * the document someone was sent would not match the screen they watched — and
 * on this app that is the difference between reassuring and unsettling.
 */
object EventNarrator {

    /** The emoji and neutral label for a type, before any payload is applied. */
    fun base(type: String): Pair<String, String> = when (type) {
        EventTypes.TRIP_STARTED -> "🚦" to "Journey started"
        EventTypes.TRIP_PAUSED -> "⏸" to "Journey paused"
        EventTypes.TRIP_RESUMED -> "▶" to "Journey resumed"
        EventTypes.TRIP_COMPLETED -> "🏁" to "Journey ended"
        EventTypes.DESTINATION_CHANGED -> "🧭" to "Destination changed"
        EventTypes.STOP_STARTED -> "🅿" to "Stopped"
        EventTypes.STOP_ENDED -> "▶" to "On the move again"
        EventTypes.LONG_STOP -> "⏳" to "Long stop"
        EventTypes.TOLL_CROSSED -> "🛣" to "Toll crossed"
        EventTypes.ARRIVAL_DETECTED -> "📍" to "Arrived near the destination"
        EventTypes.BREAK_CHECKPOINT -> "✅" to "Break logged"
        EventTypes.WATER_REPORTED -> "💧" to "Water"
        EventTypes.FOOD_REPORTED -> "🍛" to "Food"
        EventTypes.TEA_COFFEE_REPORTED -> "☕" to "Tea / coffee"
        EventTypes.SNACK_REPORTED -> "🍪" to "Snack"
        EventTypes.TOILET_REPORTED -> "🚻" to "Toilet"
        EventTypes.REST_REPORTED -> "😴" to "Rest"
        EventTypes.FUEL_STOP -> "⛽" to "Refuelled"
        EventTypes.CHARGE_STOP -> "🔌" to "Charged"
        EventTypes.OVERNIGHT_CONFIRMED -> "🌙" to "Overnight stay"
        EventTypes.MORNING_RESUME -> "🌅" to "Back on the road"
        EventTypes.QUICK_NOTE -> "📝" to "Note"
        EventTypes.DEVICE_SHUTDOWN -> "🔌" to "Phone switched off"
        EventTypes.DEVICE_BACK_ONLINE -> "🔆" to "Phone back online"
        EventTypes.SIM_CHANGED -> "⚠️" to "The SIM in this phone was changed or removed"
        EventTypes.PASSENGER_JOINED -> "👤" to "Passenger joined"
        EventTypes.PASSENGER_LEFT -> "👋" to "Passenger left"
        EventTypes.MEDICINE -> "💊" to "Medicine recorded"
        EventTypes.VEHICLE_ISSUE -> "🔧" to "Vehicle issue"
        EventTypes.INCIDENT -> "⚠" to "Incident"
        EventTypes.POSSIBLE_INCIDENT -> "⚠" to "Possible incident"
        EventTypes.SOS_ACTIVATED -> "🚨" to "SOS activated"
        EventTypes.SOS_RESOLVED -> "✅" to "SOS resolved"
        EventTypes.BATTERY_LOW -> "🔋" to "Phone battery low"
        EventTypes.BOARDED -> "🎫" to "Boarded"
        EventTypes.TRANSIT_HALTED -> "⏸" to "Halted"
        EventTypes.TRANSIT_RESUMED -> "▶" to "Moving again"
        EventTypes.DEBOARDED -> "🚶" to "Got off"
        EventTypes.LEG_STARTED -> "🧭" to "Next stage started"
        EventTypes.LEG_COMPLETED -> "✅" to "Stage completed"
        EventTypes.WELLBEING_ALERT -> "💬" to "Wellbeing update"
        EventTypes.WELLBEING_NUDGE -> "💡" to "Wellbeing suggestion"
        EventTypes.JOURNEY_UPDATE -> "🧭" to "Journey update"
        EventTypes.WATER_NUDGE, EventTypes.WATER_REMINDER -> "💧" to "Water suggested"
        EventTypes.FOOD_NUDGE, EventTypes.FOOD_REMINDER -> "🍽" to "Food suggested"
        EventTypes.BREAK_NUDGE, EventTypes.BREAK_REMINDER -> "☕" to "Break suggested"
        EventTypes.WATER_ACKNOWLEDGED -> "💧" to "Had water"
        EventTypes.FOOD_ACKNOWLEDGED -> "🍽" to "Ate something"
        EventTypes.BREAK_ACKNOWLEDGED -> "☕" to "Taking a break"
        EventTypes.HALT_SUGGESTED -> "🛏" to "Halt suggested"
        EventTypes.HALT_CONFIRMED -> "🛏" to "Halting"
        EventTypes.HALT_CANCELLED -> "▶" to "Halt cancelled"
        EventTypes.HALT_RESUMED -> "🌅" to "Resumed after the halt"
        EventTypes.TRAVEL_MODE_CHANGED -> "🔁" to "Travel mode changed"
        EventTypes.PLANNED_HALT_CREATED -> "🗓" to "Halt planned"
        EventTypes.PLANNED_HALT_CHANGED -> "🗓" to "Planned halt changed"
        EventTypes.PLANNED_HALT_CANCELLED -> "🗓" to "Planned halt cancelled"
        EventTypes.ETA_SIGNIFICANTLY_CHANGED -> "🕒" to "Arrival time changed"
        EventTypes.JOURNEY_PLAN_REVISED -> "🧭" to "Journey plan updated"
        EventTypes.JOURNEY_CLOSE_PROMPTED -> "🏁" to "Asked to close the journey"
        EventTypes.JOURNEY_REOPENED -> "▶" to "Still travelling"
        EventTypes.JOURNEY_CLOSED -> "🏁" to "Journey closed"
        EventTypes.JOURNEY_AUTO_CLOSED -> "🏁" to "Journey closed automatically"
        EventTypes.JOURNEY_REVIEW_STARTED -> "📝" to "Journey review started"
        EventTypes.JOURNEY_ANALYTICS_APPROVED -> "✅" to "Journey report approved"
        EventTypes.JOURNEY_FINALIZED -> "✅" to "Journey finalized"
        EventTypes.TRAVELLER_CONFIRMED_SAFE -> "💚" to "Confirmed arriving safely"
        EventTypes.TRAVEL_EXPENSES_APPROVED -> "₹" to "Expenses confirmed"
        else -> "•" to type.lowercase().replace('_', ' ')
    }

    /**
     * The final line, preferring whatever the event itself recorded: a meal
     * event knows which meal it was, and a transport milestone ships its own
     * sentence ("Boarded the train") so no reader has to know the mode.
     */
    fun line(type: String, payload: Map<String, Any?>): Pair<String, String> {
        if (type == EventTypes.BREAK_CHECKPOINT && payload["countsAsBreak"] != false && payload.containsKey("breakId")) {
            return "✅" to BreakTimeline.describe(payload)
        }
        if (type == EventTypes.TRIP_STARTED && payload["startedEarlier"] == true) {
            val km = ((payload["estimatedDistanceBeforeTrackingM"] as? Number)?.toDouble() ?: 0.0) / 1000.0
            return "🚗" to (if (km >= 1) "Journey started · about %.0f km before tracking began (estimated)".format(km)
                else "Journey started · logged later")
        }
        val (emoji, label) = base(type)
        val text = payload["text"] as? String
        return when (type) {
            EventTypes.FOOD_REPORTED -> {
                val meal = Nourishment.fromKey(payload["meal"] as? String)
                (meal?.emoji ?: emoji) to (meal?.label ?: label)
            }
            EventTypes.BOARDED, EventTypes.TRANSIT_HALTED,
            EventTypes.TRANSIT_RESUMED, EventTypes.DEBOARDED,
            EventTypes.LEG_STARTED,
            // These carry a complete sentence including the battery level, and
            // prefixing the label would give "Phone switched off — Phone
            // switched off — battery 74%".
            EventTypes.DEVICE_SHUTDOWN, EventTypes.DEVICE_BACK_ONLINE,
            EventTypes.SIM_CHANGED,
            // The toll event ships its own complete sentence ("Toll crossed —
            // Paliyekkara Toll Plaza"); prefixing the label would double it.
            EventTypes.TOLL_CROSSED,
            // The coach and the hourly update write complete sentences too.
            EventTypes.WELLBEING_ALERT, EventTypes.JOURNEY_UPDATE,
            EventTypes.WELLBEING_NUDGE,
            // Halts and plan changes are written as whole, neutral sentences.
            EventTypes.HALT_CONFIRMED, EventTypes.HALT_CANCELLED, EventTypes.HALT_RESUMED,
            EventTypes.DESTINATION_CHANGED, EventTypes.TRAVEL_MODE_CHANGED,
            EventTypes.PLANNED_HALT_CREATED, EventTypes.PLANNED_HALT_CHANGED, EventTypes.PLANNED_HALT_CANCELLED,
            EventTypes.ETA_SIGNIFICANTLY_CHANGED, EventTypes.JOURNEY_PLAN_REVISED,
            // Start and completion carry "Prashobh started a journey to Thrissur."
            EventTypes.TRIP_STARTED, EventTypes.TRIP_COMPLETED,
            EventTypes.ARRIVAL_DETECTED, EventTypes.JOURNEY_AUTO_CLOSED -> emoji to (text ?: label)
            else -> emoji to (text?.let { "$label — $it" } ?: label)
        }
    }
}
