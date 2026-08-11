package com.trippulse.app.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.trippulse.app.TripPulseApp
import com.trippulse.app.notifications.Notifier

/**
 * Receives push messages for viewers (docs/spec/40, 117). Cloud Functions fan
 * out a data message to the trip topic on SOS/arrival so viewers are alerted
 * even when the app is not in the foreground. Content is kept minimal.
 */
class TripPulseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val app = applicationContext as? TripPulseApp ?: return
        val data = message.data
        val type = data["type"] ?: message.notification?.title ?: return
        val notifier = app.graph.notifier
        // Types must match the event-type strings the Cloud Functions fan-out
        // sends in the data payload (functions/index.js NOTIFY table).
        when (type) {
            "TRIP_STARTED" -> notifier.showTripStarted()
            "SOS_ACTIVATED", "SOS" -> notifier.showSosActive()
            "ARRIVAL_DETECTED", "ARRIVAL", "TRIP_COMPLETED" ->
                notifier.showArrival(data["destination"] ?: "destination")
            "OVERNIGHT_CONFIRMED", "OVERNIGHT" ->
                notifier.showOvernight(data["destination"] ?: "destination")
            else -> {
                val body = message.notification?.body ?: return
                notifier.showTripUpdate(message.notification?.title ?: "Trip update", body)
            }
        }
    }

    override fun onNewToken(token: String) {
        // Topic subscriptions are used instead of per-device tokens, so nothing
        // to register here for the MVP.
    }
}
