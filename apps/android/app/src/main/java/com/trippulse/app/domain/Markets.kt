package com.trippulse.app.domain

import java.util.Locale

/**
 * Everything about a country that changes how Koode behaves, in one table.
 *
 * A traveller in Texas, Munich or Osaka should never meet a rupee, a 12-hour
 * clock they don't use, a toll scheme their roads don't have, or a field
 * named after Indian Railways. None of that needs code per country: it needs
 * a row per country, read everywhere the behaviour differs. Launching in a
 * new market is a new row here (and translations), not a change elsewhere.
 *
 * Pure and unit-tested. The country itself comes from `core/RegionDetector`;
 * the user's own overrides (units, currency, clock) sit on top in [Market.resolve].
 */

/** How the clock is read: "06:55 PM" or "18:55". */
enum class ClockStyle(val key: String) {
    TWELVE_HOUR("12"), TWENTY_FOUR_HOUR("24");

    companion object {
        fun fromKey(key: String?): ClockStyle? = entries.firstOrNull { it.key == key }
    }
}

/** What the user asked for; AUTO follows the market (and the phone's own setting). */
enum class ClockPreference(val key: String, val label: String) {
    AUTO("AUTO", "Match my region"),
    TWELVE_HOUR("12", "12-hour (6:55 PM)"),
    TWENTY_FOUR_HOUR("24", "24-hour (18:55)");

    companion object {
        val DEFAULT = AUTO
        fun fromKey(key: String?): ClockPreference = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** The order a date is written in: 5 Oct 2026, Oct 5 2026, 2026/10/05. */
enum class DateOrder { DMY, MDY, YMD }

/**
 * The road-toll scheme of a country, which decides whether Koode can notice
 * plazas from the phone's position and what it calls a pass.
 *
 * Only [FASTAG] has plaza data behind it today (India, refreshed weekly from
 * OpenStreetMap). The others are named so the day their data arrives nothing
 * but a flag changes.
 */
enum class TollSystem(val passName: String?, val hasPlazaData: Boolean) {
    /** India: a windscreen tag read at a plaza; an annual pass is a fixed number of crossings. */
    FASTAG("FASTag annual pass", hasPlazaData = true),
    /** Japan: Electronic Toll Collection at a gate. */
    ETC("ETC", hasPlazaData = false),
    /** A transponder or a camera on an open road (E-ZPass, FasTrak, Telepass, Liber-t). */
    TAG("toll tag", hasPlazaData = false),
    /** A time-limited sticker for the motorway network (Austria, Switzerland, Czechia…). */
    VIGNETTE("vignette", hasPlazaData = false),
    /** Tolls are rare or absent; nothing to notice. */
    NONE(null, hasPlazaData = false)
}

/** Which privacy law the traveller lives under, which decides what the app must show and offer. */
enum class LegalRegime(val label: String, val needsConsentScreen: Boolean) {
    INDIA_DPDP("Digital Personal Data Protection Act (India)", needsConsentScreen = true),
    GDPR("GDPR (EU/EEA)", needsConsentScreen = true),
    UK_GDPR("UK GDPR", needsConsentScreen = true),
    US_STATE("US state privacy laws (CCPA/CPRA and others)", needsConsentScreen = true),
    JAPAN_APPI("Act on the Protection of Personal Information (Japan)", needsConsentScreen = true),
    OTHER("Local privacy law", needsConsentScreen = true)
}

/** Which side of the road is driven on; for pictures and wording that depend on it. */
enum class DrivingSide { LEFT, RIGHT }

data class Market(
    /** ISO 3166-1 alpha-2, or "" for the generic market. */
    val countryCode: String,
    val name: String,
    val units: UnitSystem,
    val clock: ClockStyle,
    val dateOrder: DateOrder,
    /** The number anyone can dial for help; 112 reaches help on every GSM phone. */
    val emergencyNumber: String,
    /** Where police and ambulance differ (Japan: 110 and 119), the police number. */
    val policeNumber: String? = null,
    val tolls: TollSystem,
    /** What the ticket's identifier is called: "PNR" on Indian Railways, a booking reference elsewhere. */
    val bookingRefLabel: String,
    /** What the pump sells: "Petrol" or "Gas". */
    val fuelWord: String,
    val legal: LegalRegime,
    /** The language most travellers here read, as a BCP 47 tag; the phone's own setting wins. */
    val languageTag: String,
    val drivingSide: DrivingSide
) {
    /** A pass that covers plazas, named as this market names it; null where there is no such thing. */
    val tollPassName: String? get() = tolls.passName

    /** Koode can notice toll plazas from position only where it has their positions. */
    val canDetectTolls: Boolean get() = tolls.hasPlazaData

    /** The currency of this market, from the ISO tables. */
    val currency: MoneyFormat get() = MoneyFormat.forCountry(countryCode.ifBlank { null })

    /** The locale most travellers here read in. */
    val locale: Locale get() = Locale.forLanguageTag(languageTag)

    companion object {
        /**
         * The market as the traveller experiences it: the country's row, with
         * the traveller's own choices on top. The phone's clock setting is
         * what Android apps honour, so it is passed in and beats the row;
         * an explicit preference beats both.
         */
        fun resolve(
            countryCode: String?,
            unitPreference: UnitPreference = UnitPreference.AUTO,
            clockPreference: ClockPreference = ClockPreference.AUTO,
            phoneUses24h: Boolean? = null
        ): Market {
            val base = Markets.forCountry(countryCode)
            val units = when (unitPreference) {
                UnitPreference.METRIC -> UnitSystem.METRIC
                UnitPreference.IMPERIAL -> UnitSystem.IMPERIAL
                UnitPreference.AUTO -> base.units
            }
            val clock = when (clockPreference) {
                ClockPreference.TWELVE_HOUR -> ClockStyle.TWELVE_HOUR
                ClockPreference.TWENTY_FOUR_HOUR -> ClockStyle.TWENTY_FOUR_HOUR
                ClockPreference.AUTO -> when (phoneUses24h) {
                    true -> ClockStyle.TWENTY_FOUR_HOUR
                    false -> ClockStyle.TWELVE_HOUR
                    null -> base.clock
                }
            }
            return base.copy(units = units, clock = clock)
        }
    }
}

object Markets {

    val INDIA = Market(
        countryCode = "IN", name = "India",
        units = UnitSystem.METRIC, clock = ClockStyle.TWELVE_HOUR, dateOrder = DateOrder.DMY,
        emergencyNumber = "112", tolls = TollSystem.FASTAG,
        bookingRefLabel = "PNR", fuelWord = "Petrol",
        legal = LegalRegime.INDIA_DPDP, languageTag = "en-IN", drivingSide = DrivingSide.LEFT
    )

    val UNITED_STATES = Market(
        countryCode = "US", name = "United States",
        units = UnitSystem.IMPERIAL, clock = ClockStyle.TWELVE_HOUR, dateOrder = DateOrder.MDY,
        emergencyNumber = "911", tolls = TollSystem.TAG,
        bookingRefLabel = "Confirmation number", fuelWord = "Gas",
        legal = LegalRegime.US_STATE, languageTag = "en-US", drivingSide = DrivingSide.RIGHT
    )

    val UNITED_KINGDOM = Market(
        countryCode = "GB", name = "United Kingdom",
        units = UnitSystem.IMPERIAL, clock = ClockStyle.TWENTY_FOUR_HOUR, dateOrder = DateOrder.DMY,
        emergencyNumber = "999", tolls = TollSystem.NONE,
        bookingRefLabel = "Booking reference", fuelWord = "Petrol",
        legal = LegalRegime.UK_GDPR, languageTag = "en-GB", drivingSide = DrivingSide.LEFT
    )

    val CANADA = Market(
        countryCode = "CA", name = "Canada",
        units = UnitSystem.METRIC, clock = ClockStyle.TWELVE_HOUR, dateOrder = DateOrder.YMD,
        emergencyNumber = "911", tolls = TollSystem.TAG,
        bookingRefLabel = "Confirmation number", fuelWord = "Gas",
        legal = LegalRegime.OTHER, languageTag = "en-CA", drivingSide = DrivingSide.RIGHT
    )

    val AUSTRALIA = Market(
        countryCode = "AU", name = "Australia",
        units = UnitSystem.METRIC, clock = ClockStyle.TWELVE_HOUR, dateOrder = DateOrder.DMY,
        emergencyNumber = "000", tolls = TollSystem.TAG,
        bookingRefLabel = "Booking reference", fuelWord = "Petrol",
        legal = LegalRegime.OTHER, languageTag = "en-AU", drivingSide = DrivingSide.LEFT
    )

    /**
     * Japan: 24-hour clock, year-first dates, ETC at the toll gate, and the
     * strictest reading of a manual. Police and ambulance are different
     * numbers, so both are kept.
     */
    val JAPAN = Market(
        countryCode = "JP", name = "Japan",
        units = UnitSystem.METRIC, clock = ClockStyle.TWENTY_FOUR_HOUR, dateOrder = DateOrder.YMD,
        emergencyNumber = "119", policeNumber = "110", tolls = TollSystem.ETC,
        bookingRefLabel = "Reservation number", fuelWord = "Gasoline",
        legal = LegalRegime.JAPAN_APPI, languageTag = "ja-JP", drivingSide = DrivingSide.LEFT
    )

    /** EU and EEA members, plus Switzerland: GDPR, 112, 24-hour clock, day-first dates. */
    private val EUROPE: Set<String> = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT",
        "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "IS", "LI", "NO", "CH"
    )

    /** Countries where the motorway is paid for with a sticker, not at a booth. */
    private val VIGNETTE: Set<String> = setOf("AT", "CH", "CZ", "SK", "SI", "HU", "BG", "RO")

    /** Where the car is on the left; everywhere else it is on the right. */
    private val LEFT_SIDE: Set<String> = setOf(
        "IN", "GB", "IE", "JP", "AU", "NZ", "ZA", "LK", "NP", "BD", "PK", "MY", "SG", "TH", "ID", "KE", "MT", "CY", "HK"
    )

    /** Emergency numbers that are not 112; a GSM phone routes 112 to help everywhere anyway. */
    private val EMERGENCY: Map<String, String> = mapOf(
        "US" to "911", "CA" to "911", "MX" to "911", "GB" to "999", "IE" to "112", "AU" to "000",
        "NZ" to "111", "JP" to "119", "KR" to "119", "CN" to "120", "HK" to "999", "SG" to "995",
        "MY" to "999", "TH" to "1669", "ID" to "112", "LK" to "1990", "NP" to "102", "BD" to "999",
        "PK" to "1122", "AE" to "998", "SA" to "997", "QA" to "999", "ZA" to "10177", "BR" to "192", "AR" to "107"
    )

    /** The hand-tuned rows, by country. */
    private val KNOWN: Map<String, Market> = listOf(INDIA, UNITED_STATES, UNITED_KINGDOM, CANADA, AUSTRALIA, JAPAN)
        .associateBy { it.countryCode }

    /**
     * The market for a country. A known row where there is one; otherwise a
     * row worked out from what is known about the region: Europe under GDPR
     * with a 24-hour clock, everyone else on metric, 24-hour, 112 and local
     * privacy law. Nothing falls back to India except India.
     */
    fun forCountry(countryCode: String?): Market {
        val cc = countryCode?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } } ?: return GENERIC
        KNOWN[cc]?.let { return it }
        val europe = cc in EUROPE
        val name = runCatching { Locale.Builder().setRegion(cc).build().getDisplayCountry(Locale.ENGLISH) }
            .getOrNull()?.ifBlank { null } ?: cc
        return Market(
            countryCode = cc, name = name,
            units = Measures.unitsForCountry(cc),
            clock = ClockStyle.TWENTY_FOUR_HOUR, dateOrder = DateOrder.DMY,
            emergencyNumber = EMERGENCY[cc] ?: "112",
            tolls = when {
                cc in VIGNETTE -> TollSystem.VIGNETTE
                europe -> TollSystem.TAG
                else -> TollSystem.NONE
            },
            bookingRefLabel = "Booking reference", fuelWord = "Petrol",
            legal = if (europe) LegalRegime.GDPR else LegalRegime.OTHER,
            languageTag = languageOf(cc),
            drivingSide = if (cc in LEFT_SIDE) DrivingSide.LEFT else DrivingSide.RIGHT
        )
    }

    /** Before any country is known: metric, 24-hour, 112, and nothing India-specific. */
    val GENERIC = Market(
        countryCode = "", name = "",
        units = UnitSystem.METRIC, clock = ClockStyle.TWENTY_FOUR_HOUR, dateOrder = DateOrder.DMY,
        emergencyNumber = "112", tolls = TollSystem.NONE,
        bookingRefLabel = "Booking reference", fuelWord = "Petrol",
        legal = LegalRegime.OTHER, languageTag = "en", drivingSide = DrivingSide.RIGHT
    )

    /** Every market the picker offers, India first and then by name. */
    val PICKER: List<Market> = listOf(INDIA) + listOf(UNITED_STATES, UNITED_KINGDOM, CANADA, AUSTRALIA, JAPAN).sortedBy { it.name }

    /**
     * The language most people in a country read. Kept as a table rather than
     * asked of the JDK, whose first locale for Germany is Lower Sorbian.
     */
    private val LANGUAGE: Map<String, String> = mapOf(
        "DE" to "de", "AT" to "de", "CH" to "de", "LI" to "de", "FR" to "fr", "BE" to "nl", "LU" to "fr", "MC" to "fr",
        "ES" to "es", "MX" to "es", "AR" to "es", "CL" to "es", "CO" to "es", "PE" to "es", "IT" to "it", "SM" to "it",
        "NL" to "nl", "PT" to "pt", "BR" to "pt", "SE" to "sv", "NO" to "nb", "DK" to "da", "FI" to "fi", "IS" to "is",
        "PL" to "pl", "CZ" to "cs", "SK" to "sk", "SI" to "sl", "HR" to "hr", "HU" to "hu", "RO" to "ro", "BG" to "bg",
        "GR" to "el", "CY" to "el", "MT" to "en", "IE" to "en", "EE" to "et", "LV" to "lv", "LT" to "lt",
        "KR" to "ko", "CN" to "zh", "TW" to "zh", "HK" to "zh", "TH" to "th", "VN" to "vi", "ID" to "id", "MY" to "ms",
        "TR" to "tr", "RU" to "ru", "UA" to "uk", "SA" to "ar", "AE" to "ar", "QA" to "ar", "EG" to "ar", "IL" to "he",
        "LK" to "si", "NP" to "ne", "BD" to "bn", "PK" to "ur", "KE" to "sw", "ZA" to "en", "NZ" to "en", "SG" to "en"
    )

    private fun languageOf(cc: String): String = LANGUAGE[cc]?.let { "$it-$cc" } ?: "en"
}
