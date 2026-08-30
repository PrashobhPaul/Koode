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
        EventTypes.ROUTE_DEVIATION -> "↩" to "Off the usual route"
        EventTypes.ROUTE_REJOINED -> "↪" to "Back on route"
        EventTypes.ARRIVAL_DETECTED -> "📍" to "Reached the destination"
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
        else -> "•" to type.lowercase().replace('_', ' ')
    }

    /**
     * The final line, preferring whatever the event itself recorded: a meal
     * event knows which meal it was, and a transport milestone ships its own
     * sentence ("Boarded the train") so no reader has to know the mode.
     */
    fun line(type: String, payload: Map<String, Any?>): Pair<String, String> {
        val (emoji, label) = base(type)
        val text = payload["text"] as? String
        return when (type) {
            EventTypes.FOOD_REPORTED -> {
                val meal = Nourishment.fromKey(payload["meal"] as? String)
                (meal?.emoji ?: emoji) to (meal?.label ?: label)
            }
            EventTypes.BOARDED, EventTypes.TRANSIT_HALTED,
            EventTypes.TRANSIT_RESUMED, EventTypes.DEBOARDED,
            EventTypes.LEG_STARTED -> emoji to (text ?: label)
            else -> emoji to (text?.let { "$label — $it" } ?: label)
        }
    }
}
