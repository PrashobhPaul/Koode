package com.trippulse.app.domain.fastag

/**
 * The vehicle kinds a traveller can add to their profile. Deliberately tiny —
 * Koode tracks a personal garage (a car and a bike), not a fleet.
 */
enum class VehicleKind {
    CAR, BIKE;

    companion object {
        fun fromKey(s: String?): VehicleKind =
            if (s?.uppercase() == BIKE.name) BIKE else CAR
    }
}

/**
 * How a vehicle's FASTag balance is tracked, when the user chose to track one.
 *
 *  - [NONE]        — no FASTag details recorded (the default; always optional).
 *  - [ANNUAL_PASS] — a fixed-price annual pass; the balance is *crossings left*.
 *  - [AMOUNT]      — an ordinary prepaid FASTag; the balance is *money left*.
 *
 * Which one a vehicle uses is the user's own statement, never inferred. A
 * qualifying toll SMS decrements the matching vehicle in that vehicle's own
 * currency of balance — a crossing off a pass, money off an amount.
 */
enum class FastagMode {
    NONE, ANNUAL_PASS, AMOUNT;

    companion object {
        fun fromKey(s: String?): FastagMode = when (s?.uppercase()) {
            ANNUAL_PASS.name -> ANNUAL_PASS
            AMOUNT.name -> AMOUNT
            else -> NONE
        }
    }
}
