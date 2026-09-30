package com.trippulse.app.data.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.trippulse.app.BuildConfig
import com.trippulse.app.data.local.ViewerTripEntity
import com.trippulse.app.data.remote.TripCloud
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * This device's server-push identity as a follower.
 *
 * A follower's phone registers its FCM token against every journey it
 * follows; the server then pushes each of that journey's updates to it, which
 * wakes Koode even when it is closed. That is what makes "everyone the
 * traveller chose hears every update" hold when the follower isn't looking at
 * their phone — the in-app follow service only runs while Koode does.
 *
 * Entirely optional: with no Firebase values in the build, [enabled] is false,
 * nothing here touches Firebase, and followers are updated by the follow
 * service exactly as before. Every call is failure-safe — push is an extra
 * delivery path, never a new way for the app to break.
 */
class PushRegistry(private val context: Context, private val cloud: TripCloud) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when this build carries Firebase client identifiers. */
    val enabled: Boolean = listOf(
        BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_SENDER_ID,
        BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY
    ).all { it.isNotBlank() }

    /** Initialise Firebase from BuildConfig. Idempotent; call from Application. */
    fun init() {
        if (!enabled) return
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .build()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase init failed; push stays off", e)
        }
    }

    /** The current FCM token, or null when push is off or unavailable. */
    suspend fun token(): String? {
        if (!enabled) return null
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        return try {
            withTimeoutOrNull(10_000) { FirebaseMessaging.getInstance().token.await() }
                ?.also { prefs.edit().putString(KEY_TOKEN, it).apply() }
        } catch (e: Exception) {
            Log.w(TAG, "No FCM token yet", e)
            null
        }
    }

    /**
     * Make sure [follow]'s journey pushes to this device. Cheap to call often:
     * once the server has accepted a (journey, token) pair it is remembered
     * and not re-sent. A pending trip-id follower is refused by the server
     * until the traveller approves them, and is simply retried next time.
     */
    suspend fun ensureRegistered(follow: ViewerTripEntity) {
        if (!enabled || follow.expired) return
        val token = token() ?: return
        val key = "${follow.accessKey}|$token"
        val done = prefs.getStringSet(KEY_REGISTERED, emptySet()).orEmpty()
        if (key in done) return
        if (cloud.registerPush(follow.accessKey, token)) {
            prefs.edit().putStringSet(KEY_REGISTERED, (done + key).toList().takeLast(MAX_REGISTERED).toSet()).apply()
        }
    }

    /**
     * FCM rotated this device's token. Forget the old one on the server and
     * drop every registration made with it, so the next pass re-registers each
     * followed journey under the new token.
     */
    suspend fun onNewToken(newToken: String) {
        val old = prefs.getString(KEY_TOKEN, null)
        prefs.edit()
            .putString(KEY_TOKEN, newToken)
            .remove(KEY_REGISTERED)
            .apply()
        if (old != null && old != newToken) {
            runCatching { cloud.unregisterPush(old) }
        }
    }

    private companion object {
        const val TAG = "PushRegistry"
        const val PREFS = "tp_push"
        const val KEY_TOKEN = "fcm_token"
        const val KEY_REGISTERED = "registered"
        /** A follower follows a handful of journeys at most; keep the set small. */
        const val MAX_REGISTERED = 64
    }
}
