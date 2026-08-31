package com.trippulse.app

import com.trippulse.app.core.DeviceDossier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dossier's pure parts: serialisation, and the honesty about what Android
 * will not provide.
 *
 * The device-reading paths need a real Context and belong in an instrumented
 * test on a phone; what can be pinned here is that a stored dossier round-trips
 * without losing a null or a type, and that the "not available" markers are
 * never quietly dropped.
 */
class DeviceDossierTest {

    @Test fun a_dossier_round_trips_with_types_and_nulls_intact() {
        val original = mapOf(
            "installId" to "abc-123",
            "androidSdk" to 34,
            "publicIp" to "203.0.113.7",
            "imei" to null,
            "imeiNote" to "Not available: Android 10+ blocks IMEI for non-system apps."
        )
        val back = DeviceDossier.fromJson(DeviceDossier.toJson(original))

        assertEquals("abc-123", back["installId"])
        // A number must come back a number, not "34".
        assertEquals(34, (back["androidSdk"] as Number).toInt())
        assertEquals("203.0.113.7", back["publicIp"])
        // The null must survive as a null, so the report can distinguish
        // "sought and unavailable" from "never recorded".
        assertTrue(back.containsKey("imei"))
        assertNull(back["imei"])
        assertEquals(original["imeiNote"], back["imeiNote"])
    }

    @Test fun unreadable_storage_is_an_empty_map_not_a_crash() {
        // A report must still generate if the dossier column is corrupt.
        assertEquals(emptyMap<String, Any?>(), DeviceDossier.fromJson("{ broken"))
        assertEquals(emptyMap<String, Any?>(), DeviceDossier.fromJson(null))
        assertEquals(emptyMap<String, Any?>(), DeviceDossier.fromJson(""))
    }

    @Test fun describe_reads_like_a_police_report_line() {
        assertEquals(
            "Samsung SM-A546E · Android 14",
            DeviceDossier.describe(
                mapOf("manufacturer" to "samsung", "model" to "SM-A546E", "androidRelease" to "14")
            )
        )
        // Motorola, the family's other make, formats the same way.
        assertEquals(
            "Motorola moto g84 · Android 13",
            DeviceDossier.describe(
                mapOf("manufacturer" to "motorola", "model" to "moto g84", "androidRelease" to "13")
            )
        )
    }

    @Test fun describe_survives_a_missing_android_version() {
        assertEquals(
            "Samsung SM-A546E",
            DeviceDossier.describe(mapOf("manufacturer" to "samsung", "model" to "SM-A546E"))
        )
    }
}

/**
 * The SIM tamper classifier — the part of theft detection that can be reasoned
 * about without a phone in hand.
 *
 * The honesty here is the point: a same-carrier swap the fingerprint cannot
 * see still starts with the card leaving the tray, so a confirmed removal is
 * the signal that closes most of that gap — but only a *confirmed* removal,
 * never a failed read dressed up as one.
 */
class SimClassifierTest {
    private val a = "carrier-A"
    private val b = "carrier-B"

    @Test fun a_different_carrier_is_a_change() {
        assertEquals(
            com.trippulse.app.core.DeviceIdentity.SimEvent.CHANGED,
            com.trippulse.app.core.DeviceIdentity.classifySim(a, b, absent = false)
        )
    }

    @Test fun a_confirmed_empty_tray_is_a_removal() {
        // The card was pulled — the commonest tamper, and the first move of an
        // in-place swap.
        assertEquals(
            com.trippulse.app.core.DeviceIdentity.SimEvent.REMOVED,
            com.trippulse.app.core.DeviceIdentity.classifySim(a, null, absent = true)
        )
    }

    @Test fun a_failed_read_is_never_a_removal() {
        // null fingerprint but the OS did not say ABSENT: a modem hiccup, not a
        // theft. Crying wolf here is how the real alert gets ignored.
        assertEquals(
            com.trippulse.app.core.DeviceIdentity.SimEvent.NONE,
            com.trippulse.app.core.DeviceIdentity.classifySim(a, null, absent = false)
        )
    }

    @Test fun the_same_sim_is_nothing() {
        assertEquals(
            com.trippulse.app.core.DeviceIdentity.SimEvent.NONE,
            com.trippulse.app.core.DeviceIdentity.classifySim(a, a, absent = false)
        )
    }

    @Test fun no_baseline_means_nothing_to_compare() {
        // A journey that started with no SIM cannot have one "changed".
        assertEquals(
            com.trippulse.app.core.DeviceIdentity.SimEvent.NONE,
            com.trippulse.app.core.DeviceIdentity.classifySim(null, b, absent = false)
        )
    }
}
