package com.trippulse.app.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Journey credentials — designed for humans, not machines.
 *
 * Every character someone has to read out over the phone, or paste into a text
 * box, is a chance to get it wrong. Dashes and underscores are the worst
 * offenders: a copy-paste that clips one of them silently produces a different
 * credential and an unexplained "not found". So the model is:
 *
 *  - **Journey code** — [CODE_LENGTH] digits and nothing else. The app always
 *    renders it with the fixed [PREFIX], and the viewer's field is a numeric
 *    keypad, so "TP-40381927", "40381927" and "4038 1927" all resolve to the
 *    same journey.
 *  - **Passcode** — [PASSCODE_LENGTH] digits, chosen by the traveller when they
 *    create the journey (a random one is offered so nobody has to think).
 *  - **Access key** — SHA-256(journeyId:passcode). The only value that ever
 *    reaches the backend; the passcode itself is never stored server-side.
 *
 * Credentials issued by older versions used letters and dash groups. They stay
 * valid forever: [resolve] falls back to the legacy normalisation whenever the
 * input isn't purely numeric, and [accessKey] hashes exactly the same material
 * it always did.
 */
object TripCredentials {

    const val PREFIX = "TP-"
    const val CODE_LENGTH = 8
    const val PASSCODE_LENGTH = 6

    /** Legacy alphabet: no I, L, O, 0, 1 — kept so old ids still normalise. */
    private const val LEGACY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    // ---- generation -------------------------------------------------------

    /** A fresh journey code: [CODE_LENGTH] digits, never starting with zero. */
    fun newCode(rnd: SecureRandom = SecureRandom()): String = buildString(CODE_LENGTH) {
        append('1' + rnd.nextInt(9))
        repeat(CODE_LENGTH - 1) { append('0' + rnd.nextInt(10)) }
    }

    /** The shareable journey id, e.g. `TP-40381927`. */
    fun newTripId(rnd: SecureRandom = SecureRandom()): String = PREFIX + newCode(rnd)

    /** A suggested passcode: [PASSCODE_LENGTH] digits, leading zeros allowed. */
    fun newPasscode(rnd: SecureRandom = SecureRandom()): String =
        buildString(PASSCODE_LENGTH) { repeat(PASSCODE_LENGTH) { append('0' + rnd.nextInt(10)) } }

    // ---- normalisation ----------------------------------------------------

    /** Everything that isn't a digit, removed. */
    fun digitsOf(input: String): String = input.filter { it.isDigit() }

    /** Trim + upper-case: the historical normalisation, still used for hashing. */
    fun normalize(input: String): String = input.trim().uppercase(Locale.ROOT)

    /**
     * Turns whatever the viewer typed or pasted into the canonical journey id.
     *
     * Numeric input (with or without the prefix, spaces or stray punctuation)
     * becomes `TP-<digits>`; anything containing letters is treated as a legacy
     * id and only trimmed/upper-cased. Returns null when nothing usable is left.
     */
    fun resolve(rawInput: String): String? {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return null
        val stripped = raw.removePrefix(PREFIX).removePrefix(PREFIX.lowercase(Locale.ROOT))
        val hasLetters = stripped.any { it.isLetter() }
        if (hasLetters) return normalize(raw).takeIf { it.isNotBlank() }
        val digits = digitsOf(stripped)
        return if (digits.isEmpty()) null else PREFIX + digits
    }

    /** True when the code is a complete, well-formed new-style journey code. */
    fun isCompleteCode(digits: String): Boolean =
        digits.length == CODE_LENGTH && digits.all { it.isDigit() }

    /** True when the passcode is a complete, well-formed passcode. */
    fun isCompletePasscode(digits: String): Boolean =
        digits.length == PASSCODE_LENGTH && digits.all { it.isDigit() }

    /** `TP-4038 1927` — grouped for reading aloud, never for machine input. */
    fun pretty(tripId: String): String {
        val digits = digitsOf(tripId)
        if (digits.length != CODE_LENGTH) return tripId
        return PREFIX + digits.substring(0, 4) + " " + digits.substring(4)
    }

    // ---- hashing ----------------------------------------------------------

    /**
     * The capability handed to the backend. Unchanged from earlier versions so
     * journeys created by any release keep resolving to the same key.
     */
    fun accessKey(tripId: String, secret: String): String {
        val material = "${normalize(tripId)}:${normalize(secret)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Retained so historical credentials can still be described in tests/docs. */
    fun legacyAlphabet(): String = LEGACY_ALPHABET
}

/** Human-friendly time formatting shared by traveller and viewer UI. */
object TimeFmt {

    private val clockFmt = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)
    private val clockDayFmt = DateTimeFormatter.ofPattern("EEE hh:mm a", Locale.ENGLISH)
    private val dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH)

    fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clockFmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun date(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateFmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun dateTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateTimeFmt.format(Instant.ofEpochMilli(ms).atZone(zone))

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

    /** Local hour 0..23 for a timestamp — the input every time-of-day rule uses. */
    fun hourOfDay(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        Instant.ofEpochMilli(ms).atZone(zone).hour

    /** Calendar day key ("2026-08-30") used to scope "first meal of the day". */
    fun dayKey(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
}
