package com.trippulse.app.ui.map

import com.trippulse.app.domain.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low-poly 3D vehicles, one per transport mode.
 *
 * Each vehicle is a handful of *extruded solids*: a footprint outline plus a
 * base and top height. MapLibre renders those natively as `fill-extrusion`
 * with real perspective and lighting when the camera is tilted — so the car is
 * genuinely 3D, needs no model files, costs nothing, and the browser viewer
 * (`web/map3d.js`) draws the identical shapes from the same numbers.
 *
 * Model space: x runs tail (−0.5) to nose (+0.5), y runs left (−) to right (+),
 * heights share the same unit. Everything is scaled to screen size at placement
 * time, so a car reads as a car at street level and at country level alike.
 *
 * Pure Kotlin with no Android types, so the geometry is unit-tested on the JVM.
 * If you change a model here, change `MODELS` in web/map3d.js to match.
 */
object Vehicle3D {

    /** One solid, in model space. */
    data class Part(
        val outline: List<Pair<Double, Double>>,
        val base: Double,
        val top: Double,
        val color: String
    )

    data class Model(
        val parts: List<Part>,
        /** How long this vehicle reads relative to a car at the same zoom. */
        val displayScale: Double,
        /** Footprint drawn flat on the ground: a shadow, or a wake on water. */
        val ground: List<List<Pair<Double, Double>>>,
        val groundColor: String = "#000000",
        /** Height the model floats at when airborne, in vehicle lengths. */
        val cruiseAltitude: Double = 0.0
    )

    /** A part placed on the earth, ready for a GeoJSON polygon. */
    data class Solid(val ring: List<GeoPoint>, val baseM: Double, val topM: Double, val color: String)

    data class Placed(
        val solids: List<Solid>,
        val ground: List<List<GeoPoint>>,
        val groundColor: String,
        val lengthM: Double
    )

    /** On-screen length of a car, in map pixels. Other modes scale from this. */
    const val CAR_PX = 44.0

    private const val GLASS = "#1E2B38"
    private const val TYRE = "#141414"
    private const val LIGHT = "#FFF3C4"
    private const val TAIL = "#C1121F"
    private const val WHITE = "#F4F6F8"
    private const val METAL = "#C8D2DC"

    // ---- shape builders ---------------------------------------------------

    private fun box(cx: Double, cy: Double, len: Double, wid: Double, base: Double, top: Double, color: String): Part {
        val hx = len / 2; val hy = wid / 2
        return Part(
            listOf(cx + hx to cy - hy, cx + hx to cy + hy, cx - hx to cy + hy, cx - hx to cy - hy),
            base, top, color
        )
    }

    private fun poly(points: List<Pair<Double, Double>>, base: Double, top: Double, color: String) =
        Part(points, base, top, color)

    private fun ngon(cx: Double, cy: Double, r: Double, n: Int, base: Double, top: Double, color: String) =
        Part((0 until n).map { i -> val a = 2 * PI * i / n; cx + r * cos(a) to cy + r * sin(a) }, base, top, color)

    private fun mirrorY(points: List<Pair<Double, Double>>) = points.map { (x, y) -> x to -y }.reversed()

    private fun rect(len: Double, wid: Double) = box(0.0, 0.0, len, wid, 0.0, 0.0, "").outline

    // ---- the fleet ---------------------------------------------------------

    private fun car(paint: String, roofSign: Boolean): Model {
        val parts = mutableListOf(
            box(0.0, 0.0, 1.0, 0.44, 0.05, 0.17, paint),
            box(-0.06, 0.0, 0.52, 0.40, 0.17, 0.29, GLASS),
            box(-0.08, 0.0, 0.40, 0.36, 0.29, 0.31, paint),
            box(0.495, -0.14, 0.012, 0.09, 0.09, 0.13, LIGHT),
            box(0.495, 0.14, 0.012, 0.09, 0.09, 0.13, LIGHT),
            box(-0.495, -0.15, 0.012, 0.09, 0.10, 0.14, TAIL),
            box(-0.495, 0.15, 0.012, 0.09, 0.10, 0.14, TAIL)
        )
        for (x in listOf(0.31, -0.31)) for (y in listOf(0.225, -0.225)) {
            parts += box(x, y, 0.17, 0.05, 0.0, 0.11, TYRE)
        }
        if (roofSign) parts += box(-0.08, 0.0, 0.12, 0.20, 0.31, 0.37, "#1D1D1D")
        return Model(parts, displayScale = 1.0, ground = listOf(rect(1.08, 0.52)))
    }

    private val BIKE = Model(
        listOf(
            box(0.34, 0.0, 0.28, 0.06, 0.0, 0.26, TYRE),
            box(-0.34, 0.0, 0.28, 0.06, 0.0, 0.26, TYRE),
            box(0.05, 0.0, 0.50, 0.14, 0.18, 0.30, "#3E63DD"),
            box(-0.15, 0.0, 0.28, 0.16, 0.28, 0.33, "#222222"),
            box(0.22, 0.0, 0.04, 0.36, 0.34, 0.37, "#222222"),
            box(-0.10, 0.0, 0.20, 0.26, 0.33, 0.62, "#34495E"),
            ngon(-0.06, 0.0, 0.09, 8, 0.62, 0.74, WHITE)
        ),
        displayScale = 0.62,
        ground = listOf(rect(1.0, 0.3))
    )

    private val BUS = run {
        val paint = "#E07A1F"
        val parts = mutableListOf(
            box(0.0, 0.0, 1.0, 0.21, 0.03, 0.15, paint),
            box(0.0, 0.0, 0.97, 0.214, 0.15, 0.23, GLASS),
            box(0.0, 0.0, 1.0, 0.21, 0.23, 0.28, paint),
            box(-0.10, 0.0, 0.25, 0.12, 0.28, 0.30, "#D9DEE3"),
            box(0.502, 0.0, 0.008, 0.19, 0.10, 0.23, GLASS),
            box(0.502, 0.0, 0.008, 0.15, 0.235, 0.27, "#FFB000")
        )
        for (x in listOf(0.33, -0.30)) for (y in listOf(0.108, -0.108)) {
            parts += box(x, y, 0.10, 0.02, 0.0, 0.07, TYRE)
        }
        Model(parts, displayScale = 1.55, ground = listOf(rect(1.04, 0.26)))
    }

    private val TRAIN = run {
        val paint = "#2B4C7E"
        val stripe = "#F2C14E"
        val parts = mutableListOf<Part>()
        // two rails under the whole rake
        parts += box(0.0, 0.028, 1.08, 0.006, 0.0, 0.004, "#6B6B6B")
        parts += box(0.0, -0.028, 1.08, 0.006, 0.0, 0.004, "#6B6B6B")
        for (cx in listOf(0.34, 0.0, -0.34)) {
            parts += box(cx, 0.0, 0.32, 0.075, 0.012, 0.095, paint)
            parts += box(cx, 0.0, 0.322, 0.077, 0.045, 0.060, stripe)
            parts += box(cx, 0.0, 0.30, 0.060, 0.095, 0.105, METAL)
        }
        // tapered locomotive nose and its windscreen
        parts += poly(
            listOf(0.5 to -0.0375, 0.53 to -0.02, 0.54 to 0.0, 0.53 to 0.02, 0.5 to 0.0375),
            0.012, 0.085, paint
        )
        parts += box(0.49, 0.0, 0.02, 0.07, 0.06, 0.085, GLASS)
        Model(parts, displayScale = 2.3, ground = listOf(rect(1.06, 0.1)))
    }

    private val FLIGHT = run {
        val fuselage = listOf(
            0.5 to 0.0, 0.44 to 0.045, 0.30 to 0.055, -0.38 to 0.05, -0.5 to 0.02,
            -0.5 to -0.02, -0.38 to -0.05, 0.30 to -0.055, 0.44 to -0.045
        )
        val rightWing = listOf(0.10 to 0.05, -0.12 to 0.5, -0.20 to 0.5, -0.08 to 0.05)
        val rightTail = listOf(-0.38 to 0.03, -0.48 to 0.19, -0.52 to 0.19, -0.47 to 0.03)
        Model(
            listOf(
                poly(fuselage, 0.0, 0.10, WHITE),
                poly(rightWing, 0.03, 0.05, METAL),
                poly(mirrorY(rightWing), 0.03, 0.05, METAL),
                ngon(0.02, 0.2, 0.035, 8, 0.0, 0.045, "#8A96A3"),
                ngon(0.02, -0.2, 0.035, 8, 0.0, 0.045, "#8A96A3"),
                poly(rightTail, 0.07, 0.085, METAL),
                poly(mirrorY(rightTail), 0.07, 0.085, METAL),
                box(-0.43, 0.0, 0.14, 0.016, 0.10, 0.26, "#2F6FED"),
                box(0.43, 0.0, 0.04, 0.06, 0.07, 0.102, GLASS)
            ),
            displayScale = 1.7,
            ground = listOf(fuselage, rightWing, mirrorY(rightWing)),
            cruiseAltitude = 0.9
        )
    }

    private val SHIP = run {
        val hull = listOf(
            0.5 to 0.0, 0.36 to 0.11, -0.44 to 0.12, -0.5 to 0.09,
            -0.5 to -0.09, -0.44 to -0.12, 0.36 to -0.11
        )
        fun scaled(k: Double) = hull.map { (x, y) -> x * k to y * k }
        Model(
            listOf(
                poly(scaled(1.012), 0.0, 0.02, "#B23A48"),
                poly(hull, 0.02, 0.07, "#1F3B57"),
                poly(scaled(0.95), 0.07, 0.075, "#D9C8A9"),
                box(0.14, 0.0, 0.20, 0.14, 0.075, 0.12, "#2E86AB"),
                box(-0.22, 0.0, 0.34, 0.17, 0.075, 0.17, WHITE),
                box(-0.26, 0.0, 0.20, 0.14, 0.17, 0.24, WHITE),
                box(-0.155, 0.0, 0.012, 0.13, 0.20, 0.225, GLASS),
                ngon(-0.30, 0.0, 0.035, 10, 0.24, 0.31, "#E4572E"),
                ngon(-0.30, 0.0, 0.036, 10, 0.31, 0.335, "#1A1A1A")
            ),
            displayScale = 1.9,
            // A V-shaped wake trailing the stern, drawn flat on the water.
            ground = listOf(listOf(-0.45 to 0.10, -1.4 to 0.42, -1.4 to 0.30, -0.5 to 0.0, -1.4 to -0.30, -1.4 to -0.42, -0.45 to -0.10)),
            groundColor = "#FFFFFF"
        )
    }

    private val CAR = car("#E5484D", roofSign = false)
    private val CAB = car("#F5C518", roofSign = true)

    /** Unknown or future modes fall back to the car, like the transport catalog. */
    fun model(mode: String?): Model = when (mode) {
        "BIKE" -> BIKE
        "CAB" -> CAB
        "BUS" -> BUS
        "TRAIN" -> TRAIN
        "FLIGHT" -> FLIGHT
        "SHIP" -> SHIP
        else -> CAR
    }

    // ---- placement ---------------------------------------------------------

    /**
     * Places [mode]'s model at [center], nose pointing along [bearingDeg]
     * (clockwise from north), sized so a car spans [CAR_PX] screen pixels at
     * the current [metersPerPixel]. [airborne] lifts flights off the ground.
     */
    fun place(
        mode: String?,
        center: GeoPoint,
        bearingDeg: Double,
        metersPerPixel: Double,
        airborne: Boolean = false
    ): Placed {
        val m = model(mode)
        val lengthM = (metersPerPixel * CAR_PX * m.displayScale).coerceIn(3.0, 80_000.0)
        val lift = if (airborne) m.cruiseAltitude * lengthM else 0.0
        val solids = m.parts.map { part ->
            Solid(
                ring = ring(part.outline, center, bearingDeg, lengthM),
                baseM = (part.base * lengthM + lift).coerceAtLeast(0.0),
                topM = (part.top * lengthM + lift).coerceAtLeast(0.1),
                color = part.color
            )
        }
        return Placed(solids, m.ground.map { ring(it, center, bearingDeg, lengthM) }, m.groundColor, lengthM)
    }

    /** Model-space outline -> closed geographic ring. */
    internal fun ring(outline: List<Pair<Double, Double>>, c: GeoPoint, bearingDeg: Double, lengthM: Double): List<GeoPoint> {
        val b = Math.toRadians(bearingDeg)
        val sinB = sin(b); val cosB = cos(b)
        val latScale = 111_320.0
        val lngScale = 111_320.0 * cos(Math.toRadians(c.lat)).coerceAtLeast(0.01)
        val pts = outline.map { (x, y) ->
            val east = (x * sinB + y * cosB) * lengthM
            val north = (x * cosB - y * sinB) * lengthM
            GeoPoint(c.lat + north / latScale, c.lng + east / lngScale)
        }
        return if (pts.isEmpty()) pts else pts + pts.first()
    }

    // ---- small geodesy used by the map ------------------------------------

    /** Initial great-circle bearing from [a] to [b], degrees clockwise from north. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Shortest-way interpolation between two headings, so a U-turn never spins 340°. */
    fun lerpBearing(from: Double, to: Double, t: Double): Double {
        val delta = ((to - from + 540.0) % 360.0) - 180.0
        return (from + delta * t + 360.0) % 360.0
    }

    fun lerp(a: GeoPoint, b: GeoPoint, t: Double) =
        GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)

    /**
     * Great-circle path, [segments] pieces long. Used for the flight arc: the
     * plane's position between fixes is not known, so this line is drawn dashed
     * and labelled as an estimate — never as a live track.
     */
    fun greatCircle(a: GeoPoint, b: GeoPoint, segments: Int = 64): List<GeoPoint> {
        val la1 = Math.toRadians(a.lat); val lo1 = Math.toRadians(a.lng)
        val la2 = Math.toRadians(b.lat); val lo2 = Math.toRadians(b.lng)
        val d = 2 * asin(sqrt(
            sin((la2 - la1) / 2).let { it * it } + cos(la1) * cos(la2) * sin((lo2 - lo1) / 2).let { it * it }
        ))
        if (d < 1e-9) return listOf(a, b)
        return (0..segments).map { i ->
            val f = i.toDouble() / segments
            val sA = sin((1 - f) * d) / sin(d)
            val sB = sin(f * d) / sin(d)
            val x = sA * cos(la1) * cos(lo1) + sB * cos(la2) * cos(lo2)
            val y = sA * cos(la1) * sin(lo1) + sB * cos(la2) * sin(lo2)
            val z = sA * sin(la1) + sB * sin(la2)
            GeoPoint(Math.toDegrees(atan2(z, sqrt(x * x + y * y))), Math.toDegrees(atan2(y, x)))
        }
    }
}
