package com.trippulse.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.trippulse.app.R

/**
 * All user-facing notifications (docs/spec/40, 54, 117, 131). Notification
 * content is deliberately minimal for sensitive events — previews never leak
 * medication names or note content.
 */
class Notifier(private val context: Context) {

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_TRACKING, "Trip tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing notification while a trip is being tracked"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, "Trip updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Break, overnight and arrival updates"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SOS, "Emergency", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "SOS / incident alerts"
            }
        )
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent().setClassName(context, "com.trippulse.app.ui.MainActivity")
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    /** The persistent foreground-service notification. */
    fun buildTrackingNotification(origin: String, destination: String): Notification =
        NotificationCompat.Builder(context, CH_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(context.getString(R.string.tracking_notification_title))
            .setContentText("$origin → $destination")
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun postEvent(id: Int, channel: String, title: String, text: String, high: Boolean = false) {
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun showArrival(destination: String) =
        postEvent(ID_ARRIVAL, CH_EVENTS, "Trip completed", "Arrived at $destination.")

    fun showOvernight(destination: String) =
        postEvent(ID_OVERNIGHT, CH_EVENTS, "Overnight rest", "Driver is stopping overnight.")

    fun showSosActive() =
        postEvent(ID_SOS, CH_SOS, "SOS active", "An SOS alert is active for this trip.", high = true)

    fun showResumeHint(origin: String, destination: String) =
        postEvent(ID_RESUME, CH_EVENTS, "Trip active", "Tap to resume tracking $origin → $destination.")

    companion object {
        const val CH_TRACKING = "trippulse.tracking"
        const val CH_EVENTS = "trippulse.events"
        const val CH_SOS = "trippulse.sos"

        const val NOTIF_TRACKING = 1001
        private const val ID_ARRIVAL = 2001
        private const val ID_OVERNIGHT = 2002
        private const val ID_SOS = 2003
        private const val ID_RESUME = 2004
    }
}
