package com.trippulse.app.data.update

import android.content.Context
import com.trippulse.app.BuildConfig
import com.trippulse.app.core.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Keeps everyone on the current build.
 *
 * Koode ships as a direct APK, so there is no store to nag on our behalf: a
 * phone that installed v5 a year ago will happily run v5 forever, and its owner
 * never learns that the "journey ended" bug they hit was fixed months ago. This
 * checker closes that gap with the lightest possible mechanism — one unauthenticated
 * GET against the repository's latest GitHub release, at most once a day.
 *
 * Three rules, all of which exist to protect a journey in progress:
 *
 *  1. It is **advisory**. Koode never blocks, never force-updates and never
 *     interrupts. An available update is a card the user can dismiss.
 *  2. It is **silent on failure**. No network, rate-limited, malformed JSON —
 *     all of it resolves to "no update known", never to an error the user sees.
 *  3. It **never touches journey data**. Installing an update replaces code,
 *     while every journey lives in Room and app-private preferences, upgraded
 *     by additive migrations (see TripPulseDb). A traveller can update
 *     mid-journey, or a follower mid-watch, and lose nothing.
 */
class UpdateChecker(
    context: Context,
    private val settings: SettingsStore,
    private val releasesUrl: String = DEFAULT_RELEASES_URL
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** A newer build than the one running, as far as we can tell. */
    data class Available(
        val versionName: String,
        val downloadUrl: String,
        val notes: String?
    )

    /** The version currently installed, for the About screen. */
    val installedVersion: String get() = BuildConfig.VERSION_NAME

    /**
     * Returns a newer release, or null. Never throws.
     *
     * @param force ignore the once-a-day throttle (used by "Check now").
     */
    suspend fun check(force: Boolean = false): Available? = withContext(Dispatchers.IO) {
        if (!settings.current.checkForUpdates && !force) return@withContext null
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) {
            return@withContext cached()
        }
        val release = fetchLatest() ?: return@withContext cached()
        prefs.edit()
            .putLong(KEY_LAST_CHECK, now)
            .putString(KEY_VERSION, release.versionName)
            .putString(KEY_URL, release.downloadUrl)
            .putString(KEY_NOTES, release.notes)
            .apply()
        release.takeIf { isNewer(it.versionName, installedVersion) }
    }

    /** The last known result, so the UI can render instantly and offline. */
    fun cached(): Available? {
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val url = prefs.getString(KEY_URL, null) ?: return null
        if (!isNewer(version, installedVersion)) return null
        if (prefs.getString(KEY_DISMISSED, null) == version) return null
        return Available(version, url, prefs.getString(KEY_NOTES, null))
    }

    /** "Not now" — stop showing this specific version. */
    fun dismiss(versionName: String) {
        prefs.edit().putString(KEY_DISMISSED, versionName).apply()
    }

    private fun fetchLatest(): Available? = try {
        val req = Request.Builder()
            .url(releasesUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Koode/${BuildConfig.VERSION_NAME} (Android)")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else parse(resp.body?.string())
        }
    } catch (_: Exception) {
        null
    }

    private fun parse(body: String?): Available? {
        if (body.isNullOrBlank()) return null
        return try {
            val root = JSONObject(body)
            val tag = root.optString("tag_name").ifBlank { root.optString("name") }
            if (tag.isBlank()) return null
            val assets = root.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apk = a.optString("browser_download_url").ifBlank { null }
                        if (name.startsWith("Koode", ignoreCase = true)) break
                    }
                }
            }
            val url = apk ?: root.optString("html_url").ifBlank { null } ?: return null
            Available(normalizeVersion(tag), url, root.optString("body").ifBlank { null })
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val PREFS = "koode_updates"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_VERSION = "latest_version"
        const val KEY_URL = "latest_url"
        const val KEY_NOTES = "latest_notes"
        const val KEY_DISMISSED = "dismissed_version"
        const val CHECK_INTERVAL_MS = 24L * 3600 * 1000

        const val DEFAULT_RELEASES_URL =
            "https://api.github.com/repos/PrashobhPaul/Koode/releases/latest"

        /** Strips a leading "v" and any build suffix GitHub added to the tag. */
        fun normalizeVersion(tag: String): String =
            tag.trim().removePrefix("v").removePrefix("V").removePrefix("build-").trim()

        /**
         * Semantic-ish comparison that degrades gracefully: a tag we cannot
         * parse is never treated as newer, so a stray release name can't
         * produce a permanent "update available" banner.
         */
        fun isNewer(candidate: String, installed: String): Boolean {
            val a = versionParts(candidate) ?: return false
            val b = versionParts(installed) ?: return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        fun versionParts(raw: String): List<Int>? {
            val cleaned = normalizeVersion(raw).takeWhile { it.isDigit() || it == '.' }
            if (cleaned.isBlank()) return null
            return cleaned.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() }
        }
    }
}
