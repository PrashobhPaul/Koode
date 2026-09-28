package com.trippulse.app

import com.trippulse.app.ui.SharedPlaceInbox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedPlaceInboxTest {

    /** Stands in for SharedPreferences: survives a "process death" (memory reset). */
    private class PhoneStore : SharedPlaceInbox.Store {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    private lateinit var disk: PhoneStore
    private var now = 1_000_000L
    private val link = "LuLu Mall\nhttps://maps.app.goo.gl/AbCdEf12345"

    @Before fun setUp() {
        disk = PhoneStore()
        SharedPlaceInbox.useStoreForTest(disk)
        SharedPlaceInbox.clock = { now }
    }

    @After fun tearDown() { SharedPlaceInbox.clock = System::currentTimeMillis }

    /** Android kills Koode while Maps is open: only what was persisted remains. */
    private fun processDeath() = SharedPlaceInbox.useStoreForTest(disk)

    @Test fun copied_link_is_picked_up_on_return_and_lands_in_the_waiting_field() {
        SharedPlaceInbox.target = SharedPlaceInbox.Target(1, asStart = true)
        SharedPlaceInbox.beginGoogleHandoff("some old note")
        now += 60_000
        assertTrue(SharedPlaceInbox.acceptReturnedClip(link))
        val (text, where) = SharedPlaceInbox.take()!!
        assertEquals(link, text)
        assertEquals(SharedPlaceInbox.Target(1, true), where)
        assertFalse("hand-off is finished", SharedPlaceInbox.isAwaiting())
    }

    @Test fun survives_koode_being_closed_while_in_google_maps() {
        SharedPlaceInbox.target = SharedPlaceInbox.Target(0, asStart = true)
        SharedPlaceInbox.beginGoogleHandoff(null)
        processDeath()
        now += 5 * 60_000
        assertTrue(SharedPlaceInbox.isAwaiting())
        assertTrue(SharedPlaceInbox.acceptReturnedClip(link))
        assertEquals(SharedPlaceInbox.Target(0, true), SharedPlaceInbox.take()!!.second)
    }

    @Test fun the_clipboard_from_before_leaving_is_never_used() {
        SharedPlaceInbox.beginGoogleHandoff(link)
        assertFalse(SharedPlaceInbox.acceptReturnedClip(link))
        assertNull(SharedPlaceInbox.take())
    }

    @Test fun the_same_place_is_never_applied_twice() {
        SharedPlaceInbox.beginGoogleHandoff(null)
        assertTrue(SharedPlaceInbox.acceptReturnedClip(link))
        SharedPlaceInbox.take()
        SharedPlaceInbox.beginGoogleHandoff(null)
        assertFalse(SharedPlaceInbox.acceptReturnedClip(link))
    }

    @Test fun a_stale_hand_off_expires() {
        SharedPlaceInbox.beginGoogleHandoff(null)
        now += 21 * 60_000
        assertFalse(SharedPlaceInbox.acceptReturnedClip(link))
    }

    @Test fun ordinary_text_is_ignored() {
        SharedPlaceInbox.beginGoogleHandoff(null)
        assertFalse(SharedPlaceInbox.acceptReturnedClip("call amma at 6"))
        assertTrue(SharedPlaceInbox.acceptReturnedClip("10.5276, 76.2144"))
    }
}
