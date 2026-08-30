package com.trippulse.app.domain

import java.util.Currency
import java.util.Locale

/**
 * How a journey is measured and priced, worked out rather than assumed.
 *
 * A traveller in Kerala should see kilometres and ₹; one in Texas should see
 * miles and $. Neither should have to configure anything, and neither should
 * be shown the other's units because the app was written somewhere else. None
 * of this needs a model — it needs a country code and a lookup table, which is
 * what this file is.
 *
 * Everything here is pure and unit-tested. The Android-specific job of
 * *finding* the country lives in `core/RegionDetector.kt`.
 */

/** Metric or imperial road units. */
enum class UnitSystem(val key: String, val label: String) {
    METRIC("METRIC", "Kilometres"),
    IMPERIAL("IMPERIAL", "Miles");

    companion object {
        fun fromKey(key: String?): UnitSystem? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What the user asked for. `AUTO` means "work it out from where I am", which
 * is the default and what almost everyone will leave it on.
 */
enum class UnitPreference(val key: String, val label: String) {
    AUTO("AUTO", "Match my region"),
    METRIC("METRIC", "Kilometres (km, km/h)"),
    IMPERIAL("IMPERIAL", "Miles (mi, mph)");

    companion object {
        val DEFAULT = AUTO
        fun fromKey(key: String?): UnitPreference = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * A currency, resolved to the three things the UI actually needs: what to
 * print in front of the number, how many decimals it takes, and the ISO code
 * for anything that has to be unambiguous (an exported statement, say).
 */
data class MoneyFormat(
    val code: String,
    val symbol: String,
    val fractionDigits: Int
) {
    companion object {
        val RUPEE = MoneyFormat("INR", "₹", 2)

        /**
         * Derives the currency of a country from the JDK's own ISO tables,
         * so this stays correct without us maintaining a list of 190 countries.
         * Falls back to the rupee, because that is who this app is for first.
         */
        fun forCountry(countryCode: String?): MoneyFormat {
            val cc = countryCode?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 }
                ?: return RUPEE
            return try {
                val currency = Currency.getInstance(Locale.Builder().setRegion(cc).build())
                    ?: return RUPEE
                MoneyFormat(
                    code = currency.currencyCode,
                    symbol = symbolFor(currency, cc),
                    fractionDigits = currency.defaultFractionDigits.coerceAtLeast(0)
                )
            } catch (_: Exception) {
                RUPEE
            }
        }

        /** Resolves a currency the user pinned explicitly, by ISO code. */
        fun forCode(code: String?): MoneyFormat? {
            val iso = code?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 3 } ?: return null
            return try {
                val currency = Currency.getInstance(iso)
                MoneyFormat(
                    code = currency.currencyCode,
                    symbol = symbolFor(currency, null),
                    fractionDigits = currency.defaultFractionDigits.coerceAtLeast(0)
                )
            } catch (_: Exception) {
                null
            }
        }

        /** Every currency the picker offers, in the order travellers meet them. */
        val COMMON_CODES: List<String> = listOf(
            "INR", "USD", "EUR", "GBP", "AED", "SAR", "SGD", "AUD", "CAD", "JPY",
            "MYR", "THB", "LKR", "NPR", "CHF", "ZAR"
        )

        /**
         * The JDK returns the code ("INR") rather than the glyph when it has no
         * localised symbol for the current locale, which reads badly next to a
         * number. These are the ones our travellers actually meet.
         */
        private fun symbolFor(currency: Currency, countryCode: String?): String {
            KNOWN_SYMBOLS[currency.currencyCode]?.let { return it }
            val symbol = if (countryCode != null) {
                currency.getSymbol(Locale.Builder().setRegion(countryCode).build())
            } else {
                currency.symbol
            }
            return if (symbol.isNullOrBlank()) currency.currencyCode else symbol
        }

        private val KNOWN_SYMBOLS = mapOf(
            "INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "¥",
            "AUD" to "A$", "CAD" to "C$", "NZD" to "NZ$", "SGD" to "S$",
            "AED" to "AED ", "SAR" to "SAR ", "QAR" to "QAR ", "OMR" to "OMR ",
            "KWD" to "KWD ", "BHD" to "BHD ", "LKR" to "Rs ", "NPR" to "Rs ",
            "PKR" to "Rs ", "BDT" to "৳", "MYR" to "RM", "THB" to "฿",
            "CHF" to "CHF ", "SEK" to "kr", "NOK" to "kr", "DKK" to "kr",
            "ZAR" to "R", "CNY" to "¥", "KRW" to "₩", "RUB" to "₽", "BRL" to "R$"
        )
    }
}

/**
 * Everything the app needs to render a measurement, resolved once and passed
 * around. Screens ask this for a string; they never do arithmetic on units.
 */
data class Measures(
    val units: UnitSystem,
    val money: MoneyFormat
) {
    // ---- distance ----

    private val metresPerMile = 1609.344
    private val metresPerKm = 1000.0

    val distanceUnit: String get() = if (units == UnitSystem.IMPERIAL) "mi" else "km"
    val speedUnit: String get() = if (units == UnitSystem.IMPERIAL) "mph" else "km/h"
    val volumeUnit: String get() = if (units == UnitSystem.IMPERIAL) "gal" else "L"

    /** Distance in the user's own units, e.g. "412 km" or "256 mi". */
    fun distance(metres: Double): String {
        val value = distanceValue(metres)
        return if (value >= 100) "${value.toInt()} $distanceUnit"
        else String.format(Locale.ENGLISH, "%.1f %s", value, distanceUnit)
    }

    fun distanceValue(metres: Double): Double =
        if (units == UnitSystem.IMPERIAL) metres / metresPerMile else metres / metresPerKm

    /** Speed given in km/h (the app's internal unit), printed in the user's. */
    fun speed(kmh: Double): String {
        val value = if (units == UnitSystem.IMPERIAL) kmh / 1.609344 else kmh
        return "${value.toInt()} $speedUnit"
    }

    // ---- money ----

    /** "₹1,240" / "$18.50" — grouped, and only as precise as the currency is. */
    fun money(amount: Double): String {
        val digits = money.fractionDigits
        val formatted = if (digits == 0) String.format(Locale.ENGLISH, "%,.0f", amount)
        else String.format(Locale.ENGLISH, "%,.${digits}f", amount)
        return "${money.symbol}$formatted"
    }

    /** Money with the ISO code, for documents that must be unambiguous. */
    fun moneyWithCode(amount: Double): String = "${money(amount)} ${money.code}"

    // ---- derived rates ----

    /** "₹4.85 / km" — the number people actually compare journeys by. */
    fun costPerDistance(totalCost: Double, metres: Double): String? {
        val distance = distanceValue(metres)
        if (distance <= 0.0 || totalCost <= 0.0) return null
        return "${money(totalCost / distance)} / $distanceUnit"
    }

    /**
     * Fuel efficiency in the local idiom: km/L where the world says km/L, and
     * miles per gallon where it says mpg.
     */
    fun efficiency(metres: Double, litres: Double): String? {
        if (metres <= 0.0 || litres <= 0.0) return null
        return if (units == UnitSystem.IMPERIAL) {
            val miles = metres / metresPerMile
            val gallons = litres / 3.785411784
            String.format(Locale.ENGLISH, "%.1f mpg", miles / gallons)
        } else {
            String.format(Locale.ENGLISH, "%.1f km/L", (metres / metresPerKm) / litres)
        }
    }

    /** EV efficiency, which is km/kWh everywhere and mi/kWh in imperial. */
    fun electricEfficiency(metres: Double, kwh: Double): String? {
        if (metres <= 0.0 || kwh <= 0.0) return null
        val distance = distanceValue(metres)
        return String.format(Locale.ENGLISH, "%.1f %s/kWh", distance / kwh, distanceUnit)
    }

    companion object {
        /** Sensible default before anything has been detected. */
        val INDIA = Measures(UnitSystem.METRIC, MoneyFormat.RUPEE)

        /**
         * Countries that measure road distance in miles. Short by design: this
         * is genuinely a three-country list plus the UK, which uses miles on
         * the road while being metric everywhere else.
         */
        private val IMPERIAL_COUNTRIES = setOf("US", "GB", "LR", "MM")

        fun unitsForCountry(countryCode: String?): UnitSystem {
            val cc = countryCode?.trim()?.uppercase(Locale.ROOT)
            return if (cc in IMPERIAL_COUNTRIES) UnitSystem.IMPERIAL else UnitSystem.METRIC
        }

        /**
         * Resolves what to show, honouring an explicit setting over detection.
         *
         * @param countryCode where the traveller is, best effort; null is fine
         * @param unitPreference the user's setting, usually AUTO
         * @param currencyOverride an ISO code the user pinned, or null for auto
         */
        fun resolve(
            countryCode: String?,
            unitPreference: UnitPreference = UnitPreference.AUTO,
            currencyOverride: String? = null
        ): Measures {
            val units = when (unitPreference) {
                UnitPreference.METRIC -> UnitSystem.METRIC
                UnitPreference.IMPERIAL -> UnitSystem.IMPERIAL
                UnitPreference.AUTO -> unitsForCountry(countryCode)
            }
            val money = MoneyFormat.forCode(currencyOverride)
                ?: MoneyFormat.forCountry(countryCode)
            return Measures(units, money)
        }
    }
}
