package com.trippulse.app.domain

/**
 * What Koode tells a traveller before it starts, in the words it uses
 * everywhere: on the first screen, in Settings, and in the policy. One
 * source, so the app never promises one thing and the policy another.
 *
 * [VERSION] rises whenever the substance changes; a traveller who accepted
 * an older version is asked again. The points are kept short enough to be
 * read, because an unread notice protects nobody. Under GDPR, the UK GDPR,
 * India's DPDP Act, US state laws and Japan's APPI alike, what is required
 * is that the traveller knows what is collected, why, for how long, and
 * how to take it back; every market gets the same screen.
 */
object PrivacyNotice {

    const val VERSION = 1

    const val POLICY_URL = "https://github.com/PrashobhPaul/Koode/blob/main/docs/PRIVACY.md"
    const val TERMS_URL = "https://github.com/PrashobhPaul/Koode/blob/main/docs/TERMS.md"

    /** One line each: what, who, how long, your rights. Pictures carry these on screen. */
    data class Point(val picture: String?, val glyph: String, val title: String, val body: String)

    val POINTS: List<Point> = listOf(
        Point(Pictures.WALK, "📍", "Only during a journey you start",
            "Your position is read while a journey runs and shown by a notification. Never otherwise."),
        Point(null, "👥", "Only with people you approve",
            "Each follower is approved by you, for that journey. They see where you are, your arrival time and the breaks you log."),
        Point(Pictures.STAY, "⏳", "Gone an hour after you end it",
            "Everything shared is deleted from Koode's server one hour after you end the journey. Your own record stays on your phone."),
        Point(null, "🗂️", "Yours to take or erase",
            "Export everything Koode holds about you, or erase it all, any time, from Settings → Privacy & data.")
    )

    /** What is never collected: the sentence people most want to read. */
    const val NEVER = "No account, no ads, no analytics. Contacts, messages, photos and your money tracker never leave your phone."

    /** The one line under the button. */
    const val CONSENT_LINE = "By continuing you agree to the Privacy policy and the Terms."
}
