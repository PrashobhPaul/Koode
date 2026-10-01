package com.trippulse.app.domain

/**
 * The initial and colour that stand for a person everywhere in Koode.
 *
 * Pure so every surface — the Home list, the map, the SOS sheet and the
 * notification icon (drawn with Android's Canvas) — agrees exactly: the same
 * name always yields the same letter and the same colour.
 */
data class PersonMark(val initial: String, val argb: Int) {
    companion object {
        /** Koode's person palette: amber, violet, sky, teal, rose. */
        private val PALETTE = intArrayOf(
            0xFFF59E0B.toInt(), 0xFFA78BFA.toInt(), 0xFF38BDF8.toInt(),
            0xFF2DD4BF.toInt(), 0xFFFB7185.toInt()
        )

        fun of(name: String?): PersonMark {
            val clean = name?.trim().orEmpty()
            val initial = initialOf(clean) ?: "•"
            val key = clean.lowercase()
            // A stable hash (String.hashCode is specified, not per-process).
            val index = if (key.isEmpty()) 0 else Math.floorMod(key.hashCode(), PALETTE.size)
            return PersonMark(initial, PALETTE[index])
        }

        /** The first alphabetic character of [name], upper-cased: "Prashobh Paul" → "P". */
        fun initialOf(name: String?): String? =
            name?.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString()
    }
}

/**
 * What stands for a person in an avatar, decided once for every surface — the
 * app bar, the More card, Edit profile, the journey header, the PDF and the
 * follower's view:
 *
 *  1. their photo, when one exists and can actually be loaded;
 *  2. otherwise the first letter of their name, on their colour;
 *  3. otherwise the neutral avatar.
 */
sealed interface AvatarFace {
    object Photo : AvatarFace
    data class Initial(val mark: PersonMark) : AvatarFace
    object Neutral : AvatarFace

    companion object {
        fun resolve(photoLoadable: Boolean, name: String?): AvatarFace = when {
            photoLoadable -> Photo
            PersonMark.initialOf(name) != null -> Initial(PersonMark.of(name))
            else -> Neutral
        }
    }
}
