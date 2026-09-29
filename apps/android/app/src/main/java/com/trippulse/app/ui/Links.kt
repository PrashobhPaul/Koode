package com.trippulse.app.ui

/**
 * The two links Koode hands to other people.
 *
 * Kept in one place because they appear in the share message, the credentials
 * screen and the About section, and a journey invitation that points at a
 * stale URL is an invitation that quietly fails.
 */
object Links {

    /** The browser viewer, for followers who will never install an app. */
    const val WEB_VIEWER = "https://prashobhpaul.github.io/Koode/"

    /** Direct APK download for anyone who does want the app. */
    const val APK = "https://github.com/PrashobhPaul/Koode/releases/latest/download/Koode.apk"

    /** The project itself, for the privacy policy and terms. */
    const val REPO = "https://github.com/PrashobhPaul/Koode"

    /**
     * A one-tap "watch this journey" link. The credential rides in the URL
     * fragment (`#<code>-<passcode>`) — the part a browser never sends to the
     * server — and the web viewer and the Koode app both already know how to
     * read it, so tapping the link opens live tracking with nothing to type.
     * Whoever holds the link can watch, exactly like the passcode itself, so it
     * is only ever put in a message the traveller sends to their own circle.
     */
    fun follow(code: String, passcode: String): String {
        val c = code.filter { it.isDigit() }
        val p = passcode.filter { it.isDigit() }
        return if (c.isNotBlank() && p.isNotBlank()) "$WEB_VIEWER#$c-$p" else WEB_VIEWER
    }
}
