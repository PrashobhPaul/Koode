package com.trippulse.app.domain

/**
 * Which illustration stands for a thing, by name. One table for the whole
 * product: the app maps these names to bundled drawables (KoodeArt) and the
 * web viewer to `web/art/<name>.webp`, so a meal, a toilet stop or an auto
 * looks the same on every screen. Anything without a picture returns null
 * and keeps its emoji.
 */
object Pictures {

    // ---- the names (one file per name in drawable-nodpi and web/art) -------
    const val HOME = "home"
    const val BUILDING = "building"
    const val STAY = "stay"
    const val RESTAURANT = "restaurant"
    const val TOILET = "toilet"
    const val FUEL = "fuel"
    const val WALK = "walk"
    const val FOOD = "food"
    const val WATER = "water"
    const val REST = "rest"

    /** Every name, for checks that a file exists for each. */
    val ALL: List<String> = listOf(
        HOME, BUILDING, STAY, RESTAURANT, TOILET, FUEL, WALK, FOOD, WATER, REST,
        "car", "bike", "cab", "auto", "metro", "train", "bus", "flight", "ship", "cycle", "ferry", "cruise"
    )

    /** A travel mode's picture. Every mode in the catalog has one. */
    fun mode(key: String?): String? = when (TransportCatalog.profile(key).key) {
        "CAR" -> "car"
        "BIKE" -> "bike"
        "CAB" -> "cab"
        "AUTO" -> "auto"
        "BUS" -> "bus"
        "METRO" -> "metro"
        "TRAIN" -> "train"
        "FLIGHT" -> "flight"
        "SHIP" -> "cruise"
        "FERRY" -> "ferry"
        "CYCLE" -> "cycle"
        "WALK" -> WALK
        else -> null
    }

    /** Pictures drawn facing left, mirrored where they must face the destination. */
    fun modeFacesLeft(key: String?): Boolean = TransportCatalog.profile(key).key in setOf("BUS", "METRO", "AUTO")

    /**
     * The vehicle seen from the platform, nose to tail, for riding the
     * progress line between the start and the flag: a long, low picture that
     * sits on the track the way the real thing sits on its rails. Modes
     * without one ride their ordinary picture. Drawn facing the same way as
     * the mode's picture, so [modeFacesLeft] applies to both.
     */
    fun side(key: String?): String? = when (TransportCatalog.profile(key).key) {
        "METRO" -> "metro-side"
        else -> null
    }

    /** Every side view, for checks that a file exists for each. */
    val SIDES: List<String> = listOf("metro-side")

    /**
     * A timeline entry's picture. A logged meal is the plate of food; a break
     * that included a meal is the restaurant it was taken at. Tea and a snack
     * keep their own cup and biscuit rather than borrowing the plate.
     */
    fun event(type: String, payload: Map<String, Any?> = emptyMap()): String? = when (type) {
        EventTypes.FUEL_STOP, EventTypes.CHARGE_STOP -> FUEL
        EventTypes.FOOD_REPORTED, EventTypes.FOOD_ACKNOWLEDGED -> FOOD
        EventTypes.TOILET_REPORTED -> TOILET
        EventTypes.WATER_REPORTED, EventTypes.WATER_ACKNOWLEDGED -> WATER
        EventTypes.REST_REPORTED -> REST
        EventTypes.OVERNIGHT_CONFIRMED, EventTypes.HALT_CONFIRMED -> STAY
        EventTypes.DEBOARDED -> WALK
        EventTypes.BREAK_CHECKPOINT -> breakStop(payload)
        // A change of vehicle shows the vehicle changed to; a stage, the one it is made on.
        EventTypes.TRAVEL_MODE_CHANGED -> (payload["toMode"] as? String)?.let { mode(it) }
        EventTypes.LEG_STARTED -> (payload["mode"] as? String)?.let { mode(it) }
        else -> null
    }

    /**
     * One picture for a break, by the main reason for it: a halt for food,
     * then fuel, a rest, a toilet stop, a drink of water. A break with
     * nothing logged yet is a rest.
     */
    fun breakStop(payload: Map<String, Any?>): String {
        fun has(key: String) = payload[key] == true
        return when {
            has("food") || has("tea") || has("snack") -> RESTAURANT
            has("fuel") || has("charge") -> FUEL
            has("rest") -> REST
            has("toilet") -> TOILET
            has("water") -> WATER
            else -> REST
        }
    }

    private val HOME_WORDS = listOf("home", "house", "veedu", "ghar", "amma", "achan", "parents", "tharavad")
    private val STAY_WORDS = listOf("resort", "hotel", "homestay", "lodge", "inn", "guest house", "guesthouse", "hostel", "villa", "stay")
    private val FOOD_WORDS = listOf("restaurant", "cafe", "café", "dhaba", "canteen", "mess", "eatery", "bakery", "food court")
    private val FUEL_WORDS = listOf("petrol", "fuel", "pump", "bunk", "charging", "ev station")

    /**
     * A saved place by what its name says it is: a house for home, a resort
     * for somewhere to stay, a restaurant, a fuel station, and an office or
     * apartment block for everywhere else.
     */
    fun place(name: String): String {
        val n = name.lowercase()
        return when {
            HOME_WORDS.any { it in n } -> HOME
            STAY_WORDS.any { it in n } -> STAY
            FOOD_WORDS.any { it in n } -> RESTAURANT
            FUEL_WORDS.any { it in n } -> FUEL
            else -> BUILDING
        }
    }
}
