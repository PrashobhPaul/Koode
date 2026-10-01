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
            NotificationChannel(CH_EVENTS, "Journey updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Starts, stops, halts, plan changes and arrivals on your journeys and the ones you follow"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_COACH, "Wellbeing", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Gentle water, food and break suggestions on your journeys, and wellbeing updates on the ones you follow"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_COMPLETION, "Journey completion", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Arrival, closing and review of your journeys, and the approved report of the ones you follow"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EXPENSE, "Expense reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Private prompts to note what a stop cost you — never while you drive"
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

    /** Quiet ongoing notification while following someone else's trip. */
    fun buildFollowNotification(): Notification =
        NotificationCompat.Builder(context, CH_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle("Following trip")
            .setContentText("You'll be alerted on every journey update — starts, stops, tolls, arrival and more.")
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    /**
     * The persistent foreground-service notification.
     *
     * Android *requires* a location foreground service to show one; it cannot
     * be hidden, and trying would be both futile and against the platform's
     * design. What can be controlled is how much it gives away.
     *
     * A thief who powers on a stolen phone should learn nothing from a glance.
     * So the route, the destination and the journey number are gone from here:
     * the title is generic, there is no body, and VISIBILITY_SECRET keeps it
     * off the lock screen entirely, where a locked phone would otherwise show
     * it. The traveller, who opened the app and knows what it does, loses
     * nothing they need; the thief, who does not, is told only that some
     * background service is running -- true of dozens of apps.
     */
    fun buildTrackingNotification(origin: String, destination: String): Notification =
        NotificationCompat.Builder(context, CH_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(context.getString(R.string.tracking_notification_title))
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .build()

    private fun postEvent(
        id: Int, channel: String, title: String, text: String,
        high: Boolean = false, onlyAlertOnce: Boolean = false,
        largeIcon: android.graphics.Bitmap? = null
    ) {
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setLargeIcon(largeIcon)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(onlyAlertOnce)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /**
     * Arrival was *detected*: Koode asks the traveller to close — it never
     * closes on its own at this point. Kept in the tray (ongoing, high
     * priority) until they end the journey or say they're still travelling.
     */
    fun showClosePrompt(destination: String, stronger: Boolean) {
        val body = com.trippulse.app.domain.JourneyClosure.closePromptBody(stronger)
        val n = NotificationCompat.Builder(context, CH_COMPLETION)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(com.trippulse.app.domain.JourneyClosure.closePromptTitle(destination))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(!stronger)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentIntent())
            .addAction(0, "End journey", contentIntent())
            .addAction(0, "I'm still travelling", nudgeAction(NudgeActionReceiver.ACTION_STILL_TRAVELLING, "journey", ID_ARRIVAL_DETECTED))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_ARRIVAL_DETECTED, n)
    }

    fun cancelClosePrompt() {
        context.getSystemService(NotificationManager::class.java).cancel(ID_ARRIVAL_DETECTED)
    }

    /**
     * The journey is closed and waiting for the traveller's review. Nothing
     * reaches the people following it until they approve it.
     */
    fun showReviewPrompt(destination: String, auto: Boolean) {
        val body = if (auto) "Your journey to $destination was closed after you arrived. Review it — it's shared with the people following it only once you approve."
            else "Check what Koode recorded. It's shared with the people following your journey only once you approve."
        val n = NotificationCompat.Builder(context, CH_COMPLETION)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle("Review your journey")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_ARRIVAL, n)
    }

    fun cancelReviewPrompt() {
        context.getSystemService(NotificationManager::class.java).cancel(ID_ARRIVAL)
    }

    /**
     * A long stop was detected. Koode asks — it never assumes a long stop is a
     * problem, or guesses why. "Taking a halt" opens Koode to say what kind;
     * "Just a break" answers from the notification.
     */
    fun showHaltQuestion() {
        val b = NotificationCompat.Builder(context, CH_COACH)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle("Taking a longer break?")
            .setContentText("Looks like you've stopped for a while. Are you taking a halt?")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Looks like you've stopped for a while. Are you taking a halt?"))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, "Taking a halt", contentIntent())
            .addAction(0, "Just a break", nudgeAction(NudgeActionReceiver.ACTION_HALT_DECLINE, "halt", ID_OVERNIGHT))
        context.getSystemService(NotificationManager::class.java).notify(ID_OVERNIGHT, b.build())
    }

    fun cancelHaltQuestion() {
        context.getSystemService(NotificationManager::class.java).cancel(ID_OVERNIGHT)
    }

    /** The long-haul suggestion: plan an overnight halt. A suggestion, not a command. */
    fun showHaltPlanSuggestion(text: String) {
        val b = NotificationCompat.Builder(context, CH_COACH)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle("A long journey ahead")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, "Plan a halt", contentIntent())
        context.getSystemService(NotificationManager::class.java).notify(ID_HALT_PLAN, b.build())
    }

    fun showSosActive() =
        postEvent(ID_SOS, CH_SOS, "SOS active", "An SOS alert is active for this trip.", high = true)

    fun showResumeHint(origin: String, destination: String) =
        postEvent(ID_RESUME, CH_EVENTS, "Trip active", "Tap to resume tracking $origin → $destination.")

    fun showTripStarted() =
        postEvent(ID_STARTED, CH_EVENTS, "Trip started", "The driver has started the trip.")

    /** Driver-side nudge the moment a genuine stop is confirmed. */
    fun showBreakPrompt(privateVehicle: Boolean) =
        postEvent(
            ID_BREAK, CH_EVENTS, "Taking a break?",
            if (privateVehicle) "Log food, water, rest — and fuel if you refilled. It takes two taps."
            else "Log food, water or rest so the people following your journey stay informed. Two taps."
        )

    fun showTripUpdate(title: String, body: String) =
        postEvent(ID_UPDATE, CH_EVENTS, title, body)

    /**
     * One event on a followed journey, shown to a Circle member as its own tray
     * entry. [id] is derived from the event (see [com.trippulse.app.domain.FollowerAlerts.notificationId])
     * so distinct events stack rather than overwrite each other, and a re-seen
     * event replaces itself instead of duplicating. Urgent events ring on the
     * high-importance channel.
     */
    fun showJourneyEvent(
        id: Int, title: String, body: String, urgent: Boolean = false,
        important: Boolean = false, person: String? = null,
        category: com.trippulse.app.domain.FollowerAlerts.Category = com.trippulse.app.domain.FollowerAlerts.Category.JOURNEY
    ) =
        postEvent(
            id,
            when {
                urgent -> CH_SOS
                category == com.trippulse.app.domain.FollowerAlerts.Category.WELLBEING -> CH_COACH
                category == com.trippulse.app.domain.FollowerAlerts.Category.COMPLETION -> CH_COMPLETION
                else -> CH_EVENTS
            },
            title, body,
            // A plan change (new destination, mode, halt) heads the tray too.
            high = urgent || important, onlyAlertOnce = !urgent,
            largeIcon = person?.let { personIcon(it) }
        )

    /**
     * The person's initial on their colour — the same mark Koode shows for
     * them on Home and in the circle list, so a notification is recognisable
     * at a glance before it is read.
     */
    private fun personIcon(name: String): android.graphics.Bitmap {
        val mark = com.trippulse.app.domain.PersonMark.of(name)
        val size = (64 * context.resources.displayMetrics.density).toInt().coerceAtLeast(64)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = mark.argb
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = 0xFF07131D.toInt()
        paint.textSize = size * 0.44f
        paint.typeface = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(context, R.font.sora)
        }.getOrNull()?.let { android.graphics.Typeface.create(it, android.graphics.Typeface.BOLD) }
            ?: android.graphics.Typeface.DEFAULT_BOLD
        paint.textAlign = android.graphics.Paint.Align.CENTER
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(mark.initial, size / 2f, y, paint)
        return bmp
    }

    /**
     * A wellbeing suggestion to the traveller. Each need keeps one tray entry
     * that the reminder replaces. "Had water" / "Ate something" log it
     * straight from the notification, so the traveller never has to open
     * Koode at a stop; "Taking a break" tells the coach a stop is coming;
     * "Remind me later" snoozes it.
     */
    fun showWellbeingNudge(needKey: String, title: String, body: String) {
        val id = ID_COACH_BASE + (needKey.hashCode() and 0xFF)
        val b = NotificationCompat.Builder(context, CH_COACH)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        val done = when (needKey) {
            "water" -> "Had water"
            "food" -> "Ate something"
            "break" -> "Taking a break"
            else -> null
        }
        if (done != null) {
            b.addAction(0, done, nudgeAction(NudgeActionReceiver.ACTION_DONE, needKey, id))
        }
        b.addAction(0, "Remind me later", nudgeAction(NudgeActionReceiver.ACTION_SNOOZE, needKey, id))
        context.getSystemService(NotificationManager::class.java).notify(id, b.build())
    }

    fun cancelWellbeingNudge(needKey: String) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(ID_COACH_BASE + (needKey.hashCode() and 0xFF))
    }

    private fun nudgeAction(action: String, needKey: String, notificationId: Int): PendingIntent {
        val intent = Intent(context, NudgeActionReceiver::class.java)
            .setAction(action)
            .putExtra(NudgeActionReceiver.EXTRA_NEED, needKey)
            .putExtra(NudgeActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, (action + needKey).hashCode(), intent, flags)
    }

    /**
     * "How much did you spend on food?" — private, only ever shown when it is
     * safe to answer (never to a driver on the move). The amount can be typed
     * straight into the notification; "No expense" is a real answer and
     * "Skip for now" keeps it open for the review.
     */
    fun showExpensePrompt(tripId: String, o: com.trippulse.app.domain.Expenses.Opportunity) {
        val id = expenseNotificationId(o.id)
        fun action(a: String, mutable: Boolean = false): PendingIntent {
            val intent = Intent(context, NudgeActionReceiver::class.java)
                .setAction(a)
                .putExtra(NudgeActionReceiver.EXTRA_TRIP, tripId)
                .putExtra(NudgeActionReceiver.EXTRA_OPPORTUNITY, o.id)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or when {
                mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_MUTABLE
                !mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> PendingIntent.FLAG_IMMUTABLE
                else -> 0
            }
            return PendingIntent.getBroadcast(context, (a + o.id).hashCode(), intent, flags)
        }
        val input = androidx.core.app.RemoteInput.Builder(NudgeActionReceiver.KEY_AMOUNT)
            .setLabel("Amount")
            .build()
        val enter = NotificationCompat.Action.Builder(0, "Enter amount", action(NudgeActionReceiver.ACTION_EXPENSE_AMOUNT, mutable = true))
            .addRemoteInput(input)
            .build()
        val body = "${o.category.emoji} ${o.label} · private to you"
        val n = NotificationCompat.Builder(context, CH_EXPENSE)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(com.trippulse.app.domain.Expenses.question(o))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(enter)
            .addAction(0, "No expense", action(NudgeActionReceiver.ACTION_EXPENSE_NONE))
            .addAction(0, "Skip for now", action(NudgeActionReceiver.ACTION_EXPENSE_SKIP))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun cancelExpensePrompt(opportunityId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(expenseNotificationId(opportunityId))
    }

    private fun expenseNotificationId(opportunityId: String) = ID_EXPENSE_BASE + (opportunityId.hashCode() and 0x3FF)

    /** Journey Health dropped to CONCERN on a followed journey. */
    fun showJourneyAttention(label: String, reason: String) =
        postEvent(ID_HEALTH, CH_SOS, "Journey needs attention", "$reason ($label)", high = true)

    fun showJourneyBackToNormal(label: String) =
        postEvent(ID_HEALTH, CH_EVENTS, "Journey back to normal", "$label is progressing normally again.")

    /** A newer Koode build is available for download. */
    fun showUpdateAvailable(versionName: String) =
        postEvent(
            ID_UPDATE_AVAILABLE, CH_EVENTS, "Koode $versionName is available",
            "Open Koode to update. Journeys in progress are never affected."
        )

    companion object {
        const val CH_TRACKING = "trippulse.tracking"
        const val CH_EVENTS = "trippulse.events"
        const val CH_SOS = "trippulse.sos"
        const val CH_COACH = "trippulse.coach"
        const val CH_COMPLETION = "koode.completion"
        const val CH_EXPENSE = "koode.expense"

        const val NOTIF_TRACKING = 1001
        private const val ID_ARRIVAL = 2001
        private const val ID_OVERNIGHT = 2002
        private const val ID_SOS = 2003
        private const val ID_RESUME = 2004
        private const val ID_STARTED = 2005
        private const val ID_UPDATE = 2006
        private const val ID_HEALTH = 2007
        private const val ID_BREAK = 2008
        private const val ID_ARRIVAL_DETECTED = 2009
        private const val ID_UPDATE_AVAILABLE = 2010
        private const val ID_HALT_PLAN = 2011
        /** 3000–4023: one tray entry per expense prompt. */
        private const val ID_EXPENSE_BASE = 3000
        /** 2100–2355: one tray entry per wellbeing need. */
        private const val ID_COACH_BASE = 2100
    }
}
