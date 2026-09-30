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
            val initial = clean.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
            val key = clean.lowercase()
            // A stable hash (String.hashCode is specified, not per-process).
            val index = if (key.isEmpty()) 0 else Math.floorMod(key.hashCode(), PALETTE.size)
            return PersonMark(initial, PALETTE[index])
        }
    }
}
