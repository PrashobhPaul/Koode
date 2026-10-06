package com.trippulse.app.domain

/**
 * Travel expenses: the traveller's own, private financial record of a
 * journey — never part of what followers receive.
 *
 * Expenses are captured when they become known (a food break ending, a
 * refuel, a cab leg finishing, a room confirmed), never while a driver is
 * moving, and every prompt can be skipped. A skipped prompt is not "no
 * expense": it stays an open [Opportunity] until the traveller records an
 * amount, says there was none, or leaves it unknown. Unknown is never zero.
 *
 * Pure and unit-tested; the caller persists opportunities and stores
 * recorded amounts as expense rows.
 */
object Expenses {

    enum class Category(val label: String, val emoji: String) {
        FUEL("Fuel", "⛽"), TOLL("Tolls", "🛣"), FOOD("Food", "🍛"),
        CAB("Cab", "🚕"), BIKE_TAXI("Bike taxi", "🏍"), AUTO("Auto", "🛺"), METRO("Metro", "🚇"), BUS("Bus", "🚌"),
        TRAIN("Train", "🚆"), FLIGHT("Flight", "✈️"), SHIP("Ship / ferry", "🚢"),
        PARKING("Parking", "🅿️"), ACCOMMODATION("Accommodation", "🏨"),
        VEHICLE_REPAIR("Vehicle repair", "🔧"), OTHER("Other", "🧾");

        companion object {
            /** Stored expense types, including the ones older builds wrote. */
            fun fromType(type: String?): Category = when (type?.uppercase()) {
                "STAY" -> ACCOMMODATION
                "TICKET" -> OTHER
                null -> OTHER
                else -> entries.firstOrNull { it.name == type.uppercase() } ?: OTHER
            }

            /** The fare category of a travel mode, or null for your own vehicle. */
            fun fareFor(modeKey: String?): Category? = when (modeKey?.uppercase()) {
                "CAB" -> CAB
                "BIKE_TAXI" -> BIKE_TAXI
                "AUTO" -> AUTO
                "BUS" -> BUS
                "METRO" -> METRO
                "TRAIN" -> TRAIN
                "FLIGHT" -> FLIGHT
                "SHIP", "FERRY" -> SHIP
                else -> null
            }
        }
    }

    enum class Status {
        /** Just noticed; the traveller hasn't answered yet. */
        PENDING,
        /** "Skip for now": still open, to be completed later. */
        DEFERRED,
        RECORDED,
        /** The traveller said there was no expense — a real ₹0. */
        NO_EXPENSE,
        /** The traveller can't recover the amount — never shown as ₹0. */
        UNKNOWN
    }

    /** A moment where money was plausibly spent, tied to the journey event it came from. */
    data class Opportunity(
        val id: String,
        val category: Category,
        val atMs: Long,
        /** "Food · Hotel Aryaas", "Cab · Airport → Station". */
        val label: String,
        val status: Status = Status.PENDING,
        val amount: Double? = null,
        /** The traveller has been shown the prompt for it. */
        val prompted: Boolean = false
    ) {
        val open: Boolean get() = status == Status.PENDING || status == Status.DEFERRED
    }

    /**
     * Whether it is safe to ask now. A driver on the move is never asked to
     * type; the prompt waits for a stop. Passengers can be asked at once.
     */
    fun safeToAsk(driver: Boolean, moving: Boolean): Boolean = !(driver && moving)

    /** The prompt, in the traveller's words. */
    fun question(o: Opportunity): String = when (o.category) {
        Category.FOOD -> "How much did you spend on food?"
        Category.FUEL -> "Fuel expense?"
        Category.CAB -> "How much was the cab fare?"
        Category.AUTO -> "How much was the auto fare?"
        Category.METRO -> "Metro fare?"
        Category.BUS -> "Bus fare?"
        Category.TRAIN -> "Train fare?"
        Category.FLIGHT -> "Flight cost?"
        Category.SHIP -> "Ferry fare?"
        Category.ACCOMMODATION -> "Did you pay for the room?"
        Category.TOLL -> "Toll expense?"
        Category.PARKING -> "Did you pay for parking?"
        else -> "Any expense here?"
    }

    enum class ItemStatus { RECORDED, NO_EXPENSE, COVERED_BY_ANNUAL_PASS, NEEDS_AMOUNT, UNKNOWN }

    data class Item(
        val category: Category,
        val status: ItemStatus,
        /** Recorded money for this category (never includes unknown or pass crossings). */
        val amount: Double,
        val openCount: Int,
        val unknownCount: Int,
        val reason: String
    )

    data class Checklist(
        val items: List<Item>,
        val recordedTotal: Double,
        /** Opportunities still waiting for an answer — they block approval. */
        val needsAttention: Int,
        /** Amounts the traveller chose to leave unknown. */
        val unknownCount: Int,
        val passCrossings: Int
    ) {
        val complete: Boolean get() = needsAttention == 0 && unknownCount == 0
        val canApprove: Boolean get() = needsAttention == 0

        /** Honest about completeness: "Recorded trip expenses" until everything is accounted for. */
        val headline: String get() = if (complete) "Trip expenses" else "Recorded trip expenses"

        val statusLine: String get() = when {
            needsAttention > 0 -> "⚠ $needsAttention expense${if (needsAttention == 1) "" else "s"} still need${if (needsAttention == 1) "s" else ""} an amount"
            unknownCount > 0 -> "$unknownCount expense amount${if (unknownCount == 1) "" else "s"} remain${if (unknownCount == 1) "s" else ""} unknown"
            else -> "✓ All detected expenses accounted for"
        }
    }

    /**
     * The completeness check: what was recorded, what the journey suggests
     * was spent but has no answer yet, and tolls covered by an annual pass
     * (₹0 expense, counted as crossings — never priced).
     */
    fun checklist(
        recorded: List<Pair<Category, Double>>,
        opportunities: List<Opportunity>,
        passCoveredCrossings: Int
    ): Checklist {
        val byCat = recorded.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        val oppByCat = opportunities.groupBy { it.category }
        val cats = (byCat.keys + oppByCat.keys + (if (passCoveredCrossings > 0) listOf(Category.TOLL) else emptyList()))
            .distinct().sortedBy { it.ordinal }
        val items = cats.map { c ->
            val ops = oppByCat[c].orEmpty()
            val open = ops.count { it.open }
            val unknown = ops.count { it.status == Status.UNKNOWN }
            val amount = byCat[c] ?: 0.0
            val status = when {
                open > 0 -> ItemStatus.NEEDS_AMOUNT
                unknown > 0 -> ItemStatus.UNKNOWN
                c == Category.TOLL && passCoveredCrossings > 0 && amount == 0.0 -> ItemStatus.COVERED_BY_ANNUAL_PASS
                byCat.containsKey(c) -> ItemStatus.RECORDED
                else -> ItemStatus.NO_EXPENSE
            }
            val reason = when (status) {
                ItemStatus.NEEDS_AMOUNT -> ops.first { it.open }.label
                ItemStatus.COVERED_BY_ANNUAL_PASS -> "$passCoveredCrossings annual-pass crossing${if (passCoveredCrossings == 1) "" else "s"}"
                ItemStatus.UNKNOWN -> "Amount not recorded"
                ItemStatus.NO_EXPENSE -> "No expense"
                ItemStatus.RECORDED -> ""
            }
            Item(c, status, amount, open, unknown, reason)
        }
        return Checklist(
            items = items,
            recordedTotal = byCat.values.sum(),
            needsAttention = opportunities.count { it.open },
            unknownCount = opportunities.count { it.status == Status.UNKNOWN },
            passCrossings = passCoveredCrossings
        )
    }

    // ---- storage: unit/record separators, so labels need no escaping ----

    private const val FS = '\u001F'
    private const val RS = '\u001E'

    fun encode(list: List<Opportunity>): String = list.joinToString(RS.toString()) {
        listOf(it.id, it.category.name, it.atMs, it.label, it.status.name, it.amount ?: "", it.prompted)
            .joinToString(FS.toString())
    }

    fun decode(raw: String?): List<Opportunity> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(RS).mapNotNull { rec ->
            val f = rec.split(FS)
            if (f.size != 7) return@mapNotNull null
            Opportunity(
                id = f[0],
                category = runCatching { Category.valueOf(f[1]) }.getOrNull() ?: return@mapNotNull null,
                atMs = f[2].toLongOrNull() ?: return@mapNotNull null,
                label = f[3],
                status = runCatching { Status.valueOf(f[4]) }.getOrNull() ?: return@mapNotNull null,
                amount = f[5].toDoubleOrNull(),
                prompted = f[6].toBoolean()
            )
        }
    }

    /**
     * Whether a new opportunity would duplicate one already known: the same
     * category and label still open, or answered for the same moment.
     */
    fun isDuplicate(existing: List<Opportunity>, category: Category, label: String, atMs: Long): Boolean =
        existing.any { it.category == category && it.label == label && (it.open || kotlin.math.abs(it.atMs - atMs) < 45 * 60_000L) }
}
