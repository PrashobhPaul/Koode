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
}
