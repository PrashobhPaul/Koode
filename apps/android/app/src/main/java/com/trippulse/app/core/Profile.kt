package com.trippulse.app.core

import android.content.Context
import android.net.Uri
import java.io.File

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

    /**
     * Which silhouette stands in when the traveller hasn't added a photo. It is
     * only a placeholder choice — not a stored personal attribute — so it never
     * gates onboarding and is never uploaded.
     */
    enum class AvatarStyle { NEUTRAL, MALE, FEMALE;
        companion object {
            fun from(raw: String?): AvatarStyle {
                val key = raw?.trim()?.uppercase()
                return AvatarStyle.entries.firstOrNull { it.name == key } ?: AvatarStyle.NEUTRAL
            }
        }
    }

    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * The canonical profile revision: bumped on every change to the photo,
     * name or avatar style. Every avatar observes this one value, so a photo
     * added, changed or removed anywhere shows everywhere at once.
     */
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = _revision

    private fun changed() { _revision.value = _revision.value + 1 }

    private fun photoFile(c: Context) = File(c.applicationContext.filesDir, "profile_photo.jpg")

    /** The saved photo file, or null when none has been added. */
    fun photoPath(c: Context): String? = photoFile(c).takeIf { it.exists() && it.length() > 0 }?.absolutePath

    fun hasPhoto(c: Context): Boolean = photoPath(c) != null

    /** Copy a picked image into app-private storage. Returns true on success. */
    fun savePhoto(c: Context, uri: Uri): Boolean = try {
        c.contentResolver.openInputStream(uri)?.use { input ->
            photoFile(c).outputStream().use { input.copyTo(it) }
        }
        // Bump so anything keyed on this value recomposes with the new photo.
        prefs(c).edit().putLong("photo_updated", System.currentTimeMillis()).apply()
        changed()
        hasPhoto(c)
    } catch (_: Exception) {
        false
    }

    fun clearPhoto(c: Context) {
        runCatching { photoFile(c).delete() }
        prefs(c).edit().putLong("photo_updated", System.currentTimeMillis()).apply()
        changed()
    }

    /**
     * Decodes the photo at roughly the size it is shown, so a 30 dp app-bar
     * avatar never holds a full-resolution camera image. Null when the file is
     * missing or not a loadable image — every avatar then falls back to the
     * initial.
     */
    fun decodePhoto(path: String, targetPx: Int): android.graphics.Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
        android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Changes whenever the photo is added or removed, for Compose keys. */
    fun photoVersion(c: Context): Long = prefs(c).getLong("photo_updated", 0L)

    fun avatarStyle(c: Context): AvatarStyle = AvatarStyle.from(prefs(c).getString("avatar_style", null))

    fun setAvatarStyle(c: Context, style: AvatarStyle) {
        prefs(c).edit().putString("avatar_style", style.name).apply()
        changed()
    }

    data class Contact(val name: String, val phone: String) {
        val filled: Boolean get() = name.isNotBlank() && phone.isNotBlank()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("koode_profile", Context.MODE_PRIVATE)

    fun name(c: Context): String = prefs(c).getString("name", "")?.trim().orEmpty()

    fun setName(c: Context, name: String) {
        prefs(c).edit().putString("name", name.trim()).apply()
        changed()
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

    /**
     * What is still missing, in the order shown to the user; empty = complete.
     * Saved locations are deliberately NOT mandatory — older users found
     * adding them inconvenient; they're a convenience, not a gate.
     */
    fun missing(c: Context, @Suppress("UNUSED_PARAMETER") savedPlaceCount: Int = 0): List<String> = buildList {
        if (name(c).isBlank()) add("Your name")
        val filled = filledContacts(c)
        if (filled < MIN_CONTACTS) add("At least $MIN_CONTACTS emergency contacts (${filled}/$MIN_CONTACTS added)")
    }

    fun isComplete(c: Context, savedPlaceCount: Int = 0): Boolean = missing(c, savedPlaceCount).isEmpty()

    /** Case-insensitive check used to auto-approve the user's circle. */
    fun isCircleName(c: Context, viewerName: String): Boolean {
        val n = viewerName.trim().lowercase()
        if (n.isBlank()) return false
        return contacts(c).any { it.filled && it.name.trim().lowercase() == n }
    }
}
