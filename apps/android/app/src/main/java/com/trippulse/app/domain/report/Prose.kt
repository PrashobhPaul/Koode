package com.trippulse.app.domain.report

import com.trippulse.app.domain.Halts
import com.trippulse.app.domain.Nourishment
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.report.JourneyStory.Entry
import com.trippulse.app.domain.report.JourneyStory.Item
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * The words of a journey report.
 *
 * Everything here is chosen, not generated: a sentence is picked from a pool
 * of ways of saying the same true thing, the pool itself chosen by what the
 * journey was like (a car or a train, a dawn start or a night drive, a
 * leisurely day with a stop every hour or a long pull with none), and the
 * pick is fixed by a seed so one journey always reads the same way while two
 * journeys never read alike. Nothing is ever said that the log does not
 * support.
 *
 * Writing rules, so the voice stays one voice:
 *  - facts in the traveller's terms (places, clock times, "about an hour"),
 *    never the log's ("event", "sample", "detected");
 *  - a time of day is named ("by mid-afternoon") before a clock time is
 *    given, the way a person tells it;
 *  - no praise and no scolding, only what happened;
 *  - the first paragraph could stand alone as the whole report.
 */
object Prose {

    // ------------------------------------------------------------------------
    // Seeded choice
    // ------------------------------------------------------------------------

    /** A small deterministic generator: the same seed always makes the same choices. */
    class Dice(seed: Long) {
        private var s = seed xor 0x5DEECE66DL
        private fun next(): Int { s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17); return ((s ushr 33) and 0x7fffffff).toInt() }
        fun <T> pick(vararg options: T): T = options[next() % options.size]
        fun <T> pick(options: List<T>): T = options[next() % options.size]
        fun chance(percent: Int): Boolean = next() % 100 < percent
    }

    fun seed(key: String): Long = key.fold(1469598103934665603L) { h, c -> (h xor c.code.toLong()) * 1099511628211L }

    // ------------------------------------------------------------------------
    // What kind of journey this is
    // ------------------------------------------------------------------------

    enum class ModeClass { DRIVE, RIDE, TRANSIT, FLY, SAIL, WALK }

    enum class Daypart(val label: String, val at: String) {
        SMALL_HOURS("the small hours", "in the small hours"),
        DAWN("dawn", "at dawn"),
        EARLY_MORNING("early morning", "early in the morning"),
        MID_MORNING("mid-morning", "in the middle of the morning"),
        MIDDAY("midday", "around midday"),
        AFTERNOON("afternoon", "in the afternoon"),
        LATE_AFTERNOON("late afternoon", "late in the afternoon"),
        EVENING("evening", "in the evening"),
        NIGHT("night", "at night"),
        LATE_NIGHT("late night", "late at night");

        companion object {
            fun of(ms: Long, z: ZoneId): Daypart {
                val t = Instant.ofEpochMilli(ms).atZone(z)
                val m = t.hour * 60 + t.minute
                return when {
                    m < 4 * 60 + 30 -> SMALL_HOURS
                    m < 6 * 60 + 30 -> DAWN
                    m < 9 * 60 -> EARLY_MORNING
                    m < 11 * 60 + 30 -> MID_MORNING
                    m < 14 * 60 -> MIDDAY
                    m < 16 * 60 + 30 -> AFTERNOON
                    m < 18 * 60 -> LATE_AFTERNOON
                    m < 20 * 60 + 30 -> EVENING
                    m < 23 * 60 -> NIGHT
                    else -> LATE_NIGHT
                }
            }
        }
    }

    enum class Length { SHORT, HALF_DAY, LONG_DAY, MARATHON, MULTI_DAY }
    enum class Pace { LEISURELY, STEADY, PRESSING }

    /** The journey's character, read once from the data. */
    class Character(val input: JourneyStory.Input, val story: JourneyStory.Story) {
        val z: ZoneId = input.zone
        /** The journey's own mode: the one it spent longest on (a walk to the metro does not make it a walk). */
        val modeKey: String = TransportCatalog.profile(JourneyStory.primaryMode(story, input.mode)).key
        val modeClass: ModeClass = when (modeKey) {
            "CAR", "BIKE" -> ModeClass.DRIVE
            "CAB", "AUTO", "CYCLE" -> ModeClass.RIDE
            "BUS", "METRO", "TRAIN" -> ModeClass.TRANSIT
            "FLIGHT" -> ModeClass.FLY
            "SHIP", "FERRY" -> ModeClass.SAIL
            else -> ModeClass.WALK
        }
        /** "cab", "motorbike", "ferry": the word for the mode in a sentence. */
        val modeLabel: String = com.trippulse.app.domain.JourneyPlans.modeWord(modeKey)
        /** A distance as this traveller tells it: their units, and at sea the ship's. */
        fun km(m: Double): String = JourneyStory.km(m, input.measures, modeKey)
        val electric: Boolean = input.fuelType.equals("ELECTRIC", ignoreCase = true)
        val completed: Boolean = input.endedAtMs != null
        val elapsedS: Long = ((input.endedAtMs ?: input.nowMs) - input.startedAtMs) / 1000
        val length: Length = when {
            story.days.size > 1 && elapsedS > 20 * 3600 -> Length.MULTI_DAY
            elapsedS < 2 * 3600 -> Length.SHORT
            elapsedS < 6 * 3600 -> Length.HALF_DAY
            elapsedS < 14 * 3600 -> Length.LONG_DAY
            else -> Length.MARATHON
        }
        val departure: Daypart = Daypart.of(input.startedAtMs, z)
        val realStops: List<Entry.Stop> = story.stops.filter { it.items.isNotEmpty() }
        val drivingS: Long = story.movingSeconds
        val pace: Pace = run {
            val perHour = if (drivingS > 1800) realStops.size / (drivingS / 3600.0) else 0.0
            when {
                drivingS < 1800 -> Pace.STEADY
                perHour >= 0.6 -> Pace.LEISURELY
                perHour >= 0.25 -> Pace.STEADY
                else -> Pace.PRESSING
            }
        }
        /** Share of recorded driving that fell between 9 PM and 5 AM. */
        val nightShare: Double = run {
            val night = story.drives.sumOf { d -> if (d.seconds > 0) nightSeconds(d.atMs, d.endMs, z) * d.movingSeconds / d.seconds else 0L }
            if (drivingS > 0) night.toDouble() / drivingS else 0.0
        }
        val longestDrive: Entry.Drive? = story.longestDrive
        val first: String = JourneyStory.firstName(input.who)
        val name: String = JourneyStory.name(input.who)

        // The verbs of the mode.
        val went: String = when (modeClass) { ModeClass.DRIVE -> "drove"; ModeClass.RIDE -> "rode"; ModeClass.TRANSIT -> "travelled"; ModeClass.FLY -> "flew"; ModeClass.SAIL -> "sailed"; ModeClass.WALK -> "walked" }
        val going: String = when (modeClass) { ModeClass.DRIVE -> "driving"; ModeClass.RIDE -> "riding"; ModeClass.TRANSIT -> "travelling"; ModeClass.FLY -> "flying"; ModeClass.SAIL -> "sailing"; ModeClass.WALK -> "walking" }
        val theRoad: String = when (modeClass) { ModeClass.DRIVE, ModeClass.RIDE -> "the road"; ModeClass.TRANSIT -> "the line"; ModeClass.FLY -> "the air"; ModeClass.SAIL -> "the water"; ModeClass.WALK -> "the way" }
        val vehicle: String = when (modeKey) { "CAR" -> "the car"; "BIKE" -> "the motorbike"; "CAB" -> "the cab"; "BIKE_TAXI" -> "the bike taxi"; "AUTO" -> "the auto"; "BUS" -> "the bus"; "METRO" -> "the metro"; "TRAIN" -> "the train"; "FLIGHT" -> "the plane"; "SHIP" -> "the ship"; "FERRY" -> "the ferry"; "CYCLE" -> "the bicycle"; else -> "foot" }
        val refuelled: String = if (electric) "charged up" else "refuelled"

        private fun nightSeconds(a: Long, b: Long, z: ZoneId): Long {
            var s = 0L; var t = a
            while (t < b) {
                val h = Instant.ofEpochMilli(t).atZone(z).hour
                val step = minOf(b - t, 15 * 60_000L)
                if (h >= 21 || h < 5) s += step / 1000
                t += step
            }
            return s
        }
    }

    // ------------------------------------------------------------------------
    // The journey, told
    // ------------------------------------------------------------------------

    fun narrate(input: JourneyStory.Input, story: JourneyStory.Story, book: PlaceBook): List<String> {
        val c = Character(input, story)
        val d = Dice(seed(input.seedKey))
        val out = ArrayList<String>()
        out += opening(c, d)
        stagesLine(c, d)?.let { out += it }
        breaks(c, d)?.let { out += it }
        road(c, d)?.let { out += it }
        silence(c, d, book)?.let { out += it }
        night(c, d)?.let { out += it }
        closing(c, d)?.let { out += it }
        return out.map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * A journey made more than one way, told in order: "It was made in four
     * parts: by cab (12 min), on foot (6 min), by metro (24 min) and on foot
     * again (8 min)."
     */
    private fun stagesLine(c: Character, d: Dice): String? {
        val st = c.story.stages
        if (st.size < 2) return null
        val parts = st.mapIndexed { idx, sp ->
            val how = JourneyStory.byMode(sp.mode)
            val again = idx > 0 && st.subList(0, idx).any { it.mode.equals(sp.mode, ignoreCase = true) }
            val mins = sp.seconds / 60
            buildString {
                append(how)
                if (again) append(" again")
                if (mins >= 1) append(" (${JourneyStory.duration(sp.seconds)})")
            }
        }
        val list = JourneyStory.listJoin(parts)
        return d.pick(
            "It was made in ${Prose.words(st.size)} parts: $list.",
            "The way went $list.",
            "${Prose.words(st.size).replaceFirstChar { it.uppercase() }} ways of getting there, one after another: $list."
        )
    }

    /** The first paragraph: where, when, how far, and where things stand. */
    private fun opening(c: Character, d: Dice): String {
        val i = c.input; val z = c.z
        val dep = c.departure
        val start = JourneyStory.clock(i.startedAtMs, z)
        val day = JourneyStory.day(i.startedAtMs, z)
        val setOff = when (dep) {
            Daypart.DAWN -> d.pick("set off at first light", "was away at dawn", "left ${dep.at}")
            Daypart.SMALL_HOURS -> d.pick("set off ${dep.at}", "was on ${c.theRoad} before anyone else was up")
            Daypart.EARLY_MORNING -> d.pick("set off early", "made an early start", "left ${dep.at}")
            Daypart.MID_MORNING -> d.pick("set off ${dep.at}", "got going mid-morning")
            Daypart.MIDDAY -> d.pick("set off around midday", if (Instant.ofEpochMilli(i.startedAtMs).atZone(z).hour >= 12) "set off just after noon" else "set off late in the morning")
            Daypart.AFTERNOON, Daypart.LATE_AFTERNOON -> d.pick("set off ${dep.at}", "left ${dep.at}")
            Daypart.EVENING -> d.pick("set off ${dep.at}", "left as the evening came on")
            Daypart.NIGHT, Daypart.LATE_NIGHT -> d.pick("set off ${dep.at}", "left after dark", "started out ${dep.at}")
        }
        val how = when (c.modeClass) {
            ModeClass.DRIVE -> d.pick("by ${c.modeLabel}", "in ${c.vehicle}")
            ModeClass.RIDE -> "by ${c.modeLabel}"
            ModeClass.TRANSIT -> d.pick("by ${c.modeLabel}", "on ${c.vehicle}")
            ModeClass.FLY -> "by air"
            ModeClass.SAIL -> "by ${c.modeLabel}"
            ModeClass.WALK -> "on foot"
        }
        val sb = StringBuilder()
        sb.append(d.pick(
            "${c.name} $setOff from ${i.origin} at $start on $day, heading for ${i.destination} $how.",
            "On $day, ${c.name} $setOff from ${i.origin} at $start, bound for ${i.destination} $how.",
            "${c.name} left ${i.origin} for ${i.destination} $how, setting off at $start on $day."
        ))
        sb.append(' ')
        if (c.completed) {
            val end = i.endedAtMs!!
            val arr = JourneyStory.clock(end, z)
            val sameDay = Instant.ofEpochMilli(i.startedAtMs).atZone(z).toLocalDate() == Instant.ofEpochMilli(end).atZone(z).toLocalDate()
            val arrDay = if (sameDay) "" else " on ${JourneyStory.day(end, z)}"
            val dur = JourneyStory.duration(c.elapsedS)
            sb.append(d.pick(
                "${c.first} arrived at $arr$arrDay, ${c.km(i.distanceM)} and $dur later.",
                "The journey took $dur over ${c.km(i.distanceM)}; ${c.first} reached ${JourneyStory.short(i.destination)} at $arr$arrDay.",
                "${c.km(i.distanceM)} and $dur on, ${c.first} was at ${JourneyStory.short(i.destination)}: $arr$arrDay."
            ))
        } else {
            val covered = c.km(i.distanceM)
            val ofRoute = i.routeDistanceM?.takeIf { it > i.distanceM }?.let { " of the ${c.km(it)} route" } ?: ""
            val dur = JourneyStory.duration(c.elapsedS)
            val halting = story(c).halts.lastOrNull()?.let { it.endMs == null } == true
            val where = c.story.lastPlace
            val standing = when {
                halting && where != null -> if (c.story.halts.last().overnight) d.pick("resting ${JourneyStory.at(where)} for the night", "stopped for the night ${JourneyStory.at(where)}") else "halted ${JourneyStory.at(where)}"
                where != null -> d.pick("was last seen ${JourneyStory.near(where)}", "was last heard from ${JourneyStory.near(where)}")
                else -> null
            }
            val verb = if (halting) "is" else ""
            sb.append(when {
                standing == null -> d.pick("So far ${c.first} has covered $covered$ofRoute in $dur.", "$dur in, $covered$ofRoute is behind ${c.first}.")
                halting -> d.pick(
                    "So far ${c.first} has covered $covered$ofRoute in $dur, and is $standing.",
                    "$dur in, $covered$ofRoute is behind ${c.first}, who is $standing.",
                    "${c.first} has put $covered$ofRoute behind ${c.vehicle} in $dur and is $standing."
                )
                else -> d.pick(
                    "So far ${c.first} has covered $covered$ofRoute in $dur, and $standing.",
                    "$dur in, $covered$ofRoute is behind ${c.first}, who $standing.",
                    "${c.first} has put $covered$ofRoute behind ${c.vehicle} in $dur and $standing."
                )
            })
        }
        return sb.toString()
    }

    private fun story(c: Character) = c.story

    /** The rhythm of the stops, and what was had at them. */
    private fun breaks(c: Character, d: Dice): String? {
        val real = c.realStops
        if (real.isEmpty()) {
            return when {
                c.drivingS >= 3 * 3600 && c.modeClass == ModeClass.DRIVE -> d.pick(
                    "No break was logged in ${JourneyStory.duration(c.drivingS)} of ${c.going}.",
                    "${c.first} ${c.went} for ${JourneyStory.duration(c.drivingS)} without logging a break."
                )
                else -> null
            }
        }
        val z = c.z
        val sb = StringBuilder()
        val n = real.size
        val rhythm = when (c.pace) {
            Pace.LEISURELY -> d.pick("an unhurried day with", "a stop never far away:", "plenty of pauses along the way,")
            Pace.STEADY -> d.pick("a steady rhythm of", "well paced, with", "the stops came at an even rhythm:")
            Pace.PRESSING -> d.pick("long stretches between", "${c.first} pressed on, with only", "few pauses:")
        }
        val moving = c.story.drives.filter { it.movingSeconds >= 20 * 60 }
        val gap = if (moving.size >= 2) moving.map { it.movingSeconds }.average().toLong() else null
        sb.append(when {
            n == 1 -> d.pick("There was one proper break", "One proper break was logged", "The day had a single proper break")
            else -> "${rhythm.replaceFirstChar { it.uppercase() }} ${words(n)} proper breaks".let {
                if (rhythm.endsWith(":") || rhythm.endsWith(",")) "${rhythm.dropLast(1).replaceFirstChar { ch -> ch.uppercase() }} ${words(n)} proper breaks" else it
            }
        })
        gap?.let { sb.append(d.pick(", roughly one every ${JourneyStory.duration(it)} on ${c.theRoad}", ", about ${JourneyStory.duration(it)} of ${c.going} between each", " spaced about ${JourneyStory.duration(it)} apart")) }
        sb.append(". ")

        // Meals, in the order they came, each told once.
        val meals = real.filter { Item.FOOD in it.items }.sortedBy { it.atMs }.fold(ArrayList<Entry.Stop>()) { acc, s ->
            if (acc.none { it.meal == s.meal && abs(it.atMs - s.atMs) <= 6 * 3_600_000L }) acc += s; acc
        }
        if (meals.isNotEmpty()) {
            val bits = meals.map { s ->
                val what = (s.meal?.label ?: "a meal").lowercase(Locale.ENGLISH)
                val where = s.place?.takeIf { !s.loggedLater }?.let { " ${JourneyStory.at(JourneyStory.short(it))}" } ?: ""
                val whenAt = d.pick("at ${JourneyStory.clock(s.atMs, z)}", "${Daypart.of(s.atMs, z).at} (${JourneyStory.clock(s.atMs, z)})")
                "$what$where $whenAt"
            }
            sb.append(d.pick(
                "${JourneyStory.listJoin(bits).replaceFirstChar { it.uppercase() }}. ",
                "${c.first} had ${JourneyStory.listJoin(bits)}. ",
                "Meals: ${JourneyStory.listJoin(bits)}. "
            ))
        }
        val teas = real.filter { (Item.TEA in it.items || Item.SNACK in it.items) && Item.FOOD !in it.items }
        if (teas.isNotEmpty()) {
            val t = teas.first()
            val what = JourneyStory.stopTitle(t.items, null, null).lowercase(Locale.ENGLISH)
            val where = t.place?.let { " ${JourneyStory.near(JourneyStory.short(it))}" } ?: ""
            sb.append(d.pick(
                "There was $what$where ${Daypart.of(t.atMs, z).at}, around ${JourneyStory.clock(t.atMs, z)}. ",
                "${what.replaceFirstChar { it.uppercase() }}$where came ${Daypart.of(t.atMs, z).at}, around ${JourneyStory.clock(t.atMs, z)}. "
            ))
        }
        val fuel = real.filter { Item.FUEL in it.items || Item.CHARGE in it.items }
        if (fuel.isNotEmpty() && c.modeClass == ModeClass.DRIVE) {
            val places = fuel.mapNotNull { it.place?.let(JourneyStory::short) }.distinct()
            val times = if (fuel.size == 1) "once" else times(fuel.size)
            sb.append(d.pick(
                "${c.first} ${c.refuelled} $times${if (places.isNotEmpty()) ", ${JourneyStory.at(JourneyStory.listJoin(places))}" else ""}.",
                "${c.vehicle.replaceFirstChar { it.uppercase() }} was ${c.refuelled} $times${if (places.isNotEmpty()) ", ${JourneyStory.at(JourneyStory.listJoin(places))}" else ""}."
            ))
        }
        val restroom = real.count { Item.TOILET in it.items }
        val water = c.story.water
        if (restroom > 0 || water > 0) {
            val parts = ArrayList<String>()
            if (water > 0) parts += "water ${if (water == 1) "once" else times(water)}"
            if (restroom > 0) parts += "${words(restroom)} restroom stop${if (restroom == 1) "" else "s"}"
            sb.append(" ${d.pick("Along the way: ", "The log also shows ", "Also logged: ")}${JourneyStory.listJoin(parts)}.")
        }
        return sb.toString()
    }

    /** Tolls and the long stretches. */
    private fun road(c: Character, d: Dice): String? {
        val tolls = c.story.tolls
        val longest = c.longestDrive
        if (tolls == 0 && (longest == null || longest.seconds < 30 * 60)) return null
        val sb = StringBuilder()
        if (tolls > 0 && c.modeClass == ModeClass.DRIVE) {
            val named = c.story.drives.flatMap { it.tollNames }.distinct()
            val plural = if (tolls == 1) "toll plaza" else "toll plazas"
            sb.append(d.pick(
                "On ${c.theRoad} ${c.first} crossed ${words(tolls)} $plural",
                "${words(tolls).replaceFirstChar { it.uppercase() }} $plural were crossed",
                "The route passed ${words(tolls)} $plural"
            ))
            if (named.isNotEmpty()) sb.append(d.pick(", including ${JourneyStory.listJoin(named.take(3))}", ", among them ${JourneyStory.listJoin(named.take(3))}"))
            sb.append(". ")
        }
        if (longest != null && longest.movingSeconds >= 30 * 60) {
            val a = longest.fromPlace?.let(JourneyStory::short); val b = longest.toPlace?.let(JourneyStory::short)
            val span = if (a != null && b != null && a != b) " from $a to $b" else ""
            val night = Daypart.of(longest.atMs, c.z).let { it == Daypart.NIGHT || it == Daypart.LATE_NIGHT || it == Daypart.SMALL_HOURS }
            val dur = JourneyStory.duration(longest.movingSeconds)
            sb.append(d.pick(
                "The longest unbroken stretch was $dur$span (${c.km(longest.distanceM)})${if (night) ", ${c.going} through the night" else ""}.",
                "The longest pull${span.ifEmpty { "" }} ran $dur and ${c.km(longest.distanceM)} without a stop${if (night) ", after dark" else ""}.",
                "${dur.replaceFirstChar { it.uppercase() }} of ${c.going} without a break$span was the longest stretch, ${c.km(longest.distanceM)} in all${if (night) ", most of it at night" else ""}."
            ))
            if (longest.offlineMs > 0) sb.append(" ${d.pick(
                "The phone was out of contact for part of it, so the figures for that stretch are estimates.",
                "Part of that stretch passed with the phone silent; its distance is an estimate.",
                "For some of it the phone was out of contact, and the distance is worked out rather than measured."
            )}")
        }
        if (c.nightShare >= 0.5 && c.drivingS >= 2 * 3600) {
            sb.append(" ${d.pick(
                "Most of the ${c.going} was done at night.",
                "More than half the ${c.going} fell after dark.",
                "This was mostly a night ${if (c.modeClass == ModeClass.DRIVE) "drive" else "journey"}."
            )}")
        }
        return sb.toString()
    }

    /** A silence, told honestly, with what it means for the record. */
    private fun silence(c: Character, d: Dice, book: PlaceBook): String? {
        if (c.story.offline.isEmpty()) return null
        val z = c.z
        val out = ArrayList<String>()
        for ((from, to) in c.story.offline) {
            val dur = JourneyStory.duration((to - from) / 1000)
            val s = c.input.samples
            val b4 = s.lastOrNull { it.tMs <= from }
            val aft = s.firstOrNull { it.tMs >= to }
            val before = b4?.let { book.describe(it.lat, it.lng) }?.let(JourneyStory::short)
            val after = aft?.let { book.describe(it.lat, it.lng) }?.let(JourneyStory::short)
            val jumped = if (b4 != null && aft != null) PlaceBook.distanceM(b4.lat, b4.lng, aft.lat, aft.lng) else 0.0
            val silent = c.story.drives.filter { it.offlineMs > 0 && it.atMs < to && it.endMs > from }
            val inferred = silent.sumOf { it.inferredTolls }
            val tollsIn = silent.sumOf { it.tolls } - inferred
            val nextDay = Instant.ofEpochMilli(from).atZone(z).toLocalDate() != Instant.ofEpochMilli(to).atZone(z).toLocalDate()
            val sb = StringBuilder()
            sb.append(d.pick(
                "The phone was out of contact for $dur from ${JourneyStory.clock(from, z)}",
                "From ${JourneyStory.clock(from, z)} the phone fell silent for $dur",
                "There is a gap of $dur in the record from ${JourneyStory.clock(from, z)}"
            ))
            before?.let { sb.append(" ${JourneyStory.near(it)}") }
            sb.append(d.pick(", reconnecting at", " and came back at", ", and was heard from again at"))
            sb.append(" ${JourneyStory.clock(to, z)}${if (nextDay) " the next day" else ""}")
            after?.let { sb.append(" ${JourneyStory.at(it)}") }
            if (jumped > 5_000) sb.append(", roughly ${c.km(jumped)} further on")
            sb.append(". ")
            sb.append(d.pick(
                "Entries logged in between were sent when it came back.",
                "Anything logged meanwhile reached the record once the phone reconnected.",
                "What was logged during the gap arrived with the reconnection."
            ))
            if (inferred > 0) sb.append(" ${d.pick(
                "${words(inferred).replaceFirstChar { it.uppercase() }} toll plaza${if (inferred == 1) "" else "s"} on the road between those two points ${if (inferred == 1) "was" else "were"} worked out and counted.",
                "The road between those points passes ${words(inferred)} toll plaza${if (inferred == 1) "" else "s"}, so ${if (inferred == 1) "it was" else "they were"} counted.",
                "${words(inferred).replaceFirstChar { it.uppercase() }} toll plaza${if (inferred == 1) "" else "s"} lie on that road and ${if (inferred == 1) "was" else "were"} added to the count."
            )}")
            if (tollsIn > 0) sb.append(" ${words(tollsIn).replaceFirstChar { it.uppercase() }} toll crossing${if (tollsIn == 1) "" else "s"} on this stretch ${if (tollsIn == 1) "was" else "were"} added to the record afterwards.")
            else if (inferred == 0 && c.modeClass == ModeClass.DRIVE) sb.append(" Toll plazas passed in that time would not have been noticed.")
            out += sb.toString()
        }
        return out.joinToString(" ")
    }

    /** The night, or the halt. */
    private fun night(c: Character, d: Dice): String? {
        val h = c.story.halts.lastOrNull() ?: return null
        val z = c.z
        val sb = StringBuilder()
        val dayPart = Daypart.of(h.atMs, z)
        val whenAt = buildString {
            append(d.pick("At ${JourneyStory.clock(h.atMs, z)}", "${dayPart.at.replaceFirstChar { it.uppercase() }}, at ${JourneyStory.clock(h.atMs, z)}", "By ${JourneyStory.clock(h.atMs, z)}"))
            if (Instant.ofEpochMilli(c.input.startedAtMs).atZone(z).toLocalDate() != Instant.ofEpochMilli(h.atMs).atZone(z).toLocalDate()) append(" on ${JourneyStory.day(h.atMs, z)}")
        }
        val did = when (h.type) {
            Halts.Type.ROOM -> d.pick("took a room", "checked into a room", "found a room")
            Halts.Type.FRIEND_FAMILY -> d.pick("stopped with friends or family", "called a halt with family")
            Halts.Type.REST_STOP -> d.pick("pulled in at a rest stop", "stopped at a rest area")
            Halts.Type.OTHER -> d.pick("stopped", "called a halt")
        }
        sb.append("$whenAt, ${c.first} $did")
        h.place?.let { sb.append(" ${JourneyStory.at(it)}") }
        sb.append(if (h.overnight) d.pick(" for the night.", " and settled in for the night.", " to sleep.") else " for a halt.")
        if (h.endMs != null && h.endMs > h.atMs && h.endMs != c.input.endedAtMs) {
            sb.append(" ${d.pick(
                "The journey resumed at ${JourneyStory.clock(h.endMs, z)}, after ${JourneyStory.duration((h.endMs - h.atMs) / 1000)}.",
                "${c.first} was back on ${c.theRoad} at ${JourneyStory.clock(h.endMs, z)}, ${JourneyStory.duration((h.endMs - h.atMs) / 1000)} later."
            )}")
        } else if (h.endMs == null && !c.completed) {
            val remaining = c.input.routeDistanceM?.let { it - c.input.distanceM }?.takeIf { it > 1_000 }
            remaining?.let { sb.append(" ${d.pick("${c.km(it)} remain to ${JourneyStory.short(c.input.destination)}.", "That leaves about ${c.km(it)} for the morning.", "${JourneyStory.short(c.input.destination)} is still ${c.km(it)} away.")}") }
        }
        return sb.toString()
    }

    /** A last line for a finished journey. */
    private fun closing(c: Character, d: Dice): String? {
        if (!c.completed) return null
        val stops = c.realStops.size
        val tolls = c.story.tolls
        return when (c.length) {
            Length.SHORT -> d.pick("A short hop, done in ${JourneyStory.duration(c.elapsedS)}.", null, "Door to door in ${JourneyStory.duration(c.elapsedS)}.")
            Length.HALF_DAY -> d.pick("Half a day's travel, ${words(stops)} break${if (stops == 1) "" else "s"} and ${c.km(c.input.distanceM)}.", null)
            Length.LONG_DAY -> d.pick("A full day on ${c.theRoad}: ${c.km(c.input.distanceM)}, ${words(stops)} break${if (stops == 1) "" else "s"}${if (tolls > 0) ", ${words(tolls)} tolls" else ""}.", "That was the whole day: ${JourneyStory.duration(c.elapsedS)} from door to door.", null)
            Length.MARATHON -> d.pick("A long haul by any measure: ${JourneyStory.duration(c.elapsedS)} and ${c.km(c.input.distanceM)}.", null)
            Length.MULTI_DAY -> d.pick("${words(c.story.days.size).replaceFirstChar { it.uppercase() }} days, ${c.km(c.input.distanceM)}, ${words(stops)} breaks and ${words(c.story.halts.size)} night${if (c.story.halts.size == 1) "" else "s"} on the way.", null)
        }
    }

    // ------------------------------------------------------------------------
    // Smaller pieces: a day's lead line, captions
    // ------------------------------------------------------------------------

    /** One line under a day header: what that day was. */
    fun dayLead(day: JourneyStory.Day, c: Character, d: Dice): String? {
        val drives = day.entries.filterIsInstance<Entry.Drive>()
        val stops = day.entries.filterIsInstance<Entry.Stop>().filter { it.items.isNotEmpty() }
        val halt = day.entries.filterIsInstance<Entry.Halt>().lastOrNull()
        val arrive = day.entries.any { it is Entry.Arrive }
        val km = drives.sumOf { it.distanceM }
        val driveS = drives.sumOf { it.movingSeconds }
        val meals = stops.mapNotNull { it.meal }.distinct()
        return when {
            arrive && km > 0 -> d.pick("${c.km(km)} to the finish, ${words(stops.size)} stop${if (stops.size == 1) "" else "s"}.", "The last ${c.km(km)}.")
            halt != null && km > 1_000 -> d.pick("${c.km(km)} and then a halt ${halt.place?.let { JourneyStory.at(JourneyStory.short(it)) } ?: ""}.".replace(" .", "."), "${JourneyStory.duration(driveS)} of ${c.going}, ending ${halt.place?.let { JourneyStory.at(JourneyStory.short(it)) } ?: "with a halt"}.")
            km > 1_000 -> d.pick(
                "${c.km(km)} in ${JourneyStory.duration(driveS)} of ${c.going}${if (stops.isNotEmpty()) ", ${words(stops.size)} stop${if (stops.size == 1) "" else "s"}" else ""}.",
                "${words(stops.size).replaceFirstChar { it.uppercase() }} stop${if (stops.size == 1) "" else "s"} over ${c.km(km)}${if (meals.isNotEmpty()) ", ${JourneyStory.listJoin(meals.map { it.label.lowercase(Locale.ENGLISH) })} on the way" else ""}.".replace("No stops", "No stops")
            )
            stops.isNotEmpty() -> "${words(stops.size).replaceFirstChar { it.uppercase() }} stop${if (stops.size == 1) "" else "s"}, little distance."
            else -> null
        }
    }

    /** Why a stretch without a stop is told at all. */
    fun stretchNote(d: Entry.Drive, c: Character, dice: Dice): String? {
        if (d.offlineMs > 0) return null
        val part = Daypart.of(d.atMs, c.z)
        return when {
            d.seconds >= 3 * 3600 -> dice.pick("A long pull ${part.at}", "The longest stretch of the day", "No stops for ${JourneyStory.duration(d.seconds)}")
            d.tolls >= 3 -> dice.pick("${words(d.tolls).replaceFirstChar { it.uppercase() }} toll plazas on this stretch", "Toll after toll")
            else -> null
        }
    }

    // ------------------------------------------------------------------------
    // Money
    // ------------------------------------------------------------------------

    data class CostShare(val label: String, val amount: Double, val entries: Int, val isFuel: Boolean, val isFood: Boolean, val isStay: Boolean)

    fun money(
        c: Character, d: Dice, total: Double, shares: List<CostShare>, litres: Double, kwh: Double,
        perUnit: String?, unit: String, efficiency: String?, unknown: Int, passCrossings: Int, moneyFmt: (Double) -> String
    ): List<String> {
        if (shares.isEmpty()) return listOf(d.pick("No expenses were recorded on this journey.", "Nothing was spent, or nothing was logged: the ledger is empty."))
        val out = ArrayList<String>()
        val top = shares.first()
        val pct = Math.round(top.amount / total * 100)
        out += buildString {
            append(d.pick(
                "${c.name} spent ${moneyFmt(total)} on this journey",
                "This journey cost ${moneyFmt(total)}",
                "The ledger for this journey comes to ${moneyFmt(total)}"
            ))
            perUnit?.let { append(d.pick(", about $it for every $unit travelled", ", or $it per $unit", ", which works out at $it a $unit")) }
            append(". ")
            append(d.pick(
                "${top.label} took the largest share, ${moneyFmt(top.amount)} ($pct%)",
                "The biggest line was ${top.label.lowercase(Locale.ENGLISH)}: ${moneyFmt(top.amount)}, $pct% of the total",
                "$pct% of it, ${moneyFmt(top.amount)}, went on ${top.label.lowercase(Locale.ENGLISH)}"
            ))
            if (top.isFuel && litres > 0) {
                append(" for ${num(litres)} ${if (c.electric) "kWh" else "L"}")
                efficiency?.let { append(" (about $it)") }
            } else if (top.isFuel && kwh > 0) {
                append(" for ${num(kwh)} kWh"); efficiency?.let { append(" (about $it)") }
            }
            append(".")
        }
        val rest = shares.drop(1)
        if (rest.isNotEmpty()) {
            out += JourneyStory.listJoin(rest.take(3).map { s ->
                val tail = when {
                    s.isFood && c.story.meals.filterKeys { k -> k != Nourishment.SNACK && k != Nourishment.TEA_COFFEE }.values.sum() > 0 ->
                        " across ${words(c.story.meals.filterKeys { k -> k != Nourishment.SNACK && k != Nourishment.TEA_COFFEE }.values.sum())} meal${if (c.story.meals.values.sum() == 1) "" else "s"}"
                    s.isStay -> c.story.halts.lastOrNull()?.place?.let { " for the night ${JourneyStory.at(JourneyStory.short(it))}" } ?: ""
                    s.entries > 1 -> " over ${s.entries} entries"
                    else -> ""
                }
                "${s.label.lowercase(Locale.ENGLISH)} came to ${moneyFmt(s.amount)}$tail"
            }).replaceFirstChar { it.uppercase() } + "."
        }
        if (passCrossings > 0) {
            out += if (passCrossings >= c.story.tolls) d.pick(
                "All ${words(c.story.tolls)} toll plazas were covered by the ${c.input.tollPassName ?: "pass"}, so tolls added nothing to the bill.",
                "Tolls cost nothing: every one of the ${words(c.story.tolls)} crossings was on the annual pass."
            ) else "${words(passCrossings).replaceFirstChar { it.uppercase() }} of the ${words(c.story.tolls)} toll crossings were covered by the ${c.input.tollPassName ?: "pass"}."
        }
        if (unknown > 0) out += "${words(unknown).replaceFirstChar { it.uppercase() }} expense${if (unknown == 1) " has" else "s have"} no amount yet, so the true total is higher than shown."
        return out
    }

    // ------------------------------------------------------------------------

    /** Printed wherever tolls are counted and the phone was ever out of contact. */
    const val TOLL_NOTE = "Toll plazas are noticed from the phone's position. For a stretch the phone was out of contact, the plazas on the road between where it fell silent and where it came back are counted and marked as worked out."

    fun words(n: Int): String = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve").getOrNull(n) ?: n.toString()
    fun times(n: Int): String = when (n) { 1 -> "once"; 2 -> "twice"; else -> "${words(n)} times" }
    private fun num(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(Locale.ENGLISH, v)
}
