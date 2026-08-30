package com.trippulse.app.domain

import com.trippulse.app.core.LocationCadence

/**
 * A single tap the traveller can make from the journey screen.
 *
 * Quick actions are data, not code: each transport mode declares the sentences
 * that make sense for it, and the UI just renders whatever the profile hands
 * over. Adding "Ferry boarded" later is a one-line change to a catalog entry.
 */
data class QuickAction(
    val eventType: String,
    val emoji: String,
    val label: String,
    /** Text stored on the event so the timeline reads like a sentence. */
    val timelineText: String,
    /** Safe to offer while the vehicle is moving? (Driver safety, private modes.) */
    val availableWhileMoving: Boolean = true
)

/**
 * How much a mode of transport wants the app to observe it.
 *
 * A car needs stop detection, refuelling questions and route-deviation alerts.
 * A train needs none of them: it halts at every station, it never refuels, and
 * "off the usual route" is meaningless on rails. Encoding that once, here,
 * keeps the rest of the app free of `if (mode == "TRAIN")` scattering.
 */
data class TransportProfile(
    val key: String,
    val label: String,
    val emoji: String,

    /** The traveller is operating the vehicle: fuel, driving-fatigue rules apply. */
    val isPrivateVehicle: Boolean,
    /** Travels on roads, so a road route polyline is meaningful. */
    val isRoadMode: Boolean,

    /** Confirmed stops become break prompts and stop events. */
    val stopPromptsEnabled: Boolean,
    /** Route-deviation detection produces useful signal for this mode. */
    val deviationEnabled: Boolean,
    /**
     * A wellbeing log (food, tea, water) also counts as a *break* — i.e. the
     * vehicle was halted for it. True only for private vehicles: a passenger
     * eating on a train has not stopped the journey.
     */
    val wellbeingIsBreak: Boolean,
    /** Being offline for long stretches is expected (flights, remote rail). */
    val expectsOfflineStretches: Boolean,

    /** Default sampling cadence before the user's own setting is applied. */
    val defaultCadence: LocationCadence,
    /** Quick actions offered on the journey screen for this mode. */
    val quickActions: List<QuickAction>,
    /** Wording used in viewer-facing sentences: "Travelling by train". */
    val travellingSuffix: String,
    /** What the boarding-point field should be called at journey creation. */
    val boardingPointLabel: String
) {
    /** Fuel / charging questions only ever make sense for private vehicles. */
    val asksAboutFuel: Boolean get() = isPrivateVehicle
}

/**
 * The catalog of supported modes.
 *
 * Everything the app does differently per mode is a lookup here. New modes are
 * additive — unknown keys fall back to [CAR], so a journey created by a newer
 * build never breaks an older viewer.
 */
object TransportCatalog {

    /** Road modes where the traveller is at the wheel. */
    val PRIVATE_KEYS: Set<String> = setOf("CAR", "BIKE")

    private fun boardingActions(vehicle: String, haltNoun: String): List<QuickAction> = listOf(
        QuickAction(EventTypes.BOARDED, "🎫", "Boarded", "Boarded the $vehicle"),
        QuickAction(EventTypes.TRANSIT_HALTED, "⏸", "Halted", "$haltNoun halted"),
        QuickAction(EventTypes.TRANSIT_RESUMED, "▶", "Moving again", "$haltNoun is moving again"),
        QuickAction(EventTypes.DEBOARDED, "🚶", "Got off", "Deboarded the $vehicle")
    )

    private val privateActions: List<QuickAction> = listOf(
        QuickAction(EventTypes.PASSENGER_JOINED, "👤", "Passenger joined", "A passenger joined"),
        QuickAction(EventTypes.PASSENGER_LEFT, "👋", "Passenger left", "A passenger left"),
        QuickAction(EventTypes.MEDICINE, "💊", "Medicine", "Medicine taken"),
        QuickAction(EventTypes.VEHICLE_ISSUE, "🔧", "Vehicle issue", "Vehicle needed attention", availableWhileMoving = false)
    )

    val CAR = TransportProfile(
        key = "CAR", label = "Car", emoji = "🚗",
        isPrivateVehicle = true, isRoadMode = true,
        stopPromptsEnabled = true, deviationEnabled = true,
        wellbeingIsBreak = true, expectsOfflineStretches = false,
        defaultCadence = LocationCadence.BALANCED,
        quickActions = privateActions,
        travellingSuffix = " by car", boardingPointLabel = "Starting point"
    )

    val BIKE = CAR.copy(
        key = "BIKE", label = "Bike", emoji = "🏍",
        travellingSuffix = " by bike"
    )

    /** A taxi or ride-hail: road-borne, but the traveller isn't driving. */
    val CAB = TransportProfile(
        key = "CAB", label = "Cab / taxi", emoji = "🚕",
        isPrivateVehicle = false, isRoadMode = true,
        stopPromptsEnabled = false, deviationEnabled = true,
        wellbeingIsBreak = false, expectsOfflineStretches = false,
        defaultCadence = LocationCadence.BALANCED,
        quickActions = listOf(
            QuickAction(EventTypes.BOARDED, "🎫", "Got in", "Got into the cab"),
            QuickAction(EventTypes.TRANSIT_HALTED, "⏸", "Halted", "Cab halted"),
            QuickAction(EventTypes.TRANSIT_RESUMED, "▶", "Moving again", "Cab is moving again"),
            QuickAction(EventTypes.DEBOARDED, "🚶", "Got out", "Got out of the cab")
        ),
        travellingSuffix = " by cab", boardingPointLabel = "Pickup point"
    )

    val BUS = TransportProfile(
        key = "BUS", label = "Bus", emoji = "🚌",
        isPrivateVehicle = false, isRoadMode = true,
        stopPromptsEnabled = false, deviationEnabled = false,
        wellbeingIsBreak = false, expectsOfflineStretches = false,
        defaultCadence = LocationCadence.SAVER,
        quickActions = boardingActions("bus", "Bus"),
        travellingSuffix = " by bus", boardingPointLabel = "Boarding point"
    )

    val TRAIN = TransportProfile(
        key = "TRAIN", label = "Train", emoji = "🚆",
        isPrivateVehicle = false, isRoadMode = false,
        stopPromptsEnabled = false, deviationEnabled = false,
        wellbeingIsBreak = false, expectsOfflineStretches = true,
        defaultCadence = LocationCadence.SAVER,
        quickActions = boardingActions("train", "Train"),
        travellingSuffix = " by train", boardingPointLabel = "Railway station"
    )

    val FLIGHT = TransportProfile(
        key = "FLIGHT", label = "Flight", emoji = "✈️",
        isPrivateVehicle = false, isRoadMode = false,
        stopPromptsEnabled = false, deviationEnabled = false,
        wellbeingIsBreak = false, expectsOfflineStretches = true,
        defaultCadence = LocationCadence.SAVER,
        quickActions = listOf(
            QuickAction(EventTypes.BOARDED, "🎫", "Boarded", "Boarded the flight"),
            QuickAction(EventTypes.TRANSIT_HALTED, "⏸", "Delayed", "Flight is delayed"),
            QuickAction(EventTypes.TRANSIT_RESUMED, "🛫", "Took off", "Flight has taken off"),
            QuickAction(EventTypes.DEBOARDED, "🛬", "Landed", "Landed and off the flight")
        ),
        travellingSuffix = " by flight", boardingPointLabel = "Airport"
    )

    /** Ordered for the mode picker: private first, then public transport. */
    val ALL: List<TransportProfile> = listOf(CAR, BIKE, CAB, BUS, TRAIN, FLIGHT)

    fun profile(key: String?): TransportProfile =
        ALL.firstOrNull { it.key == key } ?: CAR

    fun isPrivate(key: String?): Boolean = profile(key).isPrivateVehicle

    fun emoji(key: String?): String = profile(key).emoji

    fun label(key: String?): String = profile(key).label
}
