package com.trippulse.app.core

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Works out which country the traveller is actually in.
 *
 * The order matters and is the whole design. A phone bought in Kerala and
 * carried to Dubai should price a journey in dirhams while it is there, so the
 * *network* the phone is attached to is the strongest signal; the SIM is the
 * next best; the device locale is the fallback. Nothing here asks for a
 * permission, makes a network call, or needs the user to configure anything.
 *
 * The result is a two-letter ISO country code, cached for the session because
 * it changes at most a few times in a lifetime — and re-read on demand when a
 * journey starts, which is exactly when crossing a border would matter.
 */
class RegionDetector(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var cached: String? = null

    /** ISO-3166 alpha-2, upper case, or null when nothing could be determined. */
    fun countryCode(refresh: Boolean = false): String? {
        if (!refresh) cached?.let { return it }
        val detected = networkCountry() ?: simCountry() ?: localeCountry()
        cached = detected
        return detected
    }

    /** Where the phone is right now, per the mobile network it can see. */
    private fun networkCountry(): String? = try {
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        // Only meaningful on a real mobile network; a Wi-Fi-only tablet reports
        // nothing here, which is why the fallbacks exist.
        tm?.networkCountryIso?.normalizeCountry()
    } catch (_: Exception) {
        null
    }

    /** Where the phone's SIM was issued — usually home, sometimes roaming. */
    private fun simCountry(): String? = try {
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.simCountryIso?.normalizeCountry()
    } catch (_: Exception) {
        null
    }

    /** Whatever the user set their device to. Always available. */
    private fun localeCountry(): String? =
        Locale.getDefault().country.normalizeCountry()

    private fun String?.normalizeCountry(): String? =
        this?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 }
}
