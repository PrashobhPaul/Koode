package com.trippulse.app.core

import android.content.Context

/**
 * The traveller's profile — the mandatory prerequisite for using Koode.
 *
 * Play-policy-grade onboarding for a personal-safety app: before the first
 * journey the user must provide their name, save at least one location
 * (Home/Office/…) and register at least MIN_CONTACTS of the three emergency
 * contacts. Everything lives in app-private preferences on this device only —
 * never uploaded.
 */
object Profile {

    const val CONTACT_SLOTS = 3
    const val MIN_CONTACTS = 2
    const val MIN_SAVED_PLACES = 1

    data class Contact(val name: String, val phone: String) {
        val filled: Boolean get() = name.isNotBlank() && phone.isNotBlank()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("koode_profile", Context.MODE_PRIVATE)

    fun name(c: Context): String = prefs(c).getString("name", "")?.trim().orEmpty()

    fun setName(c: Context, name: String) {
        prefs(c).edit().putString("name", name.trim()).apply()
    }

    fun contact(c: Context, slot: Int): Contact = Contact(
        prefs(c).getString("contact${slot}_name", "")?.trim().orEmpty(),
        prefs(c).getString("contact${slot}_phone", "")?.trim().orEmpty()
    )

    fun setContact(c: Context, slot: Int, name: String, phone: String) {
        prefs(c).edit()
            .putString("contact${slot}_name", name.trim())
            .putString("contact${slot}_phone", phone.trim())
            .apply()
    }

    fun contacts(c: Context): List<Contact> = (1..CONTACT_SLOTS).map { contact(c, it) }

    fun filledContacts(c: Context): Int = contacts(c).count { it.filled }

    /** What is still missing, in the order shown to the user; empty = complete. */
    fun missing(c: Context, savedPlaceCount: Int): List<String> = buildList {
        if (name(c).isBlank()) add("Your name")
        if (savedPlaceCount < MIN_SAVED_PLACES) add("At least $MIN_SAVED_PLACES saved location (Home / Office / …)")
        val filled = filledContacts(c)
        if (filled < MIN_CONTACTS) add("At least $MIN_CONTACTS emergency contacts (${filled}/$MIN_CONTACTS added)")
    }

    fun isComplete(c: Context, savedPlaceCount: Int): Boolean = missing(c, savedPlaceCount).isEmpty()
}
