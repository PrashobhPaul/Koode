package com.trippulse.app.data.export

import android.content.Context
import com.trippulse.app.TripPulseApp
import com.trippulse.app.core.DeviceDossier
import com.trippulse.app.core.LocationFix
import com.trippulse.app.core.Profile
import com.trippulse.app.core.TripCredentials
import com.trippulse.app.data.EventCodec
import com.trippulse.app.data.export.report.Paginator
import com.trippulse.app.data.export.report.Report
import com.trippulse.app.data.export.report.Reports
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.data.local.EventEntity
import com.trippulse.app.data.local.ExpenseEntity
import com.trippulse.app.data.local.LocationSampleEntity
import com.trippulse.app.domain.DarkAssessment
import com.trippulse.app.domain.Darkness
import com.trippulse.app.domain.EventSource
import com.trippulse.app.domain.Expenses
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.JourneyAnalytics
import com.trippulse.app.domain.Measures
import com.trippulse.app.domain.TripEvent
import com.trippulse.app.domain.report.JourneyStory
import com.trippulse.app.domain.report.PlaceBook
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Builds the three reports from what the phone holds.
 *
 * One place for it, because four screens make PDFs (the journey screen's
 * send, the summary's two exports, the approved report published for
 * followers, and the follower's emergency document) and two copies of this
 * would drift into two different reports of the same journey.
 *
 * Naming happens here: saved places first, then the names the journey
 * recorded as it went, then the phone's own geocoder for the handful of
 * points still unnamed, with a firm time budget so a slow network never holds
 * a report hostage. A point nobody could name is described by distance from
 * the nearest one that could ("about 12 km from Tirupur"), never as numbers.
 */
object ReportFactory {

    /** The shareable journey report: never money. */
    suspend fun journey(
        context: Context,
        trip: ActiveTripEntity,
        events: List<EventEntity>,
        samples: List<LocationSampleEntity>,
        analytics: JourneyAnalytics.JourneyReport?,
        measures: Measures,
        originLabel: String? = null,
        destLabel: String? = null,
        fastagSummary: String? = null
    ): Report {
        val input = input(context, trip, events, samples, analytics, originLabel, destLabel, measures)
        val book = book(context, input)
        val story = JourneyStory.build(input, book)
        val map = MapSnapshots.backdrop(context, samples.map { it.lat to it.lng }, Paginator.PAGE_W - Paginator.MARGIN * 2, 214f)
        return Reports.journey(
            Reports.JourneyInput(story, input, book, analytics, TripCredentials.pretty(trip.tripId), measures, System.currentTimeMillis(), fastagSummary, mapBackdrop = map)
        )
    }

    /** The traveller's own expense report. */
    suspend fun expenses(
        context: Context,
        trip: ActiveTripEntity,
        events: List<EventEntity>,
        samples: List<LocationSampleEntity>,
        analytics: JourneyAnalytics.JourneyReport,
        expenses: List<ExpenseEntity>,
        measures: Measures,
        opportunities: List<Expenses.Opportunity> = emptyList(),
        passCrossings: Int = 0,
        approvedAtMs: Long? = null,
        originLabel: String? = null,
        destLabel: String? = null
    ): Report {
        val input = input(context, trip, events, samples, analytics, originLabel, destLabel, measures)
        val book = book(context, input)
        val story = JourneyStory.build(input, book)
        return Reports.expenses(
            Reports.ExpenseInput(
                story, input, book, analytics,
                expenses.map {
                    Reports.Expense(Expenses.Category.fromType(it.type), it.item, it.amount, it.quantity, it.unit, it.tMs, it.note)
                },
                opportunities, passCrossings, approvedAtMs, TripCredentials.pretty(trip.tripId), measures, System.currentTimeMillis()
            )
        )
    }

    /**
     * The follower's emergency document, from what reached the server: the
     * journey's details, its last live state and its shared timeline.
     */
    suspend fun lastKnown(
        context: Context,
        meta: Map<String, Any?>?,
        state: Map<String, Any?>?,
        events: List<Map<String, Any?>>,
        assessment: DarkAssessment,
        fallbackRef: String
    ): Report {
        fun mStr(k: String) = (meta?.get(k) as? String)?.takeIf { it.isNotBlank() }
        fun mNum(k: String) = (meta?.get(k) as? Number)?.toDouble()
        fun sNum(k: String) = (state?.get(k) as? Number)?.toDouble()
        val now = System.currentTimeMillis()
        val tripEvents = events.mapNotNull(::cloudEvent)
        // The follower has no location log, only the positions events carried and the last state.
        val points = (tripEvents.mapNotNull { e -> if (e.lat != null && e.lng != null) JourneyStory.Sample(e.eventTimeMs, e.lat!!, e.lng!!) else null } +
            listOfNotNull(sNum("lat")?.let { la -> sNum("lng")?.let { lo -> JourneyStory.Sample(sNum("lastLocationAt")?.toLong() ?: now, la, lo) } }))
            .sortedBy { it.tMs }
        val who = mStr("ownerName") ?: mStr("label")
        val input = JourneyStory.Input(
            who = who,
            origin = mStr("origin") ?: "the start",
            destination = mStr("destination") ?: "the destination",
            originLat = mNum("originLat"), originLng = mNum("originLng"),
            destLat = mNum("destLat"), destLng = mNum("destLng"),
            mode = mStr("transportMode") ?: "CAR",
            startedAtMs = (meta?.get("startedAt") as? Number)?.toLong() ?: points.firstOrNull()?.tMs ?: now,
            endedAtMs = null,
            nowMs = now,
            events = tripEvents,
            samples = points,
            distanceM = sNum("distanceCoveredM") ?: 0.0,
            routeDistanceM = mNum("totalRouteDistanceM")
        )
        val book = book(context, input, extra = listOfNotNull(sNum("lat")?.let { la -> sNum("lng")?.let { lo -> la to lo } }))
        // This document is needed most when things have gone wrong; an odd
        // timeline must never be the reason it cannot be made.
        val story = runCatching { JourneyStory.build(input, book) }
            .getOrElse { JourneyStory.build(input.copy(events = emptyList()), book) }
        @Suppress("UNCHECKED_CAST")
        val device = (meta?.get("device") as? Map<String, Any?>).orEmpty()
        val ref = mStr("tripId")?.let { TripCredentials.pretty(it) } ?: fallbackRef
        val name = JourneyStory.name(who)
        val map = MapSnapshots.backdrop(context, points.map { it.lat to it.lng }, Paginator.PAGE_W - Paginator.MARGIN * 2, 190f)
        return Reports.lastKnown(
            Reports.LastKnownInput(
                story = story, input = input, book = book, tripRef = ref,
                lat = sNum("lat"), lng = sNum("lng"), accuracyM = sNum("accuracy"), speedKmh = sNum("speedKmh"),
                fixAtMs = sNum("lastLocationAt")?.toLong(),
                headline = Darkness.headline(assessment, name),
                detail = Darkness.detail(assessment),
                lastContactMs = assessment.sinceMs,
                silentMs = if (assessment.dark) assessment.elapsedMs else 0L,
                batteryPct = assessment.lastBatteryPct,
                simChangedAtMs = sNum("simChangedAt")?.toLong(),
                device = deviceRows(device),
                deviceNote = if (device.isEmpty()) null else
                    "Android does not let ordinary apps read the IMEI or the hardware MAC address; Koode never sees them. " +
                        "The carrier can identify the phone from the public IP address and the times above.",
                preparedAtMs = now,
                mapBackdrop = map
            )
        )
    }

    /** The story of a journey the phone holds, for the app's own screens. */
    suspend fun storyFor(
        context: Context,
        trip: ActiveTripEntity,
        events: List<EventEntity>,
        samples: List<LocationSampleEntity>,
        analytics: JourneyAnalytics.JourneyReport?,
        originLabel: String? = null,
        destLabel: String? = null,
        measures: Measures = Measures.INDIA
    ): JourneyStory.Story {
        val input = input(context, trip, events, samples, analytics, originLabel, destLabel, measures)
        return JourneyStory.build(input, book(context, input))
    }

    // ------------------------------------------------------------------------

    private fun input(
        context: Context,
        trip: ActiveTripEntity,
        events: List<EventEntity>,
        samples: List<LocationSampleEntity>,
        analytics: JourneyAnalytics.JourneyReport?,
        originLabel: String?,
        destLabel: String?,
        measures: Measures = Measures.INDIA,
        market: com.trippulse.app.domain.Market = marketOf(context)
    ): JourneyStory.Input {
        val now = System.currentTimeMillis()
        val sorted = samples.sortedBy { it.tMs }
        return JourneyStory.Input(
            who = Profile.name(context).ifBlank { null },
            origin = originLabel?.takeIf { it.isNotBlank() } ?: trip.originName,
            destination = destLabel?.takeIf { it.isNotBlank() } ?: trip.destName,
            originLat = trip.originLat, originLng = trip.originLng,
            destLat = trip.destLat, destLng = trip.destLng,
            mode = trip.transportMode,
            startedAtMs = trip.startedAtMs ?: sorted.firstOrNull()?.tMs ?: trip.createdAtMs,
            endedAtMs = trip.completedAtMs,
            nowMs = now,
            events = events.map(EventCodec::toDomain),
            samples = thin(sorted).map { JourneyStory.Sample(it.tMs, it.lat, it.lng) },
            distanceM = analytics?.distanceM ?: 0.0,
            routeDistanceM = trip.totalRouteDistanceM.takeIf { it > 0 },
            fuelType = trip.fuelType,
            seedKey = trip.tripId,
            measures = measures,
            tollPassName = market.tollPassName
        )
    }

    /** The traveller's market, from the app, so a report never names an Indian toll pass abroad. */
    private fun marketOf(context: Context): com.trippulse.app.domain.Market =
        runCatching { (context.applicationContext as com.trippulse.app.TripPulseApp).graph.market() }
            .getOrDefault(com.trippulse.app.domain.Markets.INDIA)

    /** A fix every 20 s is far more than a printed route needs; keep about one a minute. */
    private fun thin(samples: List<LocationSampleEntity>): List<LocationSampleEntity> {
        if (samples.size <= 1_500) return samples
        val out = ArrayList<LocationSampleEntity>()
        var last = Long.MIN_VALUE / 2
        for (s in samples) if (s.tMs - last >= 60_000L) { out += s; last = s.tMs }
        if (out.last() !== samples.last()) out += samples.last()
        return out
    }

    private suspend fun book(context: Context, input: JourneyStory.Input, extra: List<Pair<Double, Double>> = emptyList()): PlaceBook {
        val book = PlaceBook()
        val saved = runCatching {
            (context.applicationContext as TripPulseApp).graph.db.savedPlaceDao().all()
        }.getOrNull().orEmpty()
        saved.forEach { book.add(it.lat, it.lng, it.name, PlaceBook.Rank.SAVED) }
        JourneyStory.seed(input, book)
        val targets = (JourneyStory.namingTargets(input, book) + extra.filter { book.nameAt(it.first, it.second) == null })
            .distinctBy { "%.2f,%.2f".format(it.first, it.second) }
            .take(MAX_LOOKUPS)
        if (targets.isNotEmpty()) {
            val names = withTimeoutOrNull(LOOKUP_BUDGET_MS) {
                coroutineScope {
                    targets.map { (lat, lng) -> async { Triple(lat, lng, LocationFix.placeName(context, GeoPoint(lat, lng))) } }.awaitAll()
                }
            }.orEmpty()
            names.forEach { (lat, lng, name) -> book.add(lat, lng, name, PlaceBook.Rank.GEOCODED) }
        }
        return book
    }

    private fun cloudEvent(e: Map<String, Any?>): TripEvent? {
        val type = e["type"] as? String ?: return null
        val at = (e["eventTime"] as? Number)?.toLong() ?: (e["t"] as? Number)?.toLong() ?: return null
        @Suppress("UNCHECKED_CAST")
        val payload = (e["payload"] as? Map<String, Any?>).orEmpty()
        return TripEvent(
            eventId = e["eventId"] as? String ?: "$type-$at",
            tripId = "",
            type = type,
            eventTimeMs = at,
            lat = (e["lat"] as? Number)?.toDouble(),
            lng = (e["lng"] as? Number)?.toDouble(),
            accuracyM = null,
            source = EventSource.SYSTEM_INFERRED,
            payload = payload
        )
    }

    /** What identifies the phone, as far as Android lets an ordinary app read. */
    private fun deviceRows(device: Map<String, Any?>): List<Pair<String, String>> {
        if (device.isEmpty()) return emptyList()
        fun str(k: String) = (device[k] as? String)?.takeIf { it.isNotBlank() }
        return buildList {
            add("Phone" to DeviceDossier.describe(device))
            str("model")?.let { add("Model number" to it) }
            (device["androidSdk"] as? Number)?.let { sdk ->
                add("Android" to listOfNotNull(str("androidRelease"), "API $sdk").joinToString(" · "))
            }
            str("securityPatch")?.let { add("Security patch" to it) }
            str("publicIp")?.let { add("Public IP at last contact" to it) }
            str("localIp")?.let { add("Local IP" to it) }
            str("androidId")?.let { add("Android ID" to it) }
            str("installId")?.let { add("Koode install ID" to it) }
        }
    }

    /** At most this many places are looked up per report… */
    private const val MAX_LOOKUPS = 8
    /** …and never for longer than this, all together. */
    private const val LOOKUP_BUDGET_MS = 8_000L
}
