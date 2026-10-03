package com.trippulse.app.data

import com.trippulse.app.data.local.LocationSampleEntity
import com.trippulse.app.domain.DistanceLedger
import com.trippulse.app.domain.TripConfig

/**
 * The distance a journey covered, for anything that reads it back: the live
 * count the phone kept, or more when the stored samples show a stretch the
 * phone was not reporting for (see [DistanceLedger]).
 */
fun coveredDistanceM(liveM: Double, samples: List<LocationSampleEntity>, cfg: TripConfig = TripConfig()): Double {
    if (samples.size < 2) return liveM
    val record = DistanceLedger.reconstruct(
        samples.map { DistanceLedger.Point(it.tMs, it.lat, it.lng, it.speedMps) },
        cfg.restartSpeedKmh, cfg.roadDistanceFactor
    )
    return DistanceLedger.reconcile(liveM, record)
}
