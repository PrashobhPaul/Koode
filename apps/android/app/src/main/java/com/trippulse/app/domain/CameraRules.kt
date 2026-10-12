package com.trippulse.app.domain

/**
 * How the map rides along with the traveller: the camera's rules, the same
 * in the app and on the web viewer.
 *
 * Tilted and heading-up, so the road runs away from the viewer the way it
 * does through a windscreen. The faster the vehicle, the wider the view and
 * the further ahead the camera looks, so a car at speed shows the road to
 * come while a walker sees the street. Zoom never jumps: it eases toward
 * its target a little each frame, so a traffic light is not a lurch.
 */
object CameraRules {

    const val TILT = 58.0

    /** How close the camera rides, by what is being ridden and how fast. */
    fun zoomFor(speedKmh: Double?, mode: String?): Double {
        val v = speedKmh?.coerceAtLeast(0.0) ?: 0.0
        return when (TransportCatalog.profile(mode).key) {
            "WALK" -> 17.2
            "CYCLE" -> 16.6
            "FLIGHT" -> 9.0
            "SHIP" -> 12.5
            "TRAIN" -> if (v < 60) 15.0 else 13.8
            else -> when {
                v < 12 -> 16.4
                v < 35 -> 15.8
                v < 65 -> 15.2
                v < 100 -> 14.6
                else -> 14.0
            }
        }
    }

    /**
     * How far ahead of the vehicle the camera's centre sits, metres along
     * its heading, so the vehicle rides low in the view and the road ahead
     * fills it: more at speed.
     */
    fun lookAheadM(speedKmh: Double?): Double = (40.0 + (speedKmh?.coerceAtLeast(0.0) ?: 0.0) * 2.2).coerceIn(40.0, 260.0)

    /**
     * How much of the map's height is given to the road ahead: the vehicle
     * rides this far down the view (as a share of the height kept clear
     * above it), lower the faster it goes, so the road to come fills the
     * screen the way a windscreen does.
     */
    fun aheadShare(speedKmh: Double?): Double = (0.28 + (speedKmh?.coerceAtLeast(0.0) ?: 0.0) / 100.0 * 0.14).coerceIn(0.28, 0.42)

    /** [current] eased toward [target] by at most [maxStep]: a zoom that glides. */
    fun eased(current: Double, target: Double, maxStep: Double = 0.02): Double =
        current + (target - current).coerceIn(-maxStep, maxStep)

    /** The speed of a replay at [cursor], from the record's own times, km/h; null when the times are not known. */
    fun replaySpeedKmh(points: List<GeoPoint>, timesMs: List<Long>, cursor: Int): Double? {
        if (timesMs.size != points.size || points.size < 2) return null
        val i = cursor.coerceIn(1, points.lastIndex)
        val dt = timesMs[i] - timesMs[i - 1]
        if (dt <= 0 || timesMs[i] == Long.MAX_VALUE) return null
        return com.trippulse.app.core.Geo.haversineM(points[i - 1], points[i]) / dt * 3_600.0
    }
}
