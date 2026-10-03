package com.trippulse.app.domain.report

/**
 * The story as it travels: pushed by the traveller's phone inside the live
 * state, read by the follower app and the web viewer. One shape, written and
 * read here only. Small by design (a few paragraphs, a handful of highlights,
 * a few dozen time segments), since it rides along with every state push.
 */
object StoryCodec {

    data class Live(
        val headline: String,
        val status: String,
        val lastPlace: String?,
        val paragraphs: List<String>,
        val highlights: List<JourneyStory.Highlight>,
        val segments: List<JourneyStory.Segment>,
        val tollsMayBeMissing: Boolean,
        val startedAtMs: Long,
        val madeAtMs: Long
    )

    fun encode(story: JourneyStory.Story, input: JourneyStory.Input, madeAtMs: Long): Map<String, Any?> = mapOf(
        "v" to 1,
        "headline" to story.headline,
        "status" to story.status,
        "lastPlace" to story.lastPlace,
        "paragraphs" to story.paragraphs,
        "highlights" to story.highlights.map { h ->
            mapOf("picture" to h.picture, "glyph" to h.glyph, "title" to h.title, "detail" to h.detail)
        },
        // [from, to, phase] triples: compact, and readable by hand in the database.
        "segments" to story.segments.map { listOf(it.fromMs, it.toMs, it.phase.name) },
        "tollsMayBeMissing" to story.tollsMayBeMissing,
        "startedAt" to input.startedAtMs,
        "madeAt" to madeAtMs
    )

    fun decode(raw: Any?): Live? {
        val m = raw as? Map<*, *> ?: return null
        val headline = m["headline"] as? String ?: return null
        @Suppress("UNCHECKED_CAST")
        val paragraphs = (m["paragraphs"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val highlights = (m["highlights"] as? List<*>)?.mapNotNull { h ->
            val hm = h as? Map<*, *> ?: return@mapNotNull null
            JourneyStory.Highlight(hm["picture"] as? String, hm["glyph"] as? String, hm["title"] as? String ?: return@mapNotNull null, hm["detail"] as? String ?: "")
        }.orEmpty()
        val segments = (m["segments"] as? List<*>)?.mapNotNull { s ->
            val l = s as? List<*> ?: return@mapNotNull null
            val a = (l.getOrNull(0) as? Number)?.toLong() ?: return@mapNotNull null
            val b = (l.getOrNull(1) as? Number)?.toLong() ?: return@mapNotNull null
            val p = runCatching { JourneyStory.Phase.valueOf(l.getOrNull(2) as? String ?: "") }.getOrNull() ?: return@mapNotNull null
            JourneyStory.Segment(a, b, p)
        }.orEmpty()
        return Live(
            headline = headline,
            status = m["status"] as? String ?: "",
            lastPlace = m["lastPlace"] as? String,
            paragraphs = paragraphs,
            highlights = highlights,
            segments = segments,
            tollsMayBeMissing = m["tollsMayBeMissing"] == true,
            startedAtMs = (m["startedAt"] as? Number)?.toLong() ?: 0L,
            madeAtMs = (m["madeAt"] as? Number)?.toLong() ?: 0L
        )
    }
}
