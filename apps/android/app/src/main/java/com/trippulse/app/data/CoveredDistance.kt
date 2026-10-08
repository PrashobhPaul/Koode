package com.trippulse.app.data

import com.trippulse.app.data.local.LocationSampleEntity
import com.trippulse.app.data.local.TripLegEntity
import com.trippulse.app.domain.DistanceLedger
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.TransitNetwork
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.TripConfig

/**
 * The distance a journey covered, for anything that reads it back: the live
 * count the phone kept, or more when the stored samples show a stretch the
 * phone was not reporting for (see [DistanceLedger]), or a metro ride the
 * fixes cut short (see [metroRideM]).
 */
fun coveredDistanceM(
    liveM: Double,
    samples: List<LocationSampleEntity>,
    cfg: TripConfig = TripConfig(),
    legs: List<TripLegEntity> = emptyList(),
    transit: TransitNetwork = TransitData.current
): Double {
    if (samples.size < 2) return liveM
    val record = DistanceLedger.reconstruct(
        samples.map { DistanceLedger.Point(it.tMs, it.lat, it.lng, it.speedMps) },
        cfg.restartSpeedKmh, cfg.roadDistanceFactor
    )
    // What the fixes of each finished metro ride fell short of its track.
    val shortM = legs.sumOf { leg ->
        val ride = metroRideM(leg.mode, GeoPoint(leg.fromLat, leg.fromLng), GeoPoint(leg.toLat, leg.toLng), leg.completedAtMs, transit)
            ?: return@sumOf 0.0
        val path = legDistanceM(samples, leg.startedAtMs, leg.completedAtMs) ?: 0.0
        (ride - path).coerceAtLeast(0.0)
    }
    return DistanceLedger.reconcile(liveM, record.copy(totalM = record.totalM + shortM))
}

/**
 * How far the record moved during a stage, or null when the stage has no
 * end yet or too few fixes fell inside it to say.
 */
fun legDistanceM(samples: List<LocationSampleEntity>, startedAtMs: Long?, completedAtMs: Long?): Double? {
    if (startedAtMs == null || completedAtMs == null) return null
    return com.trippulse.app.domain.StageRepair.pathLengthM(samples, { it.tMs }, { it.lat }, { it.lng }, startedAtMs, completedAtMs)
}

/**
 * How far a stage went: along its fixes, and for a metro ride never less
 * than the track between the stations it began and ended at.
 */
fun legDistanceM(
    samples: List<LocationSampleEntity>,
    mode: String, from: GeoPoint, to: GeoPoint,
    startedAtMs: Long?, completedAtMs: Long?,
    transit: TransitNetwork = TransitData.current
): Double? {
    val path = legDistanceM(samples, startedAtMs, completedAtMs)
    val ride = metroRideM(mode, from, to, completedAtMs, transit) ?: return path
    return maxOf(path ?: 0.0, ride)
}

fun legDistanceM(samples: List<LocationSampleEntity>, leg: TripLegEntity, transit: TransitNetwork = TransitData.current): Double? =
    legDistanceM(samples, leg.mode, GeoPoint(leg.fromLat, leg.fromLng), GeoPoint(leg.toLat, leg.toLng), leg.startedAtMs, leg.completedAtMs, transit)

/**
 * A finished metro ride, measured along the track from the station nearest
 * where it began to the one nearest where it ended. Null for anything else,
 * a ride still going (its end is only where it is heading), or a ride whose
 * ends are not at stations Koode knows.
 */
fun metroRideM(mode: String, from: GeoPoint, to: GeoPoint, completedAtMs: Long?, transit: TransitNetwork = TransitData.current): Double? {
    if (completedAtMs == null || transit.isEmpty) return null
    if (TransportCatalog.profile(mode).key != TransportCatalog.METRO.key) return null
    return transit.rideM(from, to)?.takeIf { it > 0 }
}
