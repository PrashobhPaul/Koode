package com.trippulse.app.data.export.report

import com.trippulse.app.domain.Expenses
import com.trippulse.app.domain.JourneyAnalytics
import com.trippulse.app.domain.Measures
import com.trippulse.app.domain.Nourishment
import com.trippulse.app.domain.Pictures
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.report.JourneyStory
import com.trippulse.app.domain.report.JourneyStory.Entry
import com.trippulse.app.domain.report.JourneyStory.Item
import com.trippulse.app.domain.report.PlaceBook
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The three documents, as pages of blocks.
 *
 *  - [journey]: the story of the journey: what it was like, where it paused
 *    and why, the route, then every stop in order. Shareable; never money.
 *  - [expenses]: where the money went and why, then every entry with where
 *    and when it was spent. The traveller's own.
 *  - [lastKnown]: for an emergency. Where the phone last was, named and as
 *    coordinates, how it went quiet, and the hours before.
 *
 * Everything is in plain Kotlin so the same pages can be checked in tests and
 * previews; the phone only supplies the drawing surface.
 */
object Reports {

    // ------------------------------------------------------------------------
    // The journey report
    // ------------------------------------------------------------------------

    data class JourneyInput(
        val story: JourneyStory.Story,
        val input: JourneyStory.Input,
        val book: PlaceBook,
        val analytics: JourneyAnalytics.JourneyReport?,
        val tripRef: String,
        val measures: Measures,
        val preparedAtMs: Long,
        /** "47 crossings left" — the vehicle's FASTag balance, when tracked. */
        val fastagSummary: String? = null
    )

    fun journey(j: JourneyInput): Report {
        val s = j.story; val i = j.input; val z = i.zone; val m = j.measures
        val completed = i.endedAtMs != null
        val modePic = Pictures.mode(i.mode)
        val elapsed = ((i.endedAtMs ?: i.nowMs) - i.startedAtMs) / 1000
        val blocks = ArrayList<Block>()
        // Driving time and speed from the stretches the phone actually recorded:
        // a silence is neither driving nor stopping, so it counts as neither.
        val recorded = s.drives.filter { it.offlineMs == 0L }
        val moving = recorded.sumOf { it.seconds } to recorded.sumOf { it.distanceM }
        val avgKmh = if (moving.first >= 600) moving.second / 1000.0 / (moving.first / 3600.0) else null
        val stoppedS = s.stops.sumOf { it.seconds ?: 0L }

        blocks += Hero(
            eyebrow = if (completed) "Journey report" else "Journey so far",
            title = "${i.origin} to ${i.destination}",
            lines = listOf(
                dateRange(i.startedAtMs, i.endedAtMs, z),
                "${JourneyStory.name(i.who)} · by ${TransportCatalog.label(i.mode).lowercase(Locale.ENGLISH)} · Journey ${j.tripRef}"
            ),
            pill = s.status,
            picture = modePic?.let { Icon(it, mirrored = Pictures.modeFacesLeft(i.mode)) }
        )
        blocks += Space(18f)
        val a = j.analytics
        blocks += Tiles(listOfNotNull(
            Tiles.Tile(m.distance(i.distanceM), if (completed) "Distance" else "Covered so far", Icon(modePic, "road"), accent = true),
            Tiles.Tile(JourneyStory.duration(elapsed), "Time on the way", Icon(glyph = "clock")),
            Tiles.Tile(JourneyStory.duration(moving.first), "Driving", Icon(glyph = "road")),
            Tiles.Tile(s.stops.count { it.items.isNotEmpty() }.toString(), "Breaks", Icon(Pictures.REST)),
            Tiles.Tile(s.tolls.toString(), "Toll plazas", Icon(glyph = "toll")),
            avgKmh?.let { Tiles.Tile(m.speed(it), "Average speed", Icon(glyph = "clock")) }
        ).take(6), columns = 3)

        blocks += Space(16f)
        blocks += Heading("The story", kicker = "At a glance")
        s.paragraphs.forEachIndexed { k, p ->
            blocks += Paragraph(p, if (k == 0) Type.body.copy(size = 11.4f, color = Ink.NIGHT) else Type.body, after = 8f)
        }

        if (s.highlights.isNotEmpty()) {
            blocks += Space(10f)
            blocks += Heading("Highlights")
            s.highlights.chunked(2).forEach { pair ->
                val (l, r) = pair[0] to pair.getOrNull(1)
                blocks += HighlightRow(hl(l), r?.let(::hl))
            }
        }

        if (i.samples.size >= 2) {
            blocks += Space(8f)
            blocks += Heading("The route")
            blocks += RouteMap(
                path = i.samples.map { RouteMap.Point(it.tMs, it.lat, it.lng) },
                markers = s.stops.filter { it.lat != null && it.lng != null && !it.loggedLater && it.items.isNotEmpty() }
                    .map { RouteMap.Marker(it.lat!!, it.lng!!, stopIcon(it)) } +
                    s.halts.filter { it.lat != null && it.lng != null }.map { RouteMap.Marker(it.lat!!, it.lng!!, Icon(Pictures.STAY)) },
                startLabel = JourneyStory.short(i.origin),
                endLabel = if (completed) JourneyStory.short(i.destination) else s.lastPlace?.let { "Now · ${JourneyStory.short(it)}" },
                endIcon = if (!completed && s.halts.lastOrNull()?.endMs == null && s.halts.isNotEmpty()) Icon(Pictures.STAY) else null,
                endIsCurrent = !completed
            )
            blocks += Footnote("The line is the path the phone recorded. A dashed line is a stretch with the phone out of contact.")
        }

        blocks += Space(10f)
        blocks += Heading("Stop by stop", kicker = if (s.days.size > 1) "${s.days.size} days" else null)
        blocks += timeline(s, i, completed)

        if (a != null && a.legs.size > 1) {
            blocks += Space(10f)
            blocks += Heading("Stages")
            blocks += Facts(a.legs.map { leg ->
                (TransportCatalog.label(leg.mode) + (leg.seat?.takeIf { it.isNotBlank() }?.let { " · seat $it" } ?: "")) to
                    "${leg.fromName} → ${leg.toName}" + (leg.seconds?.let { " · ${JourneyStory.duration(it)}" } ?: "")
            })
        }

        blocks += Space(10f)
        blocks += Heading("How the journey went")
        blocks += Facts(buildList {
            add("Driving" to "${JourneyStory.duration(moving.first)} over ${m.distance(moving.second)}")
            if (stoppedS > 0) add("At stops" to "${JourneyStory.duration(stoppedS)} across ${s.stops.size} stop${if (s.stops.size == 1) "" else "s"}")
            s.halts.forEach { h -> add((if (h.overnight) "Overnight" else "Halt") to listOfNotNull(h.place?.let(JourneyStory::short), h.endMs?.let { e -> JourneyStory.duration((e - h.atMs) / 1000) } ?: "from ${JourneyStory.clock(h.atMs, z)}").joinToString(" · ")) }
            avgKmh?.let { add("Average while driving" to m.speed(it)) }
            a?.topSpeedKmh?.takeIf { it > 0 }?.let { add("Top speed" to m.speed(it)) }
            s.drives.filter { it.offlineMs == 0L }.maxByOrNull { it.seconds }?.let { d ->
                add("Longest stretch without a break" to "${JourneyStory.duration(d.seconds)} · ${m.distance(d.distanceM)}" +
                    listOfNotNull(d.fromPlace, d.toPlace).takeIf { it.size == 2 }?.let { " · ${JourneyStory.short(it[0])} → ${JourneyStory.short(it[1])}" }.orEmpty())
            }
            s.stops.filter { it.seconds != null }.maxByOrNull { it.seconds!! }?.let { st ->
                add("Longest stop" to "${JourneyStory.duration(st.seconds!!)} · ${st.title}")
            }
            val mealText = s.meals.filterValues { it > 0 }.entries.joinToString(" · ") { (k, v) -> if (v == 1) k.label else "${k.label} × $v" }
            if (mealText.isNotBlank()) add("Food and drink" to mealText)
            if (s.water > 0) add("Water" to "Logged ${if (s.water == 1) "once" else "${s.water} times"}")
            if (s.offlineMs > 0) add("Out of contact" to JourneyStory.duration(s.offlineMs / 1000))
            j.fastagSummary?.let { add("FASTag" to it) }
        })

        blocks += Space(6f)
        blocks += Footnote(
            "Times are the phone's local time. Places come from the traveller's saved places and the phone's own map data. " +
                "Distances are as the phone measured them; a stretch with the phone out of contact is estimated."
        )
        return Report(
            fileLabel = "Koode-journey-${j.tripRef.filter { !it.isWhitespace() }}",
            runningTitle = "${JourneyStory.short(i.origin)} → ${JourneyStory.short(i.destination)} · Journey report",
            footer = "Journey ${j.tripRef} · Prepared ${stamp(j.preparedAtMs, z)}",
            blocks = blocks,
            title = "Journey report"
        )
    }

    /** Every day, every stop, with the road between. Shared by the journey and emergency reports. */
    fun timeline(s: JourneyStory.Story, i: JourneyStory.Input, completed: Boolean, sinceMs: Long? = null): List<Block> {
        val z = i.zone
        val out = ArrayList<Block>()
        val days = s.days.map { d -> d to d.entries.filter { sinceMs == null || it.atMs >= sinceMs || (it is Entry.Halt && (it.endMs ?: Long.MAX_VALUE) >= sinceMs) } }
            .filter { it.second.isNotEmpty() }
        val flat = days.flatMap { it.second }
        val lastNode = flat.lastOrNull { it !is Entry.Drive }
        val firstNode = flat.firstOrNull { it !is Entry.Drive }
        for ((day, entries) in days) {
            if (s.days.size > 1 || sinceMs != null) {
                out += DayHeader(day.number, day.title)
            }
            for (e in entries) {
                val above = e !== firstNode
                val below = e !== lastNode || !completed
                out += when (e) {
                    is Entry.Depart -> TimelineNode(
                        JourneyStory.clock(e.atMs, z), null, Icon(Pictures.place(e.place)),
                        "Set off from ${e.place}", listOf("By ${TransportCatalog.label(i.mode).lowercase(Locale.ENGLISH)}"),
                        railAbove = false, railBelow = below, ring = Ink.GREEN, strong = true
                    )
                    is Entry.Drive -> TimelineLeg(
                        text = buildString {
                            append(if (e.offlineMs > 0) "Roughly ${JourneyStory.km(e.distanceM)}" else "Drove ${JourneyStory.km(e.distanceM)}")
                            append(" · ${JourneyStory.duration(e.seconds)}")
                            if (e.tolls > 0) append(" · ${e.tolls} toll${if (e.tolls == 1) "" else "s"}")
                        },
                        sub = when {
                            e.offlineMs > 0 -> "Phone out of contact for ${JourneyStory.duration(e.offlineMs / 1000)} of this stretch"
                            e.tollNames.isNotEmpty() -> "Including ${JourneyStory.listJoin(e.tollNames)}"
                            else -> null
                        },
                        dashed = e.offlineMs > 0,
                        glyph = if (e.offlineMs > 0) "offline" else "road"
                    )
                    is Entry.Stop -> TimelineNode(
                        JourneyStory.clock(e.atMs, z),
                        when { e.ongoing -> "now"; e.seconds != null -> JourneyStory.duration(e.seconds!!); else -> null },
                        stopIcon(e),
                        e.title,
                        listOfNotNull(
                            e.endMs?.takeIf { (e.seconds ?: 0) >= 60 }?.let { "${JourneyStory.clock(e.atMs, z)} – ${JourneyStory.clock(it, z)}" },
                            if (e.loggedLater) "Time as the traveller gave it" else null,
                            if (e.items.isEmpty()) "Nothing was logged at this stop" else null
                        ),
                        chips = chips(e),
                        railAbove = above, railBelow = below
                    )
                    is Entry.Halt -> TimelineNode(
                        JourneyStory.clock(e.atMs, z), null, Icon(Pictures.STAY), e.title,
                        listOf(e.endMs?.let { "Until ${JourneyStory.clock(it, z)} · ${JourneyStory.duration((it - e.atMs) / 1000)}" }
                            ?: "Resting here now"),
                        railAbove = above, railBelow = below, ring = Ink.AMBER, strong = true
                    )
                    is Entry.Moment -> TimelineNode(
                        JourneyStory.clock(e.atMs, z), e.endMs?.let { JourneyStory.duration((it - e.atMs) / 1000) },
                        Icon(glyph = momentGlyph(e.kind)), e.text,
                        listOfNotNull(e.place?.let { if (e.kind == JourneyStory.MomentKind.OFFLINE) "Back in contact ${JourneyStory.at(it)}" else JourneyStory.near(it).replaceFirstChar { c -> c.uppercase() } }),
                        railAbove = above, railBelow = below,
                        ring = if (e.kind == JourneyStory.MomentKind.SOS || e.kind == JourneyStory.MomentKind.INCIDENT) Ink.RED else Ink.FAINT
                    )
                    is Entry.Arrive -> TimelineNode(
                        JourneyStory.clock(e.atMs, z), null, Icon(Pictures.place(e.place)),
                        "Arrived at ${e.place}", emptyList(),
                        railAbove = above, railBelow = false, ring = Ink.SKY, strong = true
                    )
                }
            }
        }
        return out
    }

    private fun hl(h: JourneyStory.Highlight) = HighlightRow.Item(Icon(h.picture, h.glyph ?: "dot"), h.title, h.detail)

    fun stopIcon(st: Entry.Stop): Icon = when {
        st.picture != null && !(st.items == setOf(Item.TEA) || st.items == setOf(Item.SNACK) || st.items == setOf(Item.TEA, Item.SNACK)) -> Icon(st.picture)
        Item.TEA in st.items -> Icon(Pictures.RESTAURANT, "tea")
        Item.SNACK in st.items -> Icon(Pictures.RESTAURANT, "snack")
        else -> Icon(glyph = "stopped")
    }

    private fun chips(st: Entry.Stop): List<Pair<Icon, String>> = st.items.sortedBy { it.ordinal }.map { item ->
        when (item) {
            Item.FOOD -> Icon(Pictures.FOOD) to (st.meal?.label ?: "Meal")
            Item.TEA -> Icon(glyph = "tea") to "Tea / coffee"
            Item.SNACK -> Icon(glyph = "snack") to "Snack"
            Item.WATER -> Icon(Pictures.WATER) to "Water"
            Item.TOILET -> Icon(Pictures.TOILET) to "Restroom"
            Item.REST -> Icon(Pictures.REST) to "Rest"
            Item.FUEL -> Icon(Pictures.FUEL) to "Fuel"
            Item.CHARGE -> Icon(Pictures.FUEL) to "Charge"
        }
    }

    private fun momentGlyph(k: JourneyStory.MomentKind) = when (k) {
        JourneyStory.MomentKind.OFFLINE -> "offline"
        JourneyStory.MomentKind.SWITCHED_OFF -> "phone"
        JourneyStory.MomentKind.SIM_CHANGED -> "phone"
        JourneyStory.MomentKind.SOS, JourneyStory.MomentKind.INCIDENT -> "sos"
        JourneyStory.MomentKind.SOS_RESOLVED -> "flag"
        JourneyStory.MomentKind.NOTE, JourneyStory.MomentKind.VEHICLE -> "note"
        JourneyStory.MomentKind.DESTINATION -> "pin"
        JourneyStory.MomentKind.PASSENGER -> "people"
        JourneyStory.MomentKind.MODE -> "swap"
    }

    // ------------------------------------------------------------------------
    // The expense report
    // ------------------------------------------------------------------------

    data class Expense(
        val category: Expenses.Category,
        val item: String,
        val amount: Double,
        val quantity: Double?,
        val unit: String?,
        val atMs: Long,
        val note: String?
    )

    data class ExpenseInput(
        val story: JourneyStory.Story,
        val input: JourneyStory.Input,
        val book: PlaceBook,
        val analytics: JourneyAnalytics.JourneyReport,
        val expenses: List<Expense>,
        val opportunities: List<Expenses.Opportunity>,
        val passCrossings: Int,
        val approvedAtMs: Long?,
        val tripRef: String,
        val measures: Measures,
        val preparedAtMs: Long
    )

    fun expenses(x: ExpenseInput): Report {
        val i = x.input; val z = i.zone; val m = x.measures; val s = x.story
        val total = x.expenses.sumOf { it.amount }
        val distance = i.distanceM
        val byCat = x.expenses.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amount } }
            .entries.sortedByDescending { it.value }
        val unknown = x.opportunities.filter { it.status == Expenses.Status.UNKNOWN || it.open }
        val litres = x.expenses.filter { it.category == Expenses.Category.FUEL && it.unit != "kWh" }.sumOf { it.quantity ?: 0.0 }
        val kwh = x.expenses.filter { it.unit == "kWh" }.sumOf { it.quantity ?: 0.0 }
        val placeAt = { t: Long -> placeAt(t, i, x.book, s) }

        val blocks = ArrayList<Block>()
        blocks += Hero(
            eyebrow = "Travel expenses",
            title = "${i.origin} to ${i.destination}",
            figure = money(m, total),
            figureLabel = if (unknown.isEmpty()) "spent" else "recorded",
            lines = listOf(
                dateRange(i.startedAtMs, i.endedAtMs, z) + " · " + m.distance(distance),
                "${JourneyStory.name(i.who)} · Journey ${x.tripRef}"
            ),
            pill = x.approvedAtMs?.let { "Verified by ${JourneyStory.firstName(i.who)}" } ?: "Private to you",
            picture = Icon(glyph = "wallet").let { Pictures.mode(i.mode)?.let { p -> Icon(p, mirrored = Pictures.modeFacesLeft(i.mode)) } ?: it }
        )
        blocks += Space(18f)
        blocks += Tiles(listOfNotNull(
            perUnit(m, total, distance)?.let { Tiles.Tile(it, "Per ${m.distanceUnit}", Icon(glyph = "road"), accent = true) },
            if (litres > 0) Tiles.Tile("${num(litres)} ${m.volumeUnit}", "Fuel bought", Icon(Pictures.FUEL)) else null,
            m.efficiency(distance, litres)?.let { Tiles.Tile(it, "Fuel efficiency", Icon(Pictures.FUEL)) }
                ?: m.electricEfficiency(distance, kwh)?.let { Tiles.Tile(it, "Energy use", Icon(Pictures.FUEL)) },
            Tiles.Tile(x.expenses.size.toString(), if (x.expenses.size == 1) "Entry" else "Entries", Icon(glyph = "receipt")),
            byCat.firstOrNull()?.let { Tiles.Tile(it.key.label, "Biggest share", categoryIcon(it.key)) },
            if (s.tolls > 0) Tiles.Tile(s.tolls.toString(), if (x.passCrossings >= s.tolls) "Tolls · annual pass" else "Toll plazas", Icon(glyph = "toll")) else null
        ).take(6), columns = 3)

        blocks += Space(16f)
        blocks += Heading("Where the money went")
        moneyStory(x, total, byCat, litres, unknown).forEach { blocks += Paragraph(it, after = 8f) }
        blocks += Space(4f)
        for ((cat, amount) in byCat) {
            val rows = x.expenses.filter { it.category == cat }
            val qty = rows.sumOf { it.quantity ?: 0.0 }
            val unit = rows.firstNotNullOfOrNull { it.unit }
            val sub = buildList {
                add("${rows.size} ${if (rows.size == 1) "entry" else "entries"}")
                if (qty > 0 && unit != null) add("${num(qty)} $unit")
                rows.mapNotNull { placeAt(it.atMs)?.let(JourneyStory::short) }.distinct().takeIf { it.isNotEmpty() }?.let { add(it.take(2).joinToString(", ")) }
            }.joinToString(" · ")
            blocks += ShareBar(categoryIcon(cat), cat.label, sub, money(m, amount), if (total > 0) amount / total else 0.0)
        }
        if (x.passCrossings > 0) {
            blocks += ShareBar(Icon(glyph = "toll"), "Tolls", "${x.passCrossings} crossing${if (x.passCrossings == 1) "" else "s"} on the FASTag annual pass", "Free", 0.0)
        }

        blocks += Space(12f)
        blocks += Heading("Every expense")
        val byDay = x.expenses.sortedBy { it.atMs }.groupBy { Instant.ofEpochMilli(it.atMs).atZone(z).toLocalDate() }
        for ((date, rows) in byDay) {
            val title = DAY.withZone(z).format(date.atStartOfDay(z))
            val header = LedgerHeader(title, money(m, rows.sumOf { it.amount }))
            val cont = LedgerHeader("$title (continued)", null)
            blocks += header
            for (e in rows) {
                val where = placeAt(e.atMs)
                blocks += LedgerRow(
                    icon = categoryIcon(e.category),
                    title = e.item.ifBlank { e.category.label },
                    sub = listOfNotNull(
                        JourneyStory.clock(e.atMs, z),
                        where?.let { JourneyStory.short(it) },
                        e.category.label.takeIf { e.item.isNotBlank() && !e.item.equals(it, true) },
                        e.note?.takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    amount = money(m, e.amount),
                    amountSub = if (e.quantity != null && e.unit != null) "${num(e.quantity)} ${e.unit}" else null,
                    repeatHeader = cont
                )
            }
        }
        if (unknown.isNotEmpty()) {
            val head = LedgerHeader("Amounts not recorded", null)
            blocks += head
            unknown.sortedBy { it.atMs }.forEach { o ->
                blocks += LedgerRow(categoryIcon(o.category), o.label,
                    listOfNotNull(JourneyStory.clock(o.atMs, z), placeAt(o.atMs)?.let(JourneyStory::short)).joinToString(" · "),
                    "—", if (o.open) "to add" else "unknown", repeatHeader = head, muted = true)
            }
        }
        blocks += Space(6f)
        blocks += TotalRow(if (unknown.isEmpty()) "Total" else "Total recorded", money(m, total),
            listOfNotNull(
                "${x.expenses.size} ${if (x.expenses.size == 1) "entry" else "entries"}",
                perUnit(m, total, distance)?.let { "$it per ${m.distanceUnit}" },
                x.approvedAtMs?.let { "verified ${stamp(it, z)}" }
            ).joinToString(" · "))
        blocks += Space(8f)
        blocks += Footnote("This report is private to ${JourneyStory.firstName(i.who)}. Koode never shares it on its own; places are where the phone was when each expense was logged.")

        return Report(
            fileLabel = "Koode-expenses-${x.tripRef.filter { !it.isWhitespace() }}",
            runningTitle = "${JourneyStory.short(i.origin)} → ${JourneyStory.short(i.destination)} · Travel expenses",
            footer = "Journey ${x.tripRef} · Private · Prepared ${stamp(x.preparedAtMs, z)}",
            blocks = blocks,
            title = "Travel expenses"
        )
    }

    private fun moneyStory(
        x: ExpenseInput, total: Double, byCat: List<Map.Entry<Expenses.Category, Double>>, litres: Double,
        unknown: List<Expenses.Opportunity>
    ): List<String> {
        val m = x.measures; val s = x.story; val i = x.input
        if (x.expenses.isEmpty()) return listOf("No expenses were recorded on this journey.")
        val out = ArrayList<String>()
        out += buildString {
            val top = byCat.first()
            append("${JourneyStory.name(i.who)} spent ${money(m, total)} on this journey")
            perUnit(m, total, i.distanceM)?.let { append(", about $it for every ${m.distanceUnit} travelled") }
            append(". ")
            append("${top.key.label} took the largest share: ${money(m, top.value)}, ${Math.round(top.value / total * 100)}% of the total")
            if (top.key == Expenses.Category.FUEL && litres > 0) {
                append(", for ${num(litres)} ${m.volumeUnit}")
                m.efficiency(i.distanceM, litres)?.let { append(" (about $it)") }
            }
            append(".")
        }
        val rest = byCat.drop(1)
        if (rest.isNotEmpty()) {
            out += buildString {
                append(JourneyStory.listJoin(rest.take(3).map { (c, v) ->
                    val n = x.expenses.count { it.category == c }
                    "${c.label.lowercase(Locale.ENGLISH)} came to ${money(m, v)}" + when (c) {
                        Expenses.Category.FOOD -> mealsNote(s, n)
                        else -> if (n > 1) " over $n entries" else ""
                    }
                }).replaceFirstChar { it.uppercase() })
                append(".")
            }
        }
        if (x.passCrossings > 0) {
            out += if (x.passCrossings >= s.tolls)
                "All ${s.tolls} toll plazas were covered by the FASTag annual pass, so tolls added nothing to this journey."
            else "${x.passCrossings} of the ${s.tolls} toll crossings were covered by the FASTag annual pass."
        }
        if (unknown.isNotEmpty()) {
            out += "${unknown.size} expense${if (unknown.size == 1) " has" else "s have"} no amount yet, so the real total is higher than shown."
        }
        return out
    }

    private fun mealsNote(s: JourneyStory.Story, entries: Int): String {
        val meals = s.meals.filterKeys { it != Nourishment.SNACK && it != Nourishment.TEA_COFFEE }.values.sum()
        return if (meals > 0) " across ${meals} meal${if (meals == 1) "" else "s"}" else if (entries > 1) " over $entries entries" else ""
    }

    fun categoryIcon(c: Expenses.Category): Icon = when (c) {
        Expenses.Category.FUEL -> Icon(Pictures.FUEL)
        Expenses.Category.FOOD -> Icon(Pictures.RESTAURANT)
        Expenses.Category.ACCOMMODATION -> Icon(Pictures.STAY)
        Expenses.Category.TOLL -> Icon(glyph = "toll")
        Expenses.Category.PARKING -> Icon(glyph = "parking")
        Expenses.Category.CAB -> Icon("cab")
        Expenses.Category.AUTO -> Icon("auto")
        Expenses.Category.METRO -> Icon("metro")
        Expenses.Category.BUS -> Icon("bus")
        Expenses.Category.TRAIN -> Icon("train")
        Expenses.Category.FLIGHT -> Icon("flight")
        Expenses.Category.SHIP -> Icon("ship")
        Expenses.Category.VEHICLE_REPAIR -> Icon(glyph = "note")
        Expenses.Category.OTHER -> Icon(glyph = "receipt")
    }

    /** Where the phone was when something was logged: its nearest fix in time, by name. */
    fun placeAt(tMs: Long, i: JourneyStory.Input, book: PlaceBook, story: JourneyStory.Story? = null): String? {
        val near = i.samples.minByOrNull { kotlin.math.abs(it.tMs - tMs) }
        if (near != null && kotlin.math.abs(near.tMs - tMs) <= 30 * 60_000L) book.describe(near.lat, near.lng)?.let { return it }
        // Logged while the phone was out of contact: the stop or halt it was logged at.
        story?.let { st ->
            val window = 60 * 60_000L
            (st.halts.map { Triple(it.atMs, it.endMs, it.place) } + st.stops.map { Triple(it.atMs, it.endMs ?: it.atMs, it.place) })
                .filter { (a, b, p) -> p != null && tMs >= a - window && tMs <= (b ?: Long.MAX_VALUE) + window }
                .minByOrNull { kotlin.math.abs(it.first - tMs) }?.third?.let { return it }
        }
        if (near != null && kotlin.math.abs(near.tMs - tMs) <= 60 * 60_000L) return book.describe(near.lat, near.lng)
        return null
    }

    // ------------------------------------------------------------------------
    // The last known position
    // ------------------------------------------------------------------------

    data class LastKnownInput(
        val story: JourneyStory.Story,
        val input: JourneyStory.Input,
        val book: PlaceBook,
        val tripRef: String,
        val lat: Double?,
        val lng: Double?,
        val accuracyM: Double?,
        val speedKmh: Double?,
        val fixAtMs: Long?,
        val headline: String,
        val detail: String,
        val lastContactMs: Long?,
        val silentMs: Long,
        val batteryPct: Int?,
        val simChangedAtMs: Long?,
        val device: List<Pair<String, String>>,
        val deviceNote: String?,
        val preparedAtMs: Long
    )

    fun lastKnown(l: LastKnownInput): Report {
        val i = l.input; val z = i.zone
        val place = l.book.describe(l.lat, l.lng) ?: l.story.lastPlace
        val who = JourneyStory.name(i.who)
        val blocks = ArrayList<Block>()
        blocks += Hero(
            eyebrow = "Last known position",
            title = place?.let { if (it.startsWith("about ")) it.replaceFirstChar { c -> c.uppercase() } else "Near $it" } ?: "Position not recorded",
            lines = listOfNotNull(
                l.fixAtMs?.let { "Last position from $who's phone at ${JourneyStory.clock(it, z)}, ${JourneyStory.day(it, z)}" },
                "${i.origin} → ${i.destination} · Journey ${l.tripRef}"
            ),
            pill = if (l.silentMs > 60_000) "Silent for ${JourneyStory.duration(l.silentMs / 1000)}" else "Reporting",
            picture = Pictures.mode(i.mode)?.let { Icon(it, mirrored = Pictures.modeFacesLeft(i.mode)) },
            tone = Hero.Tone.ALERT
        )
        blocks += Space(16f)
        blocks += Callout("${l.headline}. ${l.detail}", "sos", Callout.Tone.ALERT)
        blocks += Space(4f)
        blocks += Heading("Where the phone was")
        blocks += Facts(buildList {
            add("Place" to (place ?: "Not known"))
            if (l.lat != null && l.lng != null) {
                add("Coordinates" to "%.6f, %.6f".format(Locale.ENGLISH, l.lat, l.lng))
                l.accuracyM?.let { add("Accurate to within" to "${it.toInt()} m") }
                add("Open in a map" to "maps.google.com/?q=%.6f,%.6f".format(Locale.ENGLISH, l.lat, l.lng))
            }
            l.fixAtMs?.let { add("Recorded at" to "${JourneyStory.clock(it, z)}, ${JourneyStory.day(it, z)}") }
            l.speedKmh?.let { add("Moving at" to if (it < 2) "Not moving" else "${it.toInt()} km/h") }
        })
        if (i.samples.size >= 2) {
            blocks += RouteMap(
                path = i.samples.map { RouteMap.Point(it.tMs, it.lat, it.lng) },
                markers = l.story.stops.filter { it.lat != null && !it.loggedLater && it.items.isNotEmpty() }.map { RouteMap.Marker(it.lat!!, it.lng!!, stopIcon(it)) },
                startLabel = JourneyStory.short(i.origin),
                endLabel = place?.let { "Last known · ${JourneyStory.short(it)}" },
                endIcon = Icon(glyph = "pin"),
                endIsCurrent = true,
                h = 190f
            )
        }
        blocks += Heading("How reporting stopped")
        blocks += Facts(buildList {
            add("Assessment" to l.headline)
            l.lastContactMs?.let { add("Last contact" to "${JourneyStory.clock(it, z)}, ${JourneyStory.day(it, z)}") }
            if (l.silentMs > 60_000) add("Silent for" to JourneyStory.duration(l.silentMs / 1000))
            l.batteryPct?.let { add("Battery at last report" to "$it%") }
            l.simChangedAtMs?.let { add("SIM changed at" to "${JourneyStory.clock(it, z)}, ${JourneyStory.day(it, z)}") }
        })
        val since = (l.lastContactMs ?: i.nowMs) - 12 * 3_600_000L
        val tl = timeline(l.story, i, completed = false, sinceMs = since)
        if (tl.isNotEmpty()) {
            blocks += Space(6f)
            blocks += Heading("The hours before", kicker = "Most recent last")
            blocks += tl
            l.fixAtMs?.let { at ->
                blocks += TimelineNode(
                    JourneyStory.clock(at, z), null, Icon(glyph = "pin"),
                    place?.let { "Last position, ${JourneyStory.near(it)}" } ?: "Last position",
                    listOf(if (l.silentMs > 60_000) "Nothing more from the phone for ${JourneyStory.duration(l.silentMs / 1000)}" else "Still reporting"),
                    railAbove = true, railBelow = false, ring = Ink.RED, strong = true
                )
            }
        }
        if (l.device.isNotEmpty()) {
            blocks += Space(6f)
            blocks += Heading("The phone")
            blocks += Facts(l.device)
            l.deviceNote?.let { blocks += Footnote(it) }
        }
        blocks += Space(6f)
        blocks += Heading("The journey")
        blocks += Facts(listOf(
            "Traveller" to who,
            "From" to i.origin,
            "Heading to" to i.destination,
            "Journey number" to l.tripRef,
            "Started" to "${JourneyStory.clock(i.startedAtMs, z)}, ${JourneyStory.day(i.startedAtMs, z)}",
            "Covered" to JourneyStory.km(i.distanceM)
        ))
        blocks += Footnote("Every time is the phone's local time. Positions come from the phone's satellite and network fixes. This document records facts only; it does not guess at what they mean.")
        return Report(
            fileLabel = "Koode-last-known-position-${l.tripRef.filter { !it.isWhitespace() }}",
            runningTitle = "$who · Last known position",
            footer = "Journey ${l.tripRef} · Prepared ${stamp(l.preparedAtMs, z)}",
            blocks = blocks,
            title = "Last known position"
        )
    }

    // ------------------------------------------------------------------------

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)
    private val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun stamp(ms: Long, z: ZoneId): String = STAMP.withZone(z).format(Instant.ofEpochMilli(ms))

    fun dateRange(start: Long, end: Long?, z: ZoneId): String {
        val a = "${SHORT_DAY.withZone(z).format(Instant.ofEpochMilli(start))}, ${JourneyStory.clock(start, z)}"
        if (end == null) return "$a – now"
        val sameDay = Instant.ofEpochMilli(start).atZone(z).toLocalDate() == Instant.ofEpochMilli(end).atZone(z).toLocalDate()
        return if (sameDay) "$a – ${JourneyStory.clock(end, z)}"
        else "$a – ${SHORT_DAY.withZone(z).format(Instant.ofEpochMilli(end))}, ${JourneyStory.clock(end, z)}"
    }

    /** Whole amounts without ".00": ₹9,780 reads better than ₹9,780.00. */
    fun money(m: Measures, v: Double): String = m.money(v).let { if (it.endsWith(".00")) it.dropLast(3) else it }

    /** "₹30.59": the cost of one km (or mile). */
    fun perUnit(m: Measures, total: Double, metres: Double): String? {
        val d = m.distanceValue(metres)
        return if (d >= 1 && total > 0) m.money(total / d) else null
    }

    private fun num(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(Locale.ENGLISH, v)
}
