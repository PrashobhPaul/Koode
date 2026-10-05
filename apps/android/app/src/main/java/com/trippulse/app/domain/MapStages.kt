package com.trippulse.app.domain

/**
 * How a multi-mode journey is drawn on the map: which mode carried the
 * traveller at each moment, so a replay shows the cab, then the walker, then
 * the metro, each where it was, and the walked stretches of the trail read
 * differently from the ridden ones.
 */
object MapStages {

    /** From [fromMs] on, the traveller went by [mode] (until the next stage). */
    data class Stage(val fromMs: Long, val mode: String)

    /** A run of consecutive trail points made in one mode: indices [from]..[to], inclusive. */
    data class Run(val from: Int, val to: Int, val mode: String)

    /**
     * Stages from a journey's legs: each started leg from the moment it
     * started. A leg that has not started yet is not on the trail.
     */
    fun of(legs: List<Pair<Long?, String>>): List<Stage> =
        legs.mapNotNull { (startedAtMs, mode) -> startedAtMs?.let { Stage(it, mode) } }.sortedBy { it.fromMs }

    /**
     * The mode at [tMs]: the latest stage begun by then. Before the first
     * stage it is the first stage's mode (the start of the record precedes
     * the leg's own start by moments); with no stages, [fallback].
     */
    fun modeAt(stages: List<Stage>, tMs: Long, fallback: String?): String? {
        if (stages.isEmpty()) return fallback
        return (stages.lastOrNull { it.fromMs <= tMs } ?: stages.first()).mode
    }

    /**
     * The trail cut into runs by mode. Neighbouring runs share their boundary
     * point, so the line stays unbroken where the traveller changed mode.
     * Without matching times, the whole trail is one run in [fallback].
     */
    fun runs(timesMs: List<Long>, stages: List<Stage>, fallback: String?, size: Int = timesMs.size): List<Run> {
        if (size < 2) return emptyList()
        val whole = listOf(Run(0, size - 1, fallback ?: ""))
        if (stages.size < 2 || timesMs.size < size) return whole
        val out = ArrayList<Run>()
        var start = 0
        var mode = modeAt(stages, timesMs[0], fallback)
        for (i in 1 until size) {
            val m = modeAt(stages, timesMs[i], fallback)
            if (m != mode) {
                out += Run(start, i, mode ?: "")
                start = i
                mode = m
            }
        }
        if (start < size - 1 || out.isEmpty()) out += Run(start, size - 1, mode ?: "")
        return out
    }

    /** Walked stretches are drawn as dots, not a road line. */
    fun onFoot(mode: String?): Boolean = TransportCatalog.profile(mode).key == TransportCatalog.WALK.key
}
