package com.trippulse.app

import com.trippulse.app.domain.AvatarFace
import com.trippulse.app.domain.PersonMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * One rule for the traveller's avatar on every surface: photo, else the first
 * letter of their name, else the neutral avatar.
 */
class AvatarFaceTest {

    @Test fun a_loadable_photo_wins_over_the_initial() {
        assertSame(AvatarFace.Photo, AvatarFace.resolve(photoLoadable = true, name = "Prashobh Paul"))
    }

    @Test fun without_a_photo_the_first_letter_of_the_name_stands_in() {
        assertEquals("P", (AvatarFace.resolve(false, "Prashobh Paul") as AvatarFace.Initial).mark.initial)
        assertEquals("N", (AvatarFace.resolve(false, "Nima Roy") as AvatarFace.Initial).mark.initial)
        // A photo that exists but can't be decoded counts as no photo.
        assertEquals("N", (AvatarFace.resolve(false, "  'nima") as AvatarFace.Initial).mark.initial)
    }

    @Test fun with_no_usable_name_the_neutral_avatar_stands_in() {
        assertSame(AvatarFace.Neutral, AvatarFace.resolve(false, null))
        assertSame(AvatarFace.Neutral, AvatarFace.resolve(false, "  "))
        assertSame(AvatarFace.Neutral, AvatarFace.resolve(false, "42"))
    }

    @Test fun the_initial_is_the_first_alphabetic_character() {
        assertEquals("P", PersonMark.initialOf("prashobh"))
        assertEquals("A", PersonMark.initialOf("1 Amma"))
        assertNull(PersonMark.initialOf("123"))
    }
}
