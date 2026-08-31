package com.trippulse.app.core

import android.content.Context
import android.telephony.TelephonyManager
import java.security.MessageDigest

/**
 * A fingerprint of the SIM currently in the phone, and why it is only a
 * fingerprint.
 *
 * The identifier a thief-detector really wants is the ICCID or IMSI, and
 * Android stopped handing those to ordinary apps at API 29 — correctly, since
 * they are exactly the identifiers that make silent cross-app tracking
 * possible. What remains readable without any permission is the carrier: the
 * operator's numeric code, its name and its country. That is coarse, and it is
 * honest about being coarse:
 *
 *  - Swapping to a **different carrier's** SIM changes it. This is the common
 *    case when a phone is stolen and resold, and it is caught.
 *  - Swapping to **another SIM on the same carrier** does not. That is a real
 *    gap, and no permission-free API closes it.
 *
 * We take the detection we can get rather than requesting privileged phone
 * permissions a safety app has no business holding.
 *
 * ## The part that matters more than detection
 *
 * Koode's ability to report has never depended on the SIM. A journey's
 * credentials live in the app's own storage, and reporting goes over whatever
 * network is reachable — the new SIM's data, or somebody's Wi-Fi. So a swapped
 * SIM does not stop the updates; it is simply a fact worth telling the family
 * about, because it means somebody else has opened the phone.
 */
object DeviceIdentity {

    private const val PREFS = "koode_device"
    private const val KEY_SIM = "sim_fingerprint"

    /**
     * A stable hash of the current carrier, or null when there is no SIM at
     * all (Wi-Fi tablets, or a phone with the tray removed).
     *
     * Hashed rather than stored raw: the app never needs to know *which*
     * carrier, only whether it is the same one as before, and a hash cannot
     * become a tracking identifier if the database ever leaks.
     */
    fun simFingerprint(context: Context): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        val parts = listOf(
            runCatching { tm.simOperator }.getOrNull().orEmpty(),
            runCatching { tm.simOperatorName }.getOrNull().orEmpty(),
            runCatching { tm.simCountryIso }.getOrNull().orEmpty()
        )
        // All blank means no SIM to fingerprint. Reporting "no SIM" as a
        // fingerprint would make removing the tray look identical to never
        // having had one.
        if (parts.all { it.isBlank() }) return null
        return sha256(parts.joinToString("|").lowercase()).take(16)
    }

    /** The fingerprint recorded when the current journey began. */
    fun rememberedSim(context: Context): String? =
        prefs(context).getString(KEY_SIM, null)

    fun rememberSim(context: Context, fingerprint: String?) {
        prefs(context).edit().apply {
            if (fingerprint == null) remove(KEY_SIM) else putString(KEY_SIM, fingerprint)
        }.apply()
    }

    /**
     * Whether the SIM has changed since [rememberSim] was last called.
     *
     * Deliberately false when either side is null. A phone that had no SIM and
     * now has one is somebody putting their own card into their own spare
     * handset far more often than it is a theft, and a safety alert that fires
     * on the ordinary case is an alert people switch off.
     */
    fun simChanged(context: Context): Boolean {
        val remembered = rememberedSim(context) ?: return false
        val current = simFingerprint(context) ?: return false
        return remembered != current
    }

    /** What, if anything, happened to the SIM since the baseline was set. */
    enum class SimEvent { NONE, CHANGED, REMOVED }

    /**
     * Whether the SIM tray is definitively empty right now.
     *
     * Distinguished from "we could not read the SIM" on purpose: a transient
     * telephony read failure returns a null fingerprint too, and treating that
     * as a removal would cry theft every time the modem hiccuped. Only
     * SIM_STATE_ABSENT — the OS stating plainly that there is no card — counts.
     */
    fun simDefinitelyAbsent(context: Context): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return false
        return runCatching { tm.simState == TelephonyManager.SIM_STATE_ABSENT }.getOrDefault(false)
    }

    /**
     * Classifies the SIM against a remembered fingerprint.
     *
     * REMOVED is the addition that matters for theft: a thief pulling the SIM
     * to stop tracking is both the commonest tamper and the first move of an
     * in-place same-carrier swap, which the carrier fingerprint alone cannot
     * see. It keeps working because Koode never depended on the SIM to report
     * — the pulled card stops nothing, it only tells the family what happened.
     */
    fun simEvent(context: Context, remembered: String?): SimEvent =
        classifySim(remembered, simFingerprint(context), simDefinitelyAbsent(context))

    /**
     * The decision, separated from the device reads so it can be tested.
     *
     * [absent] must be the OS stating the tray is empty, never merely a failed
     * read: a null [current] with [absent] false is "we could not tell", which
     * is silence, not a removal.
     */
    fun classifySim(remembered: String?, current: String?, absent: Boolean): SimEvent = when {
        remembered == null -> SimEvent.NONE
        current != null && current != remembered -> SimEvent.CHANGED
        current == null && absent -> SimEvent.REMOVED
        else -> SimEvent.NONE
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
