package com.trippulse.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Everything a police or cyber-crime report could use to identify a phone,
 * and an honest account of what modern Android will and will not hand over.
 *
 * ## Read this before adding "IMEI" or "MAC address"
 *
 * They cannot be obtained, and the reason is deliberate on Google's part.
 *
 *  - **IMEI / device serial.** Since Android 10 (API 29) `getImei()` and the
 *    serial throw `SecurityException` for every app that is not the carrier
 *    app or a device/profile owner. Ordinary apps — which is what this is, and
 *    what it should stay — get nothing. Requesting `READ_PHONE_STATE` does not
 *    change that; the IMEI gate is separate and higher. The only ways past it
 *    are to ship as a pre-installed system app or an enterprise MDM, which is
 *    a different product with a different distribution model.
 *
 *  - **Wi-Fi / Bluetooth MAC.** Randomised and unreadable since Android 6, and
 *    hardened further since. `getMacAddress()` returns the fixed sentinel
 *    `02:00:00:00:00:00` on every device the family owns. There is no real
 *    hardware MAC to collect.
 *
 * Pretending otherwise would put a fabricated or blank IMEI on a document
 * somebody hands to the police, which is worse than an honest "not available
 * on this Android version". So the dossier records those fields as
 * unavailable, with the reason, and collects everything that *is* real:
 *
 *  - Manufacturer, brand, model, device and product — "a black Samsung Galaxy
 *    A54 (SM-A546E)" is exactly how a report describes a phone, and it is
 *    freely readable.
 *  - Android version, API level and security-patch date.
 *  - `ANDROID_ID` — a stable per-app, per-device identifier. Not the IMEI, but
 *    the closest lawful equivalent, and it is what Google itself points
 *    developers to.
 *  - A persistent install UUID we generate once and keep. It survives a SIM
 *    swap and ties every report from this install together.
 *  - The carrier, the local IP, and — the genuinely useful one — the public IP
 *    at the moment of capture.
 *
 * ## Why the public IP matters most
 *
 * It is the one network identifier that is both obtainable and forensically
 * live. An ISP can map a public IP at a timestamp to a subscriber or a tower,
 * which is precisely what a cyber cell traces. And it changes when the phone
 * does: a thief who powers a stolen phone back on and joins their own network
 * hands us a fresh public IP the moment tracking resumes — which is why the
 * boot path re-captures the whole dossier rather than trusting the one from
 * the journey's start.
 */
object DeviceDossier {

    private const val PREFS = "koode_dossier"
    private const val KEY_INSTALL_ID = "install_id"

    /**
     * A UUID minted once per install and kept forever after.
     *
     * The one identifier entirely under our control, so the one that does not
     * evaporate when Android tightens a screw or a thief swaps the SIM.
     */
    fun installId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, fresh).apply()
        return fresh
    }

    @SuppressLint("HardwareIds") // ANDROID_ID is the lawful identifier; see class doc.
    private fun androidId(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()

    /** Non-loopback IPv4 of the active interface, or null. */
    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
    }.getOrNull()

    /**
     * The everything-that-is-known snapshot, minus the public IP (a network
     * call, so it is added by [withPublicIp]). Pure and instant, so it is safe
     * to take inside a shutdown broadcast's few seconds.
     */
    fun capture(context: Context): Map<String, Any?> = buildMap {
        put("installId", installId(context))
        put("androidId", androidId(context))
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("androidRelease", Build.VERSION.RELEASE)
        put("androidSdk", Build.VERSION.SDK_INT)
        put("securityPatch", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else null)
        put("carrier", DeviceIdentity.simFingerprint(context))
        put("localIp", localIpv4())
        // Recorded so the report can be explicit rather than silently missing.
        put("imei", null)
        put("imeiNote", "Not available: Android 10+ blocks IMEI for non-system apps.")
        put("macAddress", null)
        put("macNote", "Not available: Android randomises and hides the hardware MAC.")
        put("capturedAtMs", System.currentTimeMillis())
    }

    /**
     * The snapshot plus the public IP, resolved on the IO dispatcher.
     *
     * Best effort in the strongest sense: no network, a blocked lookup or a
     * slow one all just leave the field null. The rest of the dossier is worth
     * having on its own, and a report must never fail to generate because one
     * optional lookup timed out.
     */
    suspend fun withPublicIp(context: Context): Map<String, Any?> {
        val base = capture(context)
        val ip = fetchPublicIp()
        return if (ip == null) base else base + ("publicIp" to ip)
    }

    private val ipClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The public IP as a keyless, no-account lookup.
     *
     * Two providers, tried in turn, so one being down or rate-limiting does
     * not lose the single most useful field in the dossier.
     */
    private suspend fun fetchPublicIp(): String? = withContext(Dispatchers.IO) {
        for (url in PUBLIC_IP_ENDPOINTS) {
            val ip = runCatching {
                ipClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string()?.trim() ?: return@use null
                    // ipify's json form is {"ip":"1.2.3.4"}; icanhazip is bare text.
                    when {
                        body.startsWith("{") -> JSONObject(body).optString("ip").ifBlank { null }
                        else -> body.takeIf { it.count { c -> c == '.' } == 3 || it.contains(":") }
                    }
                }
            }.getOrNull()
            if (ip != null) return@withContext ip
        }
        null
    }

    private val PUBLIC_IP_ENDPOINTS = listOf(
        "https://api.ipify.org?format=json",
        "https://icanhazip.com"
    )

    /** Serialises a dossier snapshot to JSON, preserving nulls and types. */
    fun toJson(dossier: Map<String, Any?>): String {
        val o = JSONObject()
        for ((k, v) in dossier) if (v == null) o.put(k, JSONObject.NULL) else o.put(k, v)
        return o.toString()
    }

    /** Reads a stored dossier back. Unreadable JSON is an empty map, never a crash. */
    fun fromJson(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(json)
            buildMap {
                for (k in o.keys()) {
                    val v = o.get(k)
                    put(k, if (v == JSONObject.NULL) null else v)
                }
            }
        }.getOrDefault(emptyMap())
    }

    /**
     * A one-line description of the phone for the top of a report.
     * "Samsung Galaxy A54 (SM-A546E) · Android 14".
     */
    fun describe(d: Map<String, Any?>): String {
        val maker = (d["manufacturer"] as? String)?.replaceFirstChar { it.uppercase() }.orEmpty()
        val model = d["model"] as? String ?: ""
        val release = d["androidRelease"] as? String
        val name = listOf(maker, model).filter { it.isNotBlank() }.joinToString(" ")
        return if (release.isNullOrBlank()) name else "$name · Android $release"
    }
}
