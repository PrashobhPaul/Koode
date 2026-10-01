package com.trippulse.app.domain

/**
 * How a journey ends: a traveller-controlled transition, never a GPS verdict.
 *
 *   PLANNED → ACTIVE → DESTINATION_REACHED → AWAITING_CLOSURE
 *        → CLOSED_PENDING_REVIEW (or AUTO_CLOSED → CLOSED_PENDING_REVIEW)
 *        → ANALYTICS_REVIEW → ANALYTICS_APPROVED → FINALIZED
 *
 * - Arrival is *detected* and the traveller is asked to close.
 * - A forgotten journey may close itself after [AUTO_CLOSE_AFTER_MIN] of
 *   sustained arrival — recorded as system-closed, never as confirmed.
 * - Closing stops tracking; nothing is published. Followers hear the
 *   journey ended, with the report, only once the traveller has reviewed
 *   and approved what Koode recorded.
 * - Expenses are a separate, private review that never reaches followers.
 *
 * Pure and unit-tested; the caller persists [Record].
 */
object JourneyClosure {

    enum class Lifecycle {
        PLANNED, ACTIVE, DESTINATION_REACHED, AWAITING_CLOSURE,
        AUTO_CLOSED, CLOSED_PENDING_REVIEW, ANALYTICS_REVIEW, ANALYTICS_APPROVED, FINALIZED
    }

    /** One stronger reminder this long after arrival, if the journey is still open. */
    const val REMINDER_AFTER_MIN = 15L
    /** Sustained arrival with no answer for this long closes a forgotten journey. */
    const val AUTO_CLOSE_AFTER_MIN = 30L
    /** A fix older than this is not evidence the traveller is still there. */
    const val FRESH_FIX_MIN = 15L

    /**
     * What the traveller's device holds about closing a journey.
     *
     * [previousStatus] is the live status before closing, published instead of
     * COMPLETED while the journey awaits review, so followers never read
     * "ended" before the traveller approves it.
     */
    data class Record(
        val stage: Lifecycle,
        val closedAtMs: Long,
        val auto: Boolean,
        val previousStatus: String,
        val closingNote: String? = null,
        val reviewStartedAtMs: Long? = null,
        val approvedAtMs: Long? = null,
        val approvedBy: String? = null,
        val safeConfirmed: Boolean = false,
        val expensesApprovedAtMs: Long? = null
    ) {
        val pendingReview: Boolean
            get() = stage == Lifecycle.CLOSED_PENDING_REVIEW || stage == Lifecycle.ANALYTICS_REVIEW

        fun encode(): String = listOf(
            stage.name, closedAtMs, auto, previousStatus, closingNote.orEmpty(),
            reviewStartedAtMs ?: "", approvedAtMs ?: "", approvedBy.orEmpty(), safeConfirmed,
            expensesApprovedAtMs ?: ""
        ).joinToString(FS.toString())

        companion object {
            fun decode(raw: String?): Record? {
                val f = raw?.split(FS) ?: return null
                if (f.size != 10) return null
                return Record(
                    stage = runCatching { Lifecycle.valueOf(f[0]) }.getOrNull() ?: return null,
                    closedAtMs = f[1].toLongOrNull() ?: return null,
                    auto = f[2].toBoolean(),
                    previousStatus = f[3],
                    closingNote = f[4].ifEmpty { null },
                    reviewStartedAtMs = f[5].toLongOrNull(),
                    approvedAtMs = f[6].toLongOrNull(),
                    approvedBy = f[7].ifEmpty { null },
                    safeConfirmed = f[8].toBoolean(),
                    expensesApprovedAtMs = f[9].toLongOrNull()
                )
            }
        }
    }

    private const val FS = '\u001F'

    /** Where a journey is, from what the device knows. */
    fun lifecycle(tripStatus: String, journeyStatus: String?, arrivalPromptDue: Boolean, record: Record?): Lifecycle = when {
        record != null -> record.stage
        tripStatus == "CREATED" -> Lifecycle.PLANNED
        tripStatus == "COMPLETED" || tripStatus == "EXPIRED" -> Lifecycle.FINALIZED
        journeyStatus == JourneyStatus.ARRIVED.name && arrivalPromptDue -> Lifecycle.AWAITING_CLOSURE
        journeyStatus == JourneyStatus.ARRIVED.name -> Lifecycle.DESTINATION_REACHED
        else -> Lifecycle.ACTIVE
    }

    /** The one stronger close reminder is due. */
    fun reminderDue(arrivalSinceMs: Long?, nowMs: Long, reminderSent: Boolean): Boolean =
        arrivalSinceMs != null && !reminderSent && nowMs - arrivalSinceMs >= REMINDER_AFTER_MIN * 60_000

    /**
     * A forgotten journey may close itself only after sustained arrival: the
     * clock runs from confirmed arrival (not one fix in the radius), the
     * traveller has not said they are still travelling, and a recent fix
     * still places them at the destination.
     */
    fun autoCloseDue(
        arrivalSinceMs: Long?, nowMs: Long, stillArrived: Boolean,
        lastFixAtMs: Long?, lastFixWithinRadius: Boolean
    ): Boolean {
        if (arrivalSinceMs == null || !stillArrived || !lastFixWithinRadius) return false
        val fix = lastFixAtMs ?: return false
        if (nowMs - fix > FRESH_FIX_MIN * 60_000) return false
        return nowMs - arrivalSinceMs >= AUTO_CLOSE_AFTER_MIN * 60_000
    }

    /** The traveller-facing close prompt. */
    fun closePromptTitle(destination: String): String = "Reached $destination"

    fun closePromptBody(stronger: Boolean): String =
        if (stronger) "Your journey is still open. End it when you're done, or tell Koode you're still travelling."
        else "It looks like you've arrived at your destination. Close your journey when you're ready."

    /** Inferred, and worded so: never "reached safely". */
    fun arrivalText(who: String, destination: String): String =
        "${who.ifBlank { "The traveller" }} appears to have reached $destination."

    const val AUTO_CLOSED_TEXT = "Journey automatically closed after no response following destination arrival."

    /**
     * What followers read once the traveller approves: factual, with "safely"
     * only when the traveller said so themselves.
     */
    fun endedText(who: String, origin: String, destination: String, endClock: String, safeConfirmed: Boolean): String {
        val name = who.ifBlank { "The traveller" }
        val base = "$name completed the journey from $origin to $destination at $endClock."
        val safe = if (safeConfirmed) " $name confirmed arriving safely." else ""
        return "$base$safe The verified journey report is available."
    }
}
