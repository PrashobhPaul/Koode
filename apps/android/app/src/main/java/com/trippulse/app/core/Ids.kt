package com.trippulse.app.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Trip credential model (docs/spec/85, 10_SECURITY_PRIVACY_PLAYSTORE.md):
 *  - trip id:  TP-XXXX-XXXX (shareable, non-sequential)
 *  - secret:   XXXX-XXXX-XXXX (~60 bits of entropy, cryptographically random)
 *  - access key = SHA-256(tripId:secret) — the only value that ever reaches
 *    the backend. The raw secret is never stored server-side, so knowledge of
 *    the RTDB path is itself the capability, gated further by auth + expiry
 *    rules.
 */
object TripCredentials {

    // Crockford-style alphabet: no I, L, O, 0, 1 to avoid transcription errors.
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    private fun block(rnd: SecureRandom, len: Int): String =
        buildString(len) { repeat(len) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }

    fun newTripId(rnd: SecureRandom = SecureRandom()): String =
        "TP-${block(rnd, 4)}-${block(rnd, 4)}"

    fun newSecret(rnd: SecureRandom = SecureRandom()): String =
        "${block(rnd, 4)}-${block(rnd, 4)}-${block(rnd, 4)}"

    fun normalize(input: String): String = input.trim().uppercase(Locale.ROOT)

    fun accessKey(tripId: String, secret: String): String {
        val material = "${normalize(tripId)}:${normalize(secret)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Human-friendly time formatting shared by driver and viewer UI. */
object TimeFmt {

    private val clockFmt = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)
    private val clockDayFmt = DateTimeFormatter.ofPattern("EEE hh:mm a", Locale.ENGLISH)

    fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clockFmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun clockWithDay(ms: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val sameDay = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return if (sameDay) clock(ms, zone) else clockDayFmt.format(Instant.ofEpochMilli(ms).atZone(zone))
    }

    /** "42s", "35m", "2h 17m", "1d 3h" */
    fun durationShort(seconds: Long): String {
        if (seconds < 0) return "—"
        val s = seconds
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h ${(s % 3600) / 60}m"
            else -> "${s / 86_400}d ${(s % 86_400) / 3600}h"
        }
    }

    /** "18 sec ago", "35 min ago", "2h 10m ago" */
    fun ago(nowMs: Long, thenMs: Long): String {
        val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s sec ago"
            s < 3600 -> "${s / 60} min ago"
            else -> "${durationShort(s)} ago"
        }
    }

    fun km(meters: Double): String {
        val km = meters / 1000.0
        return if (km >= 100) "${km.toInt()} km" else String.format(Locale.ENGLISH, "%.1f km", km)
    }
}
