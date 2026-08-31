package com.trippulse.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trippulse.app.core.DeviceDossier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dossier on a real Android runtime.
 *
 * The JVM tests cover its serialisation; these cover the reads that only exist
 * on a device — and, most importantly, prove that the identifiers Android
 * withholds really do come back absent, rather than throwing or returning a
 * fabricated value. That honesty is the whole design, and it can only be
 * confirmed against a real platform.
 */
@RunWith(AndroidJUnit4::class)
class DeviceDossierInstrumentedTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun a_dossier_describes_the_actual_device() {
        val d = DeviceDossier.capture(context)
        // Build fields are always readable; the emulator reports itself just
        // as a Motorola or Samsung phone would.
        assertNotNull(d["manufacturer"])
        assertNotNull(d["model"])
        assertNotNull(d["androidSdk"])
        assertTrue("a real API level", (d["androidSdk"] as Number).toInt() >= 26)
    }

    @Test fun the_install_id_is_stable_across_calls() {
        assertEquals(DeviceDossier.installId(context), DeviceDossier.installId(context))
    }

    @Test fun imei_and_mac_come_back_honestly_absent() {
        // The crux: not a crash, not a fake, but a documented null. If a future
        // Android or a future edit ever started returning these, this test
        // fails and forces the report's wording to be revisited.
        val d = DeviceDossier.capture(context)
        assertNull("IMEI must be unavailable to an ordinary app", d["imei"])
        assertNull("hardware MAC must be unavailable", d["macAddress"])
        assertTrue(d.containsKey("imeiNote"))
        assertTrue(d.containsKey("macNote"))
    }

    @Test fun the_public_ip_lookup_never_throws() = runBlocking {
        // On the CI emulator this may or may not reach the internet; either way
        // it must return a dossier, never blow up, because a report has to be
        // producible even with no network.
        val full = DeviceDossier.withPublicIp(context)
        assertNotNull(full["installId"])
    }
}
