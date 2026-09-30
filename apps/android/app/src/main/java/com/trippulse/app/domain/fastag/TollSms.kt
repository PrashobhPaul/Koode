package com.trippulse.app.domain.fastag

import java.text.SimpleDateFormat
import java.util.Locale

/** Which payment method the crossing used, as far as the SMS actually says. */
enum class TollPassType { ANNUAL_PASS, PER_TRIP, UNKNOWN }

/**
 * A toll crossing extracted from a FASTag/toll SMS — only what the message
 * stated, nothing inferred. A crossing is *evidence* the vehicle passed a
 * plaza at a time; it is never used to reconstruct the route between points.
 */
data class TollCrossing(
    /** e.g. "Paliyekkara Toll Plaza"; null when the SMS did not name one. */
    val plaza: String?,
    /** Normalised plate, e.g. "TS07FZ1868"; null when absent. */
    val vehicle: String?,
    val passType: TollPassType,
    /** Parsed from the SMS when present, else the receive time. */
    val crossedAtMs: Long,
    /** Best-effort issuer/bank, e.g. "ICICI"; may be null. */
    val issuer: String?
) {
    /**
     * A stable key for the same real-world crossing, so the same SMS (or a
     * re-delivery of it) never produces two events. Minute-resolution on the
     * time absorbs trivial second differences between duplicate messages.
     */
    fun dedupKey(): String {
        val p = plaza?.lowercase()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val v = vehicle?.uppercase().orEmpty()
        return "$v|$p|${crossedAtMs / 60_000}"
    }
}

/**
 * On-device parser for FASTag / toll-plaza SMS. Deliberately issuer-agnostic:
 * it keys off the vocabulary common to toll messages (FASTag, toll plaza,
 * crossed, NHAI, annual pass) rather than one bank's exact template, and
 * extracts only the plaza, vehicle, pass type and time. It never keeps the raw
 * SMS body, and returns null for anything that is not clearly a toll crossing
 * (recharge alerts, low-balance warnings, OTPs, unrelated bank SMS).
 */
object TollSmsParser {

    private val TOLL_WORDS = listOf("toll", "fastag", "fas tag", "plaza", "nhai", "npci")
    private val CROSSING_WORDS = listOf("crossed", "passed", "toll plaza", "toll booth", "deducted at", "toll transaction")

    private val KNOWN_ISSUERS = listOf(
        "ICICI", "HDFC", "SBI", "AXIS", "IDFC", "KOTAK", "PAYTM", "AIRTEL", "BANK OF BARODA", "NHAI"
    )

    /** A cheap pre-filter: is this message plausibly about a toll at all? */
    fun looksLikeToll(body: String?): Boolean {
        val b = body?.lowercase() ?: return false
        return TOLL_WORDS.any { it in b } && CROSSING_WORDS.any { it in b }
    }

    /**
     * Parse a toll crossing, or null if the message is not a toll crossing.
     * [receivedAtMs] is used as the time only when the SMS has none.
     */
    fun parse(body: String?, receivedAtMs: Long, sender: String? = null): TollCrossing? {
        if (body.isNullOrBlank() || !looksLikeToll(body)) return null

        val plaza = extractPlaza(body)
        val vehicle = extractVehicle(body)
        // A crossing needs at least a plaza or a vehicle to be worth recording.
        if (plaza == null && vehicle == null) return null

        val lower = body.lowercase()
        val passType = when {
            "annual pass" in lower -> TollPassType.ANNUAL_PASS
            listOf("debited", "deducted", "amount", "paid", "rs.", "inr").any { it in lower } -> TollPassType.PER_TRIP
            else -> TollPassType.UNKNOWN
        }
        val crossedAt = extractTime(body) ?: receivedAtMs
        return TollCrossing(plaza, vehicle, passType, crossedAt, extractIssuer(body, sender))
    }

    private val PLAZA_PATTERNS = listOf(
        Regex("""(?:crossed|passed|at)\s+([A-Za-z0-9.'&\- ]+?\s+(?:Toll\s*Plaza|Toll\s*Booth))""", RegexOption.IGNORE_CASE),
        Regex("""(?:Toll\s*Plaza|Plaza)\s*[:\-]\s*([A-Za-z0-9.'&\- ]+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:crossed|passed)\s+([A-Za-z0-9.'&\- ]+?\s+Plaza)""", RegexOption.IGNORE_CASE)
    )

    private fun extractPlaza(b: String): String? {
        for (p in PLAZA_PATTERNS) {
            p.find(b)?.groupValues?.getOrNull(1)?.let { raw ->
                val name = tidy(raw)
                if (name.isNotBlank() && name.length in 3..60) return name
            }
        }
        return null
    }

    // Indian plate forms: TS07FZ1868, KL-08-AC-1234, MH 12 AB 1234, with or
    // without separators; also the newer "TS 07 F 1868" single-letter series.
    private val PLATE = Regex("""\b([A-Z]{2}[ -]?\d{1,2}[ -]?[A-Z]{1,3}[ -]?\d{3,4})\b""")
    private val PLATE_AFTER_VEHICLE = Regex(
        """vehicle\s*(?:no\.?|number)?\s*[:\-]?\s*([A-Z]{2}[ -]?\d{1,2}[ -]?[A-Z]{1,3}[ -]?\d{3,4})""",
        RegexOption.IGNORE_CASE
    )

    private fun extractVehicle(b: String): String? {
        PLATE_AFTER_VEHICLE.find(b)?.groupValues?.getOrNull(1)?.let { return normalizePlate(it) }
        PLATE.find(b.uppercase())?.groupValues?.getOrNull(1)?.let { return normalizePlate(it) }
        return null
    }

    /** Strip separators and upper-case: "KL-08-AC-1234" → "KL08AC1234". */
    fun normalizePlate(raw: String): String = raw.uppercase().replace(Regex("[^A-Z0-9]"), "")

    private val TIME_PATTERNS = listOf(
        "dd-MM-yyyy HH:mm:ss" to Regex("""(\d{2}-\d{2}-\d{4}\s+\d{2}:\d{2}:\d{2})"""),
        "dd-MM-yyyy HH:mm" to Regex("""(\d{2}-\d{2}-\d{4}\s+\d{2}:\d{2})"""),
        "dd/MM/yyyy HH:mm:ss" to Regex("""(\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2}:\d{2})"""),
        "dd/MM/yyyy HH:mm" to Regex("""(\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2})""")
    )

    private fun extractTime(b: String): Long? {
        for ((fmt, rx) in TIME_PATTERNS) {
            val raw = rx.find(b)?.groupValues?.getOrNull(1) ?: continue
            runCatching {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH).apply { isLenient = false }
                return sdf.parse(raw)?.time
            }
        }
        return null
    }

    private fun extractIssuer(b: String, sender: String?): String? {
        val hay = (sender.orEmpty() + " " + b).uppercase()
        return KNOWN_ISSUERS.firstOrNull { it in hay }
    }

    private fun tidy(s: String): String =
        s.replace(Regex("\\s+"), " ").trim().trim('.', ',', '-', ':').trim()
}
