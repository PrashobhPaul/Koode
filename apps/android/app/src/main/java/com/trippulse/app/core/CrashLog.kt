package com.trippulse.app.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.trippulse.app.BuildConfig
import java.io.File
import java.util.Date

/**
 * Keeps the technical details of the last crash on this phone.
 *
 * Koode is installed from an APK and run by people with no way to read a
 * device log, so a crash would otherwise leave nothing to fix it with. The
 * details are written to app-private storage and only ever leave the phone if
 * the traveller taps "Share details" on the Home screen. No analytics service,
 * no account, no cost.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                File(app.filesDir, FILE).writeText(buildString {
                    append("Koode ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                    append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n")
                    append("When: ${Date()}\nThread: ${thread.name}\n\n")
                    append(Log.getStackTraceString(e))
                })
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun read(c: Context): String? = runCatching { File(c.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(c: Context) { runCatching { File(c.filesDir, FILE).delete() } }
}
