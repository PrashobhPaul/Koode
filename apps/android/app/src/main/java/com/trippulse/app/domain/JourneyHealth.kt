package com.trippulse.app.domain

/**
 * Journey Health — the product's core idea. Koode doesn't ask the family to
 * monitor the traveller; it evaluates the journey continuously and says, in
 * one glance, whether anything deserves attention:
 *
 *   🟢 NORMAL     Journey progressing normally.
 *   🟡 ATTENTION  Something unusual is happening (late-night travel, long
 *                 spell without a logged break, low phone battery, off the
 *                 usual route, updates getting stale…).
 *   🔴 CONCERN    Potentially significant (SOS, no location updates,
 *                 unusually long unexplained stop).
 *
 * Positioning note: this is deliberately NOT medical. We never claim to know
 * the traveller's state — only when something was last *logged* and what the
 * journey data shows. Wording stays factual: "Last logged food: 5h ago",
 * never "nutritional deficiency detected".
 */
object JourneyHealth {

    enum class Level { NORMAL, ATTENTION, CONCERN }

    data class Report(
        val level: Level,
        val headline: String,
        val reasons: List<String>
    )

    data class Inputs(
        val nowMs: Long,
        val journey: String?,            // JourneyStatus name from live state
        val freshness: Freshness,
        val sosActive: Boolean = false,
        val deviationActive: Boolean = false,
        val batteryPct: Int? = null,
        val foodAtMs: Long? = null,
        val waterAtMs: Long? = null,
        val lastBreakEndAtMs: Long? = null,
        val stopStartedAtMs: Long? = null,
        val drivingSinceMs: Long? = null,
        val overnightType: String? = null,   // set when the traveller confirmed an overnight halt
        val startedAtMs: Long? = null,
        val localHour: Int,                   // viewer-local hour 0..23, for late-night travel
        /** Private vehicle (car/bike): continuous-driving-without-a-break
         *  applies. On a bus/train/flight the traveller isn't driving, so
         *  that rule is silenced. */
        val privateVehicle: Boolean = true,
        /** Flight rule: while a flight is in the air the phone is offline BY
         *  DESIGN (flight mode) — the caller sets this during the expected
         *  flying window so silence reads as "in flight", not as a concern. */
        val offlineExpected: Boolean = false
    )

    // thresholds (minutes) — journey-planning heuristics, not medical advice
    private const val FOOD_GAP_MIN = 330L        // 5.5 h without a logged meal
    private const val WATER_GAP_MIN = 180L       // 3 h without a logged water break
    private const val CONTINUOUS_DRIVE_MIN = 210L
    private const val UNEXPLAINED_STOP_MIN = 90L
    private const val LOW_BATTERY_PCT = 25

    fun evaluate(i: Inputs): Report {
        // terminal states are always calm
        if (i.journey == JourneyStatus.ARRIVED.name) {
            return Report(Level.NORMAL, "Arrived safely", emptyList())
        }
        if (i.journey == JourneyStatus.COMPLETED.name) {
            return Report(Level.NORMAL, "Journey completed", emptyList())
        }

        val concern = ArrayList<String>()
        val attention = ArrayList<String>()

        // ---- concern ----
        if (i.sosActive) concern.add("SOS is active")
        if (i.freshness == Freshness.OFFLINE && !i.offlineExpected) {
            concern.add("No location updates for a while — could be network coverage")
        }
        val stoppedMin = i.stopStartedAtMs?.let { (i.nowMs - it) / 60_000 }
        val stoppedStates = setOf(JourneyStatus.STOPPED.name, JourneyStatus.LONG_STOP.name)
        if (i.journey in stoppedStates && i.overnightType == null &&
            stoppedMin != null && stoppedMin >= UNEXPLAINED_STOP_MIN
        ) {
            concern.add("Stopped for ${stoppedMin / 60}h ${stoppedMin % 60}m without a logged reason")
        }

        // ---- attention ----
        if (i.freshness == Freshness.STALE) attention.add("Updates are arriving slowly")
        if (i.deviationActive) attention.add("Off the usual route")
        i.batteryPct?.let { if (it <= LOW_BATTERY_PCT) attention.add("Traveller's phone battery is at $it%") }

        val activeSinceMin = i.startedAtMs?.let { (i.nowMs - it) / 60_000 } ?: Long.MAX_VALUE
        val driving = i.journey == JourneyStatus.DRIVING.name
        if (driving) {
            val sinceFoodMin = gapMin(i.nowMs, i.foodAtMs, i.startedAtMs)
            if (sinceFoodMin != null && sinceFoodMin >= FOOD_GAP_MIN && activeSinceMin >= FOOD_GAP_MIN) {
                attention.add("No food logged for ${sinceFoodMin / 60}h ${sinceFoodMin % 60}m")
            }
            val sinceWaterMin = gapMin(i.nowMs, i.waterAtMs, i.startedAtMs)
            if (sinceWaterMin != null && sinceWaterMin >= WATER_GAP_MIN && activeSinceMin >= WATER_GAP_MIN) {
                attention.add("No water logged for ${sinceWaterMin / 60}h ${sinceWaterMin % 60}m")
            }
            val legMin = i.drivingSinceMs?.let { (i.nowMs - it) / 60_000 }
            if (i.privateVehicle && legMin != null && legMin >= CONTINUOUS_DRIVE_MIN) {
                attention.add("Driving ${legMin / 60}h ${legMin % 60}m without a break")
            }
            if (i.localHour >= 23 || i.localHour <= 4) {
                attention.add("Travelling late at night")
            }
        }

        return when {
            concern.isNotEmpty() -> Report(Level.CONCERN, "This journey needs attention", concern + attention)
            attention.isNotEmpty() -> Report(Level.ATTENTION, "Something worth a look", attention)
            else -> Report(Level.NORMAL, "Journey progressing normally", emptyList())
        }
    }

    /** Minutes since the later of (last log, journey start); null if neither known. */
    private fun gapMin(nowMs: Long, loggedAtMs: Long?, startedAtMs: Long?): Long? {
        val base = maxOf(loggedAtMs ?: 0L, startedAtMs ?: 0L)
        if (base == 0L) return null
        return (nowMs - base) / 60_000
    }
}
