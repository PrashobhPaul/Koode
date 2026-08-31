package com.trippulse.app.domain

/**
 * What the app asks about the vehicle, and how strictly.
 *
 * Journeys carry details that only matter for one kind of travel: a coach
 * number means nothing in a car, a fuel type means nothing on a train. Rather
 * than scatter that knowledge across the create screen, the edit sheet and the
 * PDF, each mode declares the fields it wants and how they are labelled, and
 * every surface renders from that declaration.
 *
 * Only one mode makes anything mandatory. In a private vehicle the details
 * *are* the safety information — if something goes wrong, "a silver Ertiga,
 * KL-08-AC-1234" is what a family member repeats to someone who can help, and
 * an empty field there is a gap in exactly the moment the app exists for. On
 * public transport the same fields are conveniences, and a traveller settling
 * into a seat should never be blocked by a form.
 */

/** One thing the app can ask about a vehicle or booking. */
data class DetailField(
    val key: String,
    val label: String,
    /** Fixed choices, or empty for free text. */
    val options: List<String> = emptyList(),
    val required: Boolean = false,
    /** Free text hint shown under the field. */
    val hint: String? = null,
    /** Letters allowed, or digits only. */
    val digitsOnly: Boolean = false,
    /** Uppercased as typed — registrations, coach and PNR codes always are. */
    val uppercase: Boolean = false
) {
    val isChoice: Boolean get() = options.isNotEmpty()
}

/** Keys are stable: they are persisted and read back by both the UI and the PDF. */
object DetailKeys {
    const val OPERATOR = "operator"
    const val VEHICLE_NAME = "vehicleName"
    const val VEHICLE_TYPE = "vehicleType"
    const val REGISTRATION = "registration"
    const val FUEL_TYPE = "fuelType"
    const val COACH = "coach"
    const val SEAT = "seat"
    const val PNR = "pnr"
}

/**
 * The details a mode asks for.
 *
 * Lists of operators are starting points, not gates — every one of them ends
 * in "Other", and the field accepts anything. A traveller on a bus company we
 * have never heard of must not be stuck.
 */
object TravelDetails {

    val VEHICLE_TYPES = listOf("Hatchback", "Sedan", "SUV", "MPV", "Other")
    val FUEL_TYPES = listOf("Petrol", "Diesel", "Electric", "CNG", "Hybrid")

    /**
     * Ride-hailing services. Regional on purpose: Ola and Rapido matter in
     * India, Grab across South-East Asia, Bolt across Europe and Africa.
     */
    val CAB_PROVIDERS = listOf(
        "Uber", "Ola", "Rapido", "Grab", "Bolt", "Lyft", "Careem",
        "Local taxi", "Other"
    )

    /**
     * Coach operators. A mix of state undertakings and the private operators
     * people actually name when they say which bus they are on.
     */
    val BUS_OPERATORS = listOf(
        "State transport", "KSRTC", "TNSTC", "APSRTC", "TSRTC", "MSRTC",
        "Kallada", "Orange", "YAS", "SRS", "VRL", "Parveen",
        "FlixBus", "National Express", "Greyhound", "Other"
    )

    /** Fields for [mode], in the order they should be shown. */
    fun fieldsFor(mode: String?): List<DetailField> {
        val profile = TransportCatalog.profile(mode)
        return when {
            profile.isPrivateVehicle -> privateVehicleFields()
            profile.key == TransportCatalog.CAB.key -> cabFields()
            profile.key == TransportCatalog.TRAIN.key -> trainFields()
            profile.key == TransportCatalog.BUS.key -> busFields()
            profile.key == TransportCatalog.FLIGHT.key -> flightFields()
            else -> emptyList()
        }
    }

    private fun privateVehicleFields() = listOf(
        DetailField(
            DetailKeys.VEHICLE_NAME, "Vehicle", required = true,
            hint = "What it is, in the words you'd use on the phone — \"the white Swift\""
        ),
        DetailField(DetailKeys.VEHICLE_TYPE, "Body type", options = VEHICLE_TYPES, required = true),
        DetailField(DetailKeys.FUEL_TYPE, "Fuel", options = FUEL_TYPES, required = true),
        DetailField(
            DetailKeys.REGISTRATION, "Registration", required = true,
            uppercase = true, hint = "The number someone would read out to find you"
        )
    )

    private fun cabFields() = listOf(
        DetailField(DetailKeys.OPERATOR, "Service", options = CAB_PROVIDERS),
        DetailField(DetailKeys.REGISTRATION, "Vehicle number", uppercase = true)
    )

    private fun trainFields() = listOf(
        DetailField(DetailKeys.OPERATOR, "Train number or name"),
        DetailField(DetailKeys.COACH, "Coach", uppercase = true, hint = "S3, B1, A2"),
        DetailField(DetailKeys.SEAT, "Seat or berth", uppercase = true),
        DetailField(DetailKeys.PNR, "PNR", uppercase = true)
    )

    private fun busFields() = listOf(
        DetailField(DetailKeys.OPERATOR, "Operator", options = BUS_OPERATORS),
        DetailField(DetailKeys.SEAT, "Seat", uppercase = true),
        DetailField(DetailKeys.PNR, "PNR or booking reference", uppercase = true)
    )

    private fun flightFields() = listOf(
        DetailField(DetailKeys.OPERATOR, "Flight number", uppercase = true),
        DetailField(DetailKeys.SEAT, "Seat", uppercase = true),
        DetailField(DetailKeys.PNR, "Booking reference", uppercase = true)
    )

    /** Keys still empty that [mode] insists on. Empty means good to go. */
    fun missingRequired(mode: String?, values: Map<String, String>): List<DetailField> =
        fieldsFor(mode).filter { it.required && values[it.key].isNullOrBlank() }

    fun isComplete(mode: String?, values: Map<String, String>): Boolean =
        missingRequired(mode, values).isEmpty()

    /**
     * A one-line description of the vehicle for the timeline and the PDF.
     *
     * Deliberately short and deliberately human: "Uber · KL01AB1234", "12626
     * · S3 · 42". Blank when nothing was filled in, so callers can skip the
     * line rather than print an empty label.
     */
    fun summary(mode: String?, values: Map<String, String>): String {
        val profile = TransportCatalog.profile(mode)
        val parts = if (profile.isPrivateVehicle) {
            listOf(
                values[DetailKeys.VEHICLE_NAME],
                values[DetailKeys.VEHICLE_TYPE],
                values[DetailKeys.REGISTRATION]
            )
        } else {
            listOf(
                values[DetailKeys.OPERATOR],
                values[DetailKeys.COACH],
                values[DetailKeys.SEAT]
            )
        }
        return parts.filter { !it.isNullOrBlank() }.joinToString(" · ")
    }
}
