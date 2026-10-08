package com.trippulse.app.domain.report

import com.trippulse.app.domain.BreakTimeline
import com.trippulse.app.domain.DistanceLedger
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.Halts
import com.trippulse.app.domain.Nourishment
import com.trippulse.app.domain.Pictures
import com.trippulse.app.domain.TimelineEdits
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.TripEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * A journey told as a story: where it began, where it paused and why, how
 * the road went between, and where it is now.
 *
 * The event log is a machine's record. It holds three entries for one stop
 * (fuel, then the restroom, then a bottle of water as the car pulled away),
 * a line per toll, nudges, ETA changes and the traveller's own corrections.
 * Read straight, it is a logbook. This turns it into what a person would
 * write: one stop at Pallapatti for fuel and the restroom, 17 minutes; two
 * hours' driving with three tolls; dinner logged later; the night in a room
 * at Palasamudram.
 *
 * Every place is named. Names come from a [PlaceBook] the caller fills
 * first (saved places, names recorded with entries, the phone's geocoder for
 * the few points left over, see [namingTargets]); nothing here reaches the
 * network, so it is deterministic and testable.
 *
 * Money is never part of the story. The journey report is shareable; the
 * expense report has its own document.
 */
object JourneyStory {

    data class Sample(val tMs: Long, val lat: Double, val lng: Double)

    data class Input(
        val who: String?,
        val origin: String,
        val destination: String,
        val originLat: Double? = null,
        val originLng: Double? = null,
        val destLat: Double? = null,
        val destLng: Double? = null,
        val mode: String,
        val startedAtMs: Long,
        /** When the journey ended, or null while it is still going. */
        val endedAtMs: Long?,
        val nowMs: Long,
        val events: List<TripEvent>,
        val samples: List<Sample>,
        /** Distance the phone actually measured. */
        val distanceM: Double,
        /** The planned route's length, when known: "320 of 990 km". */
        val routeDistanceM: Double? = null,
        val zone: ZoneId = ZoneId.systemDefault(),
        /** PETROL, DIESEL or ELECTRIC for a private vehicle: "charged up" rather than "refuelled". */
        val fuelType: String? = null,
        /** Fixes the wording: one journey always reads the same way. */
        val seedKey: String = "$origin|$startedAtMs",
        /** The traveller's own units: a Texan's story is told in miles, a cruise in nautical miles. */
        val measures: com.trippulse.app.domain.Measures = com.trippulse.app.domain.Measures.INDIA,
        /** What a toll pass is called where the traveller lives ("FASTag annual pass"); null where there is none. */
        val tollPassName: String? = "FASTag annual pass"
    )

    /** What the journey was doing over one span of time, for charts. */
    enum class Phase { DRIVING, STOPPED, HALT, OFFLINE }
    data class Segment(val fromMs: Long, val toMs: Long, val phase: Phase, val label: String? = null)

    /** Distance covered in one clock hour, for the pace chart. */
    data class HourKm(val hourStartMs: Long, val metres: Double, val estimatedM: Double = 0.0)

    /** What happened at a stop, each with its picture where the app has one. */
    enum class Item(val picture: String?, val word: String) {
        FOOD(Pictures.FOOD, "a meal"),
        TEA(null, "tea"),
        SNACK(null, "a snack"),
        WATER(Pictures.WATER, "water"),
        TOILET(Pictures.TOILET, "the restroom"),
        REST(Pictures.REST, "a rest"),
        FUEL(Pictures.FUEL, "fuel"),
        CHARGE(Pictures.FUEL, "a charge")
    }

    enum class MomentKind { OFFLINE, SWITCHED_OFF, SIM_CHANGED, SOS, SOS_RESOLVED, NOTE, INCIDENT, VEHICLE, DESTINATION, PASSENGER, MODE }

    sealed class Entry {
        abstract val atMs: Long

        data class Depart(override val atMs: Long, val place: String) : Entry()

        data class Stop(
            override val atMs: Long,
            val endMs: Long?,
            val place: String?,
            val lat: Double?,
            val lng: Double?,
            val items: Set<Item>,
            val meal: Nourishment?,
            /** The picture that stands for the stop: the restaurant, the pump, the restroom… */
            val picture: String?,
            val title: String,
            /** Still under way when the report was made. */
            val ongoing: Boolean,
            /** Logged hours after the fact: the time is the traveller's, the place is where it was logged. */
            val loggedLater: Boolean
        ) : Entry() {
            val seconds: Long? get() = endMs?.let { ((it - atMs) / 1000).coerceAtLeast(0) }
        }

        data class Drive(
            override val atMs: Long,
            val endMs: Long,
            val distanceM: Double,
            val tolls: Int,
            val tollNames: List<String>,
            /** Part of this stretch passed with the phone out of contact; the distance is then an estimate. */
            val offlineMs: Long,
            val fromPlace: String?,
            val toPlace: String?,
            /** Time inside this stretch the car is known to have stood: a stop logged later, a silence it never moved in. */
            val stoppedInsideS: Long = 0,
            /** Of [tolls], how many were worked out from the road while the phone was silent. */
            val inferredTolls: Int = 0,
            /** How this stretch was made: the stage the journey was on. */
            val mode: String? = null
        ) : Entry() {
            val seconds: Long get() = ((endMs - atMs) / 1000).coerceAtLeast(0)
            /** Time actually on the move, including a silence the car moved through. */
            val movingSeconds: Long get() = (seconds - stoppedInsideS).coerceAtLeast(0)
        }

        data class Halt(
            override val atMs: Long,
            val endMs: Long?,
            val place: String?,
            val lat: Double?,
            val lng: Double?,
            val type: Halts.Type,
            val overnight: Boolean,
            val title: String
        ) : Entry()

        data class Moment(
            override val atMs: Long,
            val endMs: Long?,
            val kind: MomentKind,
            val text: String,
            val place: String?,
            /** For a change of vehicle: the vehicle changed to, which is the picture the moment is drawn with. */
            val mode: String? = null
        ) : Entry()

        data class Arrive(override val atMs: Long, val place: String) : Entry()
    }

    data class Day(val number: Int, val dateMs: Long, val title: String, val entries: List<Entry>)

    data class Highlight(val picture: String?, val glyph: String?, val title: String, val detail: String)

    data class Story(
        val headline: String,
        val paragraphs: List<String>,
        val days: List<Day>,
        val highlights: List<Highlight>,
        val stops: List<Entry.Stop>,
        val halts: List<Entry.Halt>,
        val drives: List<Entry.Drive>,
        val tolls: Int,
        val meals: Map<Nourishment, Int>,
        val water: Int,
        val restroom: Int,
        val refuels: Int,
        val offlineMs: Long,
        val status: String,
        /** Where the journey is now, or ended. */
        val lastPlace: String?,
        /** The whole span, phase by phase, for the day strip. */
        val segments: List<Segment> = emptyList(),
        /** Distance per clock hour, for the pace chart. */
        val kmByHour: List<HourKm> = emptyList(),
        /** True when the phone was ever out of contact: toll counts may be incomplete. */
        val tollsMayBeMissing: Boolean = false,
        /** The silences, as (from, to). */
        val offline: List<Pair<Long, Long>> = emptyList(),
        /** Time on the move over the whole journey, a silence the car moved through included. */
        val movingSeconds: Long = 0,
        /** Time at stops and halts. */
        val stoppedSeconds: Long = 0,
        val longestDrive: Entry.Drive? = null,
        val longestStop: Entry.Stop? = null,
        /** The ways the journey was made, in order, one span per change of mode. */
        val stages: List<StageSpan> = emptyList()
    )

    /** One way of travelling, from when it began to when the next began (or the journey ended). */
    data class StageSpan(val mode: String, val fromMs: Long, val toMs: Long) {
        val seconds: Long get() = ((toMs - fromMs) / 1000).coerceAtLeast(0)
    }

    // ------------------------------------------------------------------------
    // Naming
    // ------------------------------------------------------------------------

    /** Names the log itself recorded: each break's and halt's place, the journey's ends. */
    fun seed(input: Input, book: PlaceBook) {
        book.add(input.originLat, input.originLng, input.origin, PlaceBook.Rank.END)
        book.add(input.destLat, input.destLng, input.destination, PlaceBook.Rank.END)
        corrected(input.events).forEach { e ->
            if (e.type == EventTypes.BREAK_CHECKPOINT || e.type == EventTypes.HALT_CONFIRMED) {
                val logged = abs(TimelineEdits.shownTime(e.type, e.payload, e.eventTimeMs) - e.eventTimeMs) < LOGGED_LATER_MS
                if (logged) book.add(e.lat, e.lng, e.payload["place"] as? String, PlaceBook.Rank.RECORDED)
            }
        }
    }

    /**
     * Points the story will need a name for that the book cannot yet give:
     * stops nobody logged anything at, where the phone came back after a
     * silence, the latest position. The caller asks the geocoder for these
     * (a handful, not one per sample) and adds the answers before [build].
     */
    fun namingTargets(input: Input, book: PlaceBook): List<Pair<Double, Double>> {
        val pts = ArrayList<Pair<Double, Double>>()
        stopPeriods(corrected(input.events), input).forEach { p -> if (p.lat != null && p.lng != null) pts += p.lat to p.lng }
        input.samples.firstOrNull()?.let { pts += it.lat to it.lng }
        input.samples.lastOrNull()?.let { pts += it.lat to it.lng }
        offlineGaps(corrected(input.events), input).forEach { g ->
            input.samples.firstOrNull { it.tMs >= g.second }?.let { pts += it.lat to it.lng }
        }
        // De-duplicate within a kilometre and drop what the book can already name.
        val out = ArrayList<Pair<Double, Double>>()
        for (p in pts) {
            if (book.nameAt(p.first, p.second) != null) continue
            if (out.any { PlaceBook.distanceM(it.first, it.second, p.first, p.second) < 1_000 }) continue
            out += p
        }
        return out
    }

    // ------------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------------

    fun build(input: Input, book: PlaceBook): Story {
        val events = corrected(input.events)
        val endMs = input.endedAtMs ?: input.nowMs

        val stops = stops(events, input, book)
        val halts = halts(events, input, book)
        val gaps = offlineGaps(events, input)
        val tollEvents = events.filter { it.type == EventTypes.TOLL_CROSSED }
        val completed = input.endedAtMs != null

        // ---- the chain of places the vehicle actually stood at, and the drives between ----
        // A walk of a few hundred metres between the metro and the cab is the
        // change between them, not a way the journey was made.
        val allSpans = stageSpans(events, input, endMs)
        val stages = com.trippulse.app.domain.StageRepair.ridden(allSpans, { it.mode }) { travelled(input.samples, it.fromMs, it.toMs, null) }
        val transfers = allSpans.filterNot { it in stages }
        // A break logged while on a halt (water in the room) is part of the halt, not a place the car stood.
        fun insideHalt(s: Entry.Stop) = halts.any { h -> s.atMs >= h.atMs && s.atMs < (h.endMs ?: endMs) }
        val standing = stops.filterNot { it.loggedLater || insideHalt(it) }
        val anchors = ArrayList<Entry>()
        anchors += Entry.Depart(input.startedAtMs, input.origin)
        anchors += standing
        anchors += halts
        val arrival = if (completed) Entry.Arrive(input.endedAtMs!!, input.destination) else null
        arrival?.let { anchors += it }
        anchors.sortBy { it.atMs }

        val drives = ArrayList<Entry.Drive>()
        for (i in 0 until anchors.size) {
            val a = anchors[i]
            val from = endOf(a) ?: continue
            val to = anchors.getOrNull(i + 1)?.atMs ?: if (completed) null else latestMovement(input, from)
            if (to == null || to - from < MIN_DRIVE_MS) continue
            val dist = travelled(input.samples, from, to, anchorPoint(anchors.getOrNull(i + 1), input))
            if (dist < MIN_DRIVE_M) continue
            val inWindow = tollEvents.filter { TimelineEdits.shownTime(it.type, it.payload, it.eventTimeMs) in from..to }
            drives += Entry.Drive(
                atMs = from, endMs = to, distanceM = dist,
                tolls = inWindow.size,
                tollNames = inWindow.mapNotNull { tollName(it.payload) }.distinct(),
                offlineMs = gaps.sumOf { (s, e) -> (minOf(e, to) - maxOf(s, from)).coerceAtLeast(0) },
                fromPlace = placeOf(a),
                toPlace = anchors.getOrNull(i + 1)?.let { placeOf(it) }
                    ?: input.samples.lastOrNull { it.tMs <= to }?.let { book.describe(it.lat, it.lng) },
                stoppedInsideS = stoodInside(input, stops, from, to),
                inferredTolls = inWindow.count { it.payload["inferred"] == true },
                mode = modeAt(stages, (from + to) / 2) ?: input.mode
            )
        }

        // ---- moments worth a line of their own ----
        val moments = moments(events, input, book, gaps, drives, stages, transfers)

        // Every stop is told, including the ones that are not anchors (logged later, or in the middle of a halt).
        val all = (anchors + stops.filterNot { it in standing } + drives + moments).sortedWith(
            compareBy<Entry> { it.atMs }.thenBy { order(it) }
        )
        val days = all.groupBy { localDate(it.atMs, input.zone) }.entries.sortedBy { it.key }.mapIndexed { i, (date, list) ->
            val ms = date.atStartOfDay(input.zone).toInstant().toEpochMilli()
            Day(i + 1, ms, DAY_TITLE.withZone(input.zone).format(Instant.ofEpochMilli(ms)), list)
        }

        // ---- the numbers a person would mention ----
        val meals = mealOccasions(events)
        val water = distinctLogs(events, EventTypes.WATER_REPORTED, 20 * MIN)
        val restroom = stops.count { Item.TOILET in it.items }
        val refuels = stops.count { Item.FUEL in it.items || Item.CHARGE in it.items }
        val offlineMs = gaps.sumOf { it.second - it.first }
        val lastPlace = when {
            completed -> input.destination
            halts.lastOrNull()?.endMs == null && halts.isNotEmpty() -> halts.last().place
            else -> input.samples.lastOrNull()?.let { book.describe(it.lat, it.lng) }
        }
        val haltingNow = !completed && halts.lastOrNull()?.let { it.endMs == null } == true
        val status = when {
            completed -> "Completed"
            haltingNow -> "Halted for the night".takeIf { halts.last().overnight } ?: "On a halt"
            else -> "On the way"
        }

        val highlights = highlights(input, stops, halts, drives, tollEvents, meals, water, restroom, refuels, stages)
        val segments = segments(input, standing, halts, gaps, endMs)
        val stood = stops.filter { it.loggedLater }.map { it.atMs to (it.endMs ?: it.atMs + NOMINAL_STOP_MS) } +
            halts.map { it.atMs to (it.endMs ?: endMs) }
        val kmByHour = kmByHour(input, stood)
        val movingSeconds = drives.sumOf { it.movingSeconds }
        val stoppedSeconds = standing.sumOf { it.seconds ?: 0L } + halts.sumOf { h -> ((h.endMs ?: endMs) - h.atMs) / 1000 }
        // Told by the mode it spent longest on: a walk to the metro does not make it a walk.
        val mainMode = (stages.filter { TransportCatalog.profile(it.mode).key != TransportCatalog.WALK.key }.ifEmpty { stages })
            .maxByOrNull { it.seconds }?.mode ?: input.mode
        // More than one way of riding: the journey is a journey, not "by bike taxi".
        val mixed = stages.map { TransportCatalog.profile(it.mode).key }.filter { it != TransportCatalog.WALK.key }.distinct().size >= 2
        val mode = if (mixed) "" else " " + byMode(mainMode)
        val headline = if (completed)
            "${name(input.who)} travelled ${km(input.distanceM, input.measures, mainMode)} from ${input.origin} to ${input.destination}$mode."
        else
            "${name(input.who)} is travelling from ${input.origin} to ${input.destination}$mode."

        val draft = Story(
            headline = headline,
            paragraphs = emptyList(),
            days = days,
            highlights = highlights,
            stops = stops,
            halts = halts,
            drives = drives,
            tolls = tollEvents.size,
            meals = meals,
            water = water,
            restroom = restroom,
            refuels = refuels,
            offlineMs = offlineMs,
            status = status,
            lastPlace = lastPlace,
            segments = segments,
            kmByHour = kmByHour,
            tollsMayBeMissing = offlineMs > 0,
            offline = gaps,
            movingSeconds = movingSeconds,
            stoppedSeconds = stoppedSeconds,
            longestDrive = drives.maxByOrNull { it.movingSeconds },
            longestStop = standing.filter { it.seconds != null }.maxByOrNull { it.seconds!! },
            stages = stages
        )
        return draft.copy(paragraphs = Prose.narrate(input, draft, book))
    }

    // ------------------------------------------------------------------------
    // Phases and pace, for the charts
    // ------------------------------------------------------------------------

    private fun segments(input: Input, stops: List<Entry.Stop>, halts: List<Entry.Halt>, gaps: List<Pair<Long, Long>>, endMs: Long): List<Segment> {
        val start = input.startedAtMs
        if (endMs <= start) return emptyList()
        // Priority when spans overlap: a halt, then a stop, then a silence; the rest is moving.
        data class Span(val a: Long, val b: Long, val phase: Phase, val label: String?, val rank: Int)
        val spans = ArrayList<Span>()
        halts.forEach { spans += Span(it.atMs, it.endMs ?: endMs, Phase.HALT, it.place, 0) }
        stops.forEach { spans += Span(it.atMs, it.endMs ?: endMs, Phase.STOPPED, it.title, 1) }
        gaps.forEach { (a, b) -> spans += Span(a, b, Phase.OFFLINE, null, 2) }
        val cuts = (spans.flatMap { listOf(it.a, it.b) } + start + endMs).filter { it in start..endMs }.distinct().sorted()
        val out = ArrayList<Segment>()
        for (i in 0 until cuts.size - 1) {
            val a = cuts[i]; val b = cuts[i + 1]
            if (b <= a) continue
            val mid = (a + b) / 2
            val on = spans.filter { mid >= it.a && mid < it.b }.minByOrNull { it.rank }
            val phase = on?.phase ?: Phase.DRIVING
            val last = out.lastOrNull()
            if (last != null && last.phase == phase && last.label == on?.label && last.toMs == a) out[out.lastIndex] = last.copy(toMs = b)
            else out += Segment(a, b, phase, on?.label)
        }
        return out
    }

    /**
     * Distance per clock hour. A silence the car moved through is spread
     * over the hours it was on the move -- not over a stop logged inside it
     * or a halt -- and marked as estimated.
     */
    private fun kmByHour(input: Input, stood: List<Pair<Long, Long>>): List<HourKm> {
        val z = input.zone
        val acc = java.util.TreeMap<Long, Double>()
        val est = java.util.TreeMap<Long, Double>()
        val s = input.samples
        fun hourOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(z).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
        fun standing(ms: Long) = stood.any { (a, b) -> ms >= a && ms < b }
        for (i in 1 until s.size) {
            val a = s[i - 1]; val b = s[i]
            val line = PlaceBook.distanceM(a.lat, a.lng, b.lat, b.lng)
            if (b.tMs - a.tMs >= DistanceLedger.GAP_MS) {
                if (line < DistanceLedger.GAP_MIN_M) continue
                val road = line * DistanceLedger.roadFactor(line, ROAD_FACTOR)
                // Five-minute steps, counting only the ones the car was moving in.
                val step = 5 * MIN
                val moving = ArrayList<Long>()
                var t = a.tMs
                while (t < b.tMs) { if (!standing(t)) moving += t; t += step }
                if (moving.isEmpty()) continue
                val share = road / moving.size
                for (m in moving) { val h = hourOf(m); est[h] = (est[h] ?: 0.0) + share }
                continue
            }
            val hour = hourOf(b.tMs)
            acc[hour] = (acc[hour] ?: 0.0) + line
        }
        return (acc.keys + est.keys).sorted().map { h -> HourKm(h, acc[h] ?: 0.0, est[h] ?: 0.0) }
    }

    /**
     * Time inside a stretch the car is known to have stood: a stop logged
     * after the fact that falls in it, and any silence it did not move in.
     */
    private fun stoodInside(input: Input, stops: List<Entry.Stop>, from: Long, to: Long): Long {
        var ms = 0L
        for (st in stops.filter { it.loggedLater }) {
            val e = st.endMs ?: (st.atMs + NOMINAL_STOP_MS)
            ms += (minOf(e, to) - maxOf(st.atMs, from)).coerceAtLeast(0)
        }
        val s = input.samples
        for (i in 1 until s.size) {
            val a = s[i - 1]; val b = s[i]
            if (b.tMs <= from || a.tMs >= to || b.tMs - a.tMs < DistanceLedger.GAP_MS) continue
            if (PlaceBook.distanceM(a.lat, a.lng, b.lat, b.lng) < DistanceLedger.GAP_MIN_M) {
                ms += (minOf(b.tMs, to) - maxOf(a.tMs, from)).coerceAtLeast(0)
            }
        }
        return ms / 1000
    }

    /**
     * The ways the journey was made, from the stage starts it recorded (the
     * first stage, every planned stage, every change part-way). Consecutive
     * stages of one mode are one span.
     */
    private fun stageSpans(events: List<TripEvent>, input: Input, endMs: Long): List<StageSpan> {
        // A stage starts at a LEG_STARTED; an older build recorded some mode
        // changes only as a plan change, which says what it changed to.
        val changes = events.filter { it.type == EventTypes.TRAVEL_MODE_CHANGED && it.payload["toMode"] is String }
            .sortedBy { it.eventTimeMs }
        val starts0 = (events.filter { it.type == EventTypes.LEG_STARTED && it.payload["mode"] is String }
            .map { (it.payload["mode"] as String) to it.eventTimeMs } +
            changes.map { (it.payload["toMode"] as String) to it.eventTimeMs })
            .sortedBy { it.second }
        // Before the first change it was going by what it changed from.
        val first = changes.firstOrNull()
        val firstMode = first?.payload?.get("fromMode") as? String
        val starts = if (first != null && firstMode != null && starts0.none { it.second < first.eventTimeMs })
            listOf(firstMode to input.startedAtMs) + starts0 else starts0
        if (starts.isEmpty()) return listOf(StageSpan(input.mode, input.startedAtMs, endMs))
        val out = ArrayList<StageSpan>()
        for ((i, st) in starts.withIndex()) {
            val from = if (i == 0) minOf(input.startedAtMs, st.second) else st.second
            // A stage ends where the next begins, and none runs past the journey's end.
            val to = minOf(starts.getOrNull(i + 1)?.second ?: endMs, endMs)
            if (to <= from) continue
            val last = out.lastOrNull()
            if (last != null && last.mode.equals(st.first, ignoreCase = true)) out[out.lastIndex] = last.copy(toMs = to)
            else out += StageSpan(st.first, from, to)
        }
        return out.ifEmpty { listOf(StageSpan(input.mode, input.startedAtMs, endMs)) }
    }

    private fun modeAt(stages: List<StageSpan>, tMs: Long): String? =
        (stages.lastOrNull { it.fromMs <= tMs } ?: stages.firstOrNull())?.mode

    /**
     * The mode a journey is best described by: the one it spent longest on,
     * not counting walking when there was anything else.
     */
    fun primaryMode(story: Story, fallback: String): String {
        val byMode = story.stages.groupBy { TransportCatalog.profile(it.mode).key }.mapValues { (_, v) -> v.sumOf { it.seconds } }
        val ridden = byMode.filterKeys { it != TransportCatalog.WALK.key }
        return (ridden.ifEmpty { byMode }).maxByOrNull { it.value }?.key ?: fallback
    }

    /**
     * A journey ridden more than one way (bike taxi, metro, bike taxi): told
     * as a journey, not by any one of its modes. Walking between rides does
     * not make it mixed; two different rides do.
     */
    fun isMixed(story: Story): Boolean =
        story.stages.map { TransportCatalog.profile(it.mode).key }.filter { it != TransportCatalog.WALK.key }.distinct().size >= 2

    /** The longest of a journey's parts. */
    fun longestStage(story: Story): StageSpan? = story.stages.maxByOrNull { it.seconds }

    /** A change of vehicle within this of a walk's start or end belongs to that walk. */
    private const val TRANSFER_SLACK_MS = 3 * 60_000L

    /** "the metro", "a cab", "a bike taxi", "walking": a vehicle as the object of a sentence. */
    fun vehicleWord(mode: String?): String = when (TransportCatalog.profile(mode).key) {
        "WALK" -> "walking"
        "CAR" -> "the car"
        "BIKE" -> "the motorbike"
        "CYCLE" -> "the bicycle"
        "CAB" -> "a cab"
        "BIKE_TAXI" -> "a bike taxi"
        "AUTO" -> "an auto"
        "FLIGHT" -> "a flight"
        "SHIP" -> "the ship"
        "FERRY" -> "the ferry"
        else -> "the " + com.trippulse.app.domain.JourneyPlans.modeWord(TransportCatalog.profile(mode).key)
    }

    /** "by cab", "on foot", "by air". */
    fun byMode(mode: String?): String = when (TransportCatalog.profile(mode).key) {
        "WALK" -> "on foot"
        "FLIGHT" -> "by air"
        "CAB" -> "by cab"
        "BIKE_TAXI" -> "by bike taxi"
        else -> "by " + com.trippulse.app.domain.JourneyPlans.modeWord(TransportCatalog.profile(mode).key)
    }

    /** How a stretch reads on the timeline: "Drove 12 km", "Walked 600 m", "By metro 9.4 km". */
    fun stretch(mode: String?, metres: Double, measures: com.trippulse.app.domain.Measures = com.trippulse.app.domain.Measures.INDIA): String {
        val d = km(metres, measures, mode)
        return when (TransportCatalog.profile(mode).key) {
            "CAR" -> "Drove $d"
            "BIKE" -> "Rode $d"
            "CYCLE" -> "Cycled $d"
            "WALK" -> "Walked $d"
            "FLIGHT" -> "Flew $d"
            "SHIP", "FERRY" -> "Sailed $d"
            else -> "${byMode(mode).replaceFirstChar { it.uppercase() }} $d"
        }
    }

    /** Whether any fix between [from] and [to] is back at the place. */
    private fun cameBack(input: Input, lat: Double?, lng: Double?, from: Long, to: Long): Boolean {
        if (lat == null || lng == null) return false
        return input.samples.any { it.tMs > from + MIN && it.tMs < to && PlaceBook.distanceM(lat, lng, it.lat, it.lng) < MOVED_ON_M }
    }

    /**
     * When the car left a stop the record never closed: the last fix still
     * there before the first one clearly away. Null when the record cannot say.
     */
    private fun movedOnAt(input: Input, atMs: Long, lat: Double?, lng: Double?): Long? {
        val s = input.samples
        val here = if (lat != null && lng != null) lat to lng
            else s.lastOrNull { it.tMs <= atMs }?.let { it.lat to it.lng } ?: return null
        val away = s.firstOrNull { it.tMs > atMs && PlaceBook.distanceM(here.first, here.second, it.lat, it.lng) >= MOVED_ON_M } ?: return null
        val last = s.lastOrNull { it.tMs < away.tMs && it.tMs >= atMs }
        return (last?.tMs ?: away.tMs).coerceAtLeast(atMs + MIN)
    }

    // ------------------------------------------------------------------------
    // Stops: one per place the vehicle stood, however many entries it took
    // ------------------------------------------------------------------------

    private data class Brk(
        val id: String, val loggedMs: Long, val startMs: Long, val endMs: Long?,
        val lat: Double?, val lng: Double?, val place: String?, val items: Set<Item>,
        val meal: Nourishment?, val open: Boolean
    ) { val loggedLater: Boolean get() = abs(loggedMs - startMs) >= LOGGED_LATER_MS && endMs != null }

    private data class Period(val startMs: Long, val endMs: Long?, val lat: Double?, val lng: Double?)

    private fun breaks(events: List<TripEvent>): List<Brk> = events
        .filter { it.type == EventTypes.BREAK_CHECKPOINT && it.payload["breakId"] is String && it.payload["countsAsBreak"] != false }
        .groupBy { it.payload["breakId"] as String }
        .mapNotNull { (id, versions) ->
            val e = versions.maxByOrNull { it.eventTimeMs } ?: return@mapNotNull null
            val p = e.payload
            if (p["removed"] == true) return@mapNotNull null
            // The first version is where and when it was logged; later versions move with the car.
            val origin = versions.minByOrNull { it.eventTimeMs }!!
            // When it began: what the newest version says, else when it was first logged.
            val start = if (p["startMs"] is Number || p["atMs"] is Number) TimelineEdits.shownTime(e.type, p, e.eventTimeMs)
                else TimelineEdits.shownTime(origin.type, origin.payload, origin.eventTimeMs)
            val dur = (p["durationS"] as? Number)?.toLong()?.takeIf { it > 0 }
            val items = buildSet {
                if (p["food"] == true) add(Item.FOOD)
                if (p["tea"] == true) add(Item.TEA)
                if (p["snack"] == true) add(Item.SNACK)
                if (p["water"] == true) add(Item.WATER)
                if (p["toilet"] == true) add(Item.TOILET)
                if (p["rest"] == true) add(Item.REST)
                if (p["fuel"] == true) add(Item.FUEL)
                if (p["charge"] == true) add(Item.CHARGE)
            }
            Brk(
                id = id, loggedMs = origin.eventTimeMs, startMs = start,
                endMs = dur?.let { start + it * 1000 },
                lat = origin.lat, lng = origin.lng,
                place = PlaceBook.clean(p["place"] as? String),
                items = items, meal = Nourishment.fromKey(p["meal"] as? String),
                open = p["open"] == true
            )
        }

    private fun stopPeriods(events: List<TripEvent>, input: Input): List<Period> {
        val out = ArrayList<Period>()
        var open: TripEvent? = null
        for (e in events.sortedBy { it.eventTimeMs }) {
            when (e.type) {
                EventTypes.STOP_STARTED -> if (open == null) open = e
                EventTypes.STOP_ENDED, EventTypes.DRIVING_STARTED -> open?.let {
                    out += Period(it.eventTimeMs, e.eventTimeMs, it.lat, it.lng); open = null
                }
            }
        }
        open?.let { if (input.endedAtMs == null) out += Period(it.eventTimeMs, null, it.lat, it.lng) }
        return out
    }

    private fun stops(events: List<TripEvent>, input: Input, book: PlaceBook): List<Entry.Stop> {
        val now = input.endedAtMs ?: input.nowMs
        val periods = stopPeriods(events, input)
        val members = periods.associateWith { ArrayList<Brk>() }
        val loose = ArrayList<Brk>()
        for (b in breaks(events).sortedBy { it.loggedMs }) {
            if (b.loggedLater) { loose += b; continue }
            fun near(p: Period) = b.lat == null || p.lat == null ||
                PlaceBook.distanceM(b.lat, b.lng!!, p.lat, p.lng!!) <= SAME_STOP_M
            val home = periods.firstOrNull { p -> near(p) && b.loggedMs in (p.startMs - JOIN_MS)..((p.endMs ?: now) + JOIN_MS) }
                ?: periods.firstOrNull { p -> near(p) && b.startMs in (p.startMs - JOIN_MS)..(p.endMs ?: now) }
            if (home != null) members.getValue(home) += b else loose += b
        }

        val out = ArrayList<Entry.Stop>()
        for (p in periods) {
            val bs = members.getValue(p)
            val end = p.endMs ?: movedOnAt(input, p.startMs, p.lat, p.lng)
            if (bs.isEmpty() && ((end ?: now) - p.startMs) < BRIEF_STOP_MS) continue
            val start = (bs.map { it.startMs }.filter { it >= p.startMs - JOIN_MS } + p.startMs).min()
            out += stopOf(start, end, p.lat, p.lng, bs, ongoing = end == null && input.endedAtMs == null, book)
        }
        // Breaks with no detected stop: group the ones logged at one place, one after another.
        val groups = ArrayList<MutableList<Brk>>()
        for (b in loose.sortedBy { it.startMs }) {
            val g = groups.lastOrNull { g ->
                val h = g.first()
                h.loggedLater == b.loggedLater && abs(b.startMs - h.startMs) <= JOIN_MS &&
                    (h.lat == null || b.lat == null || PlaceBook.distanceM(h.lat, h.lng!!, b.lat, b.lng!!) <= SAME_STOP_M)
            }
            if (g != null) g += b else groups += mutableListOf(b)
        }
        for (g in groups) {
            val start = g.minOf { it.startMs }
            val told = g.mapNotNull { it.endMs }.maxOrNull()
            val ongoing = told == null && g.any { it.open } && input.endedAtMs == null
            // No length given: the record says when the car moved on, or the
            // stop is taken as brief. Never does it run to the end of the journey.
            val end = told ?: if (ongoing) null else movedOnAt(input, start, g.first().lat, g.first().lng) ?: (start + NOMINAL_STOP_MS)
            out += stopOf(start, end, g.first().lat, g.first().lng, g, ongoing, book, loggedLater = g.first().loggedLater)
        }
        return out.sortedBy { it.atMs }
    }

    private fun stopOf(
        start: Long, end: Long?, lat: Double?, lng: Double?, bs: List<Brk>, ongoing: Boolean,
        book: PlaceBook, loggedLater: Boolean = false
    ): Entry.Stop {
        val items = bs.flatMap { it.items }.toSet()
        val meal = bs.sortedBy { it.loggedMs }.mapNotNull { it.meal }.lastOrNull()
            ?: if (Item.FOOD in items) Nourishment.LUNCH.takeIf { false } else null
        val place = bs.firstNotNullOfOrNull { it.place } ?: book.describe(lat, lng)
        val payloadLike = items.associate { it.name.lowercase() to true }
        val picture = when {
            items.isEmpty() -> null
            else -> Pictures.breakStop(payloadLike)
        }
        return Entry.Stop(
            atMs = start, endMs = end, place = place, lat = lat, lng = lng,
            items = items, meal = meal, picture = picture,
            title = stopTitle(items, meal, place, loggedLater),
            ongoing = ongoing, loggedLater = loggedLater
        )
    }

    /** "Lunch at Tirupur", "Refuelled at Pallapatti", "Stopped near Salem". */
    fun stopTitle(items: Set<Item>, meal: Nourishment?, place: String?, loggedLater: Boolean = false): String {
        val what = when {
            meal != null && Item.FOOD in items -> meal.label
            Item.FOOD in items -> "A meal"
            Item.TEA in items && Item.SNACK in items -> "Tea and a snack"
            Item.TEA in items -> "Tea"
            Item.SNACK in items -> "A snack"
            Item.FUEL in items -> "Refuelled"
            Item.CHARGE in items -> "Charged up"
            Item.REST in items -> "A rest"
            Item.TOILET in items -> "Restroom stop"
            Item.WATER in items -> "Water break"
            else -> "Stopped"
        }
        if (place == null) return what
        return if (loggedLater) "$what · logged later at $place" else "$what ${at(place)}"
    }

    // ------------------------------------------------------------------------
    // Halts, silences and the other moments
    // ------------------------------------------------------------------------

    private fun halts(events: List<TripEvent>, input: Input, book: PlaceBook): List<Entry.Halt> {
        val sorted = events.sortedBy { it.eventTimeMs }
        return sorted.filter { it.type == EventTypes.HALT_CONFIRMED }.mapNotNull { e ->
            val start = TimelineEdits.shownTime(e.type, e.payload, e.eventTimeMs)
            val after = sorted.filter { it.eventTimeMs > e.eventTimeMs }
            if (after.firstOrNull { it.type == EventTypes.HALT_CANCELLED || it.type == EventTypes.HALT_CONFIRMED }?.type == EventTypes.HALT_CANCELLED) {
                return@mapNotNull null
            }
            val resumed = after.firstOrNull {
                it.type in setOf(EventTypes.HALT_RESUMED, EventTypes.MORNING_RESUME, EventTypes.TRIP_RESUMED, EventTypes.HALT_CONFIRMED)
            }?.eventTimeMs
            // The halt ends when the traveller says so -- unless the record shows
            // the car gone well before that and never back, or nothing was said
            // at all: then it ends when the car left.
            val left = movedOnAt(input, start, e.lat, e.lng)
            val end = when {
                resumed == null -> left ?: input.endedAtMs
                left != null && resumed - left > LATE_RESUME_MS && !cameBack(input, e.lat, e.lng, left, resumed) -> left
                else -> resumed
            }
            val type = Halts.Type.from(e.payload["haltType"] as? String)
            val overnight = e.payload["overnight"] == true
            val place = PlaceBook.clean(e.payload["place"] as? String) ?: book.describe(e.lat, e.lng)
            val what = when (type) {
                Halts.Type.ROOM -> if (overnight) "Night in a room" else "Took a room"
                Halts.Type.FRIEND_FAMILY -> if (overnight) "Night with friends or family" else "Halt with friends or family"
                Halts.Type.REST_STOP -> if (overnight) "Night at a rest stop" else "Halt at a rest stop"
                Halts.Type.OTHER -> if (overnight) "Overnight halt" else "Halt"
            }
            Entry.Halt(start, end, place, e.lat, e.lng, type, overnight, place?.let { "$what ${at(it)}" } ?: what)
        }
    }

    /** Silences during the journey, as (from, to). A silence that began before the journey is not the journey's. */
    private fun offlineGaps(events: List<TripEvent>, input: Input): List<Pair<Long, Long>> =
        events.filter { it.type == EventTypes.DEVICE_BACK_ONLINE }.mapNotNull { e ->
            val gap = (e.payload["gapMs"] as? Number)?.toLong() ?: return@mapNotNull null
            val from = e.eventTimeMs - gap
            if (from < input.startedAtMs - 5 * MIN) null else from to e.eventTimeMs
        }

    private fun moments(
        events: List<TripEvent>, input: Input, book: PlaceBook,
        gaps: List<Pair<Long, Long>>, drives: List<Entry.Drive>
    ,
        stages: List<StageSpan> = emptyList(), transfers: List<StageSpan> = emptyList()
    ): List<Entry.Moment> {
        val out = ArrayList<Entry.Moment>()
        // A silence already told inside a drive ("the phone was offline for part of it") needs no line of its own.
        for ((from, to) in gaps) {
            if (drives.any { it.offlineMs > 0 && from < it.endMs && to > it.atMs }) continue
            val back = input.samples.firstOrNull { it.tMs >= to }
            out += Entry.Moment(from, to, MomentKind.OFFLINE,
                "Phone out of contact for ${duration((to - from) / 1000)}",
                back?.let { book.describe(it.lat, it.lng) })
        }
        // A change of vehicle is told as what it was changed to, in Koode's own
        // words; a short walk between two rides is the one change from the
        // first ride to the second, however many taps it took.
        val skip = HashSet<TripEvent>()
        for (e in events) {
            if (e.type != EventTypes.TRAVEL_MODE_CHANGED) continue
            val from = e.payload["fromMode"] as? String
            val to = e.payload["toMode"] as? String ?: continue
            val at = TimelineEdits.shownTime(e.type, e.payload, e.eventTimeMs)
            val transfer = transfers.firstOrNull { abs(it.fromMs - at) < TRANSFER_SLACK_MS || abs(it.toMs - at) < TRANSFER_SLACK_MS }
            if (transfer != null && TransportCatalog.profile(to).key == TransportCatalog.WALK.key) {
                // The walk begins: one moment for the whole change.
                val next = stages.firstOrNull { it.fromMs >= transfer.toMs - TRANSFER_SLACK_MS }?.mode
                val place = book.describe(e.lat, e.lng)
                val walked = travelled(input.samples, transfer.fromMs, transfer.toMs, null)
                out += if (next != null) Entry.Moment(
                    transfer.fromMs, transfer.toMs, MomentKind.MODE,
                    "Changed from ${vehicleWord(from)} to ${vehicleWord(next)}", place, mode = next
                ) else Entry.Moment(
                    transfer.fromMs, transfer.toMs, MomentKind.MODE,
                    "Off ${vehicleWord(from)}, the last ${km(walked, input.measures, "WALK")} on foot", place, mode = "WALK"
                )
                skip += e
            } else if (transfer != null && from != null && TransportCatalog.profile(from).key == TransportCatalog.WALK.key) {
                skip += e // the walk ends: already told by the moment it began with
            } else if (from == null || TransportCatalog.profile(from).key != TransportCatalog.profile(to).key) {
                out += Entry.Moment(at, null, MomentKind.MODE, "Changed from ${vehicleWord(from)} to ${vehicleWord(to)}", book.describe(e.lat, e.lng), mode = to)
                skip += e
            } else skip += e
        }
        for (e in events) {
            if (e in skip) continue
            val place = book.describe(e.lat, e.lng)
            val text = e.payload["text"] as? String
            val m = when (e.type) {
                EventTypes.DEVICE_SHUTDOWN -> MomentKind.SWITCHED_OFF to "Phone switched off" +
                    ((e.payload["battery"] as? Number)?.let { " with $it% battery" } ?: "")
                EventTypes.SIM_CHANGED -> MomentKind.SIM_CHANGED to "SIM card changed or removed"
                EventTypes.SOS_ACTIVATED -> MomentKind.SOS to "SOS raised"
                EventTypes.SOS_RESOLVED -> MomentKind.SOS_RESOLVED to "SOS resolved, safe"
                EventTypes.QUICK_NOTE -> MomentKind.NOTE to (text ?: "Note")
                EventTypes.INCIDENT, EventTypes.POSSIBLE_INCIDENT -> MomentKind.INCIDENT to (text ?: "Incident reported")
                EventTypes.VEHICLE_ISSUE -> MomentKind.VEHICLE to (text ?: "Vehicle issue")
                EventTypes.DESTINATION_CHANGED -> MomentKind.DESTINATION to (text ?: "Destination changed")
                EventTypes.PASSENGER_JOINED, EventTypes.PASSENGER_LEFT -> MomentKind.PASSENGER to (text ?: "Passenger change")
                EventTypes.TRAVEL_MODE_CHANGED -> MomentKind.MODE to (text ?: "Changed how they travel")
                else -> null
            } ?: continue
            out += Entry.Moment(TimelineEdits.shownTime(e.type, e.payload, e.eventTimeMs), null, m.first, m.second, place)
        }
        return out
    }

    private fun highlights(
        input: Input, stops: List<Entry.Stop>, halts: List<Entry.Halt>, drives: List<Entry.Drive>,
        tolls: List<TripEvent>, meals: Map<Nourishment, Int>, water: Int, restroom: Int, refuels: Int,
        stages: List<StageSpan> = emptyList()
    ): List<Highlight> {
        val z = input.zone
        val out = ArrayList<Highlight>()
        val mealCount = meals.filterKeys { it != Nourishment.SNACK && it != Nourishment.TEA_COFFEE }.values.sum()
        if (mealCount > 0) {
            val stopMeals = stops.filter { it.meal != null && Item.FOOD in it.items }.sortedBy { it.atMs }
            out += Highlight(Pictures.FOOD, null, "${words(mealCount).replaceFirstChar { it.uppercase() }} meal${if (mealCount == 1) "" else "s"}",
                stopMeals.distinctBy { it.meal }.joinToString(" · ") { s ->
                    s.meal!!.label + (s.place?.takeIf { !s.loggedLater }?.let { " ${at(short(it))}" } ?: " at ${clock(s.atMs, z)}")
                }.ifBlank { meals.keys.joinToString(", ") { it.label } })
        }
        if (water > 0) out += Highlight(Pictures.WATER, null, "Water ${times(water)}",
            stops.lastOrNull { Item.WATER in it.items }?.let { "Last at ${clock(it.atMs, z)}" } ?: "Logged on the way")
        if (restroom > 0) out += Highlight(Pictures.TOILET, null, "${words(restroom).replaceFirstChar { it.uppercase() }} restroom stop${if (restroom == 1) "" else "s"}",
            stops.filter { Item.TOILET in it.items }.mapNotNull { it.place?.let(::short) }.distinct().joinToString(" · "))
        if (refuels > 0) out += Highlight(Pictures.FUEL, null, if (refuels == 1) "Refuelled once" else "Refuelled ${times(refuels)}",
            stops.filter { Item.FUEL in it.items || Item.CHARGE in it.items }.mapNotNull { it.place?.let(::short) }.distinct().joinToString(" · "))
        if (tolls.isNotEmpty()) {
            val covered = tolls.count { it.payload["passCovered"] == true }
            out += Highlight(null, "toll", "${tolls.size} toll plaza${if (tolls.size == 1) "" else "s"}",
                if (covered == tolls.size) "All on the ${input.tollPassName ?: "pass"}" else tolls.mapNotNull { tollName(it.payload) }.distinct().take(2).joinToString(" · ").ifBlank { "Crossed on the way" })
        }
        val ridden = stages.filter { TransportCatalog.profile(it.mode).key != TransportCatalog.WALK.key }
        if (ridden.map { TransportCatalog.profile(it.mode).key }.distinct().size >= 2) {
            // Ridden more than one way: the longest part, with its own picture.
            stages.maxByOrNull { it.seconds }?.takeIf { it.seconds >= 10 * 60 }?.let { st ->
                out += Highlight(Pictures.mode(st.mode), null, "${duration(st.seconds)} ${byMode(st.mode)}", "The longest part")
            }
        } else drives.maxByOrNull { it.movingSeconds }?.takeIf { it.movingSeconds >= 30 * 60 }?.let {
            // The picture of the way it was mostly made, not of the last few steps.
            val main = ridden.maxByOrNull { it.seconds }?.mode ?: input.mode
            out += Highlight(Pictures.mode(main), null, "${duration(it.movingSeconds)} longest stretch",
                listOfNotNull(it.fromPlace?.let(::short), it.toPlace?.let(::short)).distinct().joinToString(" → ").ifBlank { km(it.distanceM, input.measures, it.mode) })
        }
        halts.lastOrNull()?.let { h ->
            out += Highlight(Pictures.STAY, null, if (h.overnight) "Overnight halt" else "Halt",
                listOfNotNull(h.place?.let(::short), clock(h.atMs, z)).joinToString(" · "))
        }
        return out
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** The log with the traveller's corrections applied and removed breaks gone. */
    fun corrected(events: List<TripEvent>): List<TripEvent> {
        val edited = TimelineEdits.apply(events, { it.eventId }, { it.type }, { it.eventTimeMs }, { it.payload }, { e, p -> e.copy(payload = p) })
        return BreakTimeline.latestBreaks(edited).let { latest ->
            // latestBreaks keeps only the newest version of each break; the stop
            // builder needs every version (the first one says where it began).
            val removedBreaks = edited.filter { it.type == EventTypes.BREAK_CHECKPOINT }
                .groupBy { it.payload["breakId"] as? String }
                .filterKeys { it != null }
                .filterValues { vs -> vs.maxByOrNull { it.eventTimeMs }?.payload?.get("removed") == true }
                .keys
            val keepIds = latest.map { it.eventId }.toSet()
            edited.filter { e ->
                val bid = e.payload["breakId"] as? String
                when {
                    bid != null && bid in removedBreaks -> false
                    e.type == EventTypes.BREAK_CHECKPOINT -> true
                    else -> e.eventId in keepIds || bid != null
                }
            }
        }
    }

    private fun mealOccasions(events: List<TripEvent>): Map<Nourishment, Int> {
        val out = LinkedHashMap<Nourishment, Int>()
        events.filter { it.type == EventTypes.FOOD_REPORTED }
            .map { (Nourishment.fromKey(it.payload["meal"] as? String) ?: Nourishment.SNACK) to TimelineEdits.shownTime(it.type, it.payload, it.eventTimeMs) }
            .groupBy { it.first }
            .forEach { (kind, list) ->
                // The same meal logged twice (a quick tap, then the full log) is one meal.
                out[kind] = occasions(list.map { it.second }, 6 * HOUR)
            }
        events.filter { it.type == EventTypes.SNACK_REPORTED }.takeIf { it.isNotEmpty() }?.let { out[Nourishment.SNACK] = (out[Nourishment.SNACK] ?: 0) + it.size }
        events.filter { it.type == EventTypes.TEA_COFFEE_REPORTED }.takeIf { it.isNotEmpty() }?.let { out[Nourishment.TEA_COFFEE] = it.size }
        return out
    }

    private fun distinctLogs(events: List<TripEvent>, type: String, windowMs: Long): Int =
        occasions(events.filter { it.type == type }.map { TimelineEdits.shownTime(it.type, it.payload, it.eventTimeMs) }, windowMs)

    /** How many separate occasions a list of times makes, when entries closer than [windowMs] are one. */
    fun occasions(times: List<Long>, windowMs: Long): Int {
        var n = 0
        var last: Long? = null
        for (t in times.sorted()) {
            if (last == null || t - last > windowMs) n++
            last = t
        }
        return n
    }

    private fun travelled(samples: List<Sample>, from: Long, to: Long, end: Pair<Double, Double>?): Double {
        val inside = samples.filter { it.tMs in from..to }
        var d = 0.0
        val before = samples.lastOrNull { it.tMs < from }
        val pts = listOfNotNull(before) + inside
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val line = PlaceBook.distanceM(a.lat, a.lng, b.lat, b.lng)
            // A silence the car moved through is a road, not a straight line.
            d += if (b.tMs - a.tMs >= DistanceLedger.GAP_MS && line >= DistanceLedger.GAP_MIN_M) line * DistanceLedger.roadFactor(line, ROAD_FACTOR) else line
        }
        // The car reached the next stop: close the gap to it (the phone may have been silent on the way).
        val lastPt = pts.lastOrNull()
        if (lastPt != null && end != null) {
            val line = PlaceBook.distanceM(lastPt.lat, lastPt.lng, end.first, end.second)
            d += if (line >= DistanceLedger.GAP_MIN_M) line * DistanceLedger.roadFactor(line, ROAD_FACTOR) else line
        }
        return d
    }

    private fun latestMovement(input: Input, after: Long): Long? =
        input.samples.lastOrNull()?.tMs?.takeIf { it > after }

    private fun anchorPoint(e: Entry?, input: Input? = null): Pair<Double, Double>? = when (e) {
        is Entry.Stop -> if (e.lat != null && e.lng != null) e.lat to e.lng else null
        is Entry.Halt -> if (e.lat != null && e.lng != null) e.lat to e.lng else null
        is Entry.Arrive -> if (input?.destLat != null && input.destLng != null) input.destLat to input.destLng else null
        else -> null
    }

    private fun endOf(e: Entry): Long? = when (e) {
        is Entry.Depart -> e.atMs
        is Entry.Stop -> e.endMs
        is Entry.Halt -> e.endMs
        else -> null
    }

    private fun placeOf(e: Entry): String? = when (e) {
        is Entry.Depart -> e.place
        is Entry.Stop -> e.place
        is Entry.Halt -> e.place
        is Entry.Arrive -> e.place
        else -> null
    }

    private fun order(e: Entry): Int = when (e) {
        is Entry.Depart -> 0
        is Entry.Moment -> 1
        is Entry.Stop -> 2
        is Entry.Halt -> 3
        is Entry.Drive -> 4
        is Entry.Arrive -> 5
    }

    fun tollName(p: Map<String, Any?>): String? =
        (p["plaza"] as? String)?.trim()?.takeIf { it.length > 4 && !it.equals("toll", true) }

    private fun localDate(ms: Long, z: ZoneId) = Instant.ofEpochMilli(ms).atZone(z).toLocalDate()
    private fun sameDay(a: Long, b: Long, z: ZoneId) = localDate(a, z) == localDate(b, z)

    fun firstName(who: String?): String = who?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "The traveller"
    fun name(who: String?): String = who?.trim()?.takeIf { it.isNotBlank() } ?: "The traveller"

    /** "at Tirupur", but "about 12 km from Tirupur" stays as it is. */
    fun at(place: String): String = if (place.startsWith("about ")) place else "at $place"
    fun near(place: String): String = if (place.startsWith("about ")) place else "near $place"

    /** "Kottagoundampatti, Kattagoundampatti" → "Kottagoundampatti" where space is short. */
    fun short(place: String): String = place.substringBefore(",").trim()

    /** The clock as the traveller reads it: 12- or 24-hour, set once for the app (see TimeFmt). */
    private val CLOCK: DateTimeFormatter
        get() = DateTimeFormatter.ofPattern(com.trippulse.app.core.TimeFmt.clockPattern, com.trippulse.app.core.TimeFmt.currentLocale)
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private val DAY_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)

    fun clock(ms: Long, z: ZoneId): String = CLOCK.withZone(z).format(Instant.ofEpochMilli(ms))
    fun day(ms: Long, z: ZoneId): String = DAY.withZone(z).format(Instant.ofEpochMilli(ms))

    fun duration(seconds: Long): String {
        val m = (seconds + 30) / 60
        return when {
            m < 1 -> "under a minute"
            m < 60 -> "$m min"
            m % 60 == 0L -> "${m / 60} h"
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    /** A distance as a sentence tells it, in the traveller's units; at sea in nautical miles. */
    fun km(m: Double, measures: com.trippulse.app.domain.Measures = com.trippulse.app.domain.Measures.INDIA, mode: String? = null): String =
        measures.distanceTold(m, mode)

    private fun words(n: Int) = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve").getOrNull(n) ?: n.toString()
    private fun times(n: Int) = when (n) { 1 -> "once"; 2 -> "twice"; else -> "${words(n)} times" }
    private fun sentence(s: String) = if (s.endsWith(".")) s else "$s."

    fun listJoin(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    /** An entry timed this far from when it was logged was written after the fact. */
    private const val LOGGED_LATER_MS = 45 * MIN
    /** Entries this close in time and place belong to the same stop. */
    private const val JOIN_MS = 20 * MIN
    private const val SAME_STOP_M = 2_500.0
    /** A pause shorter than this with nothing logged is traffic, not a stop. */
    private const val BRIEF_STOP_MS = 10 * MIN
    private const val MIN_DRIVE_MS = 3 * MIN
    private const val MIN_DRIVE_M = 500.0
    /** A fix this far from a stop means the car has left it. */
    private const val MOVED_ON_M = 500.0
    /** A stop with no length and no record of leaving it is taken as brief. */
    private const val NOMINAL_STOP_MS = 10 * MIN
    /** A resume tapped this long after the car left is a late tap, not the end of the halt. */
    private const val LATE_RESUME_MS = 30 * MIN
    /** Straight line to road for a short hop (the app's usual figure). */
    private const val ROAD_FACTOR = 1.27
}
