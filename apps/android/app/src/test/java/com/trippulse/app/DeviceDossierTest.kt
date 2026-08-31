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
