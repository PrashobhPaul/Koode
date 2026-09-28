package com.trippulse.app.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.trippulse.app.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Where am I right now?" — answered properly, once.
 *
 * The old approach (cached position, else one low-power attempt, any failure
 * = null) failed silently whenever location was switched off, no fix had been
 * taken recently, or the phone needed GPS. This asks in order of quality and
 * says *why* when it can't answer:
 *  1. a fresh fix (GPS when precise location is allowed), accepting one up to
 *     two minutes old so it is instant when the phone already knows,
 *  2. a network-based fix,
 *  3. the last known position, only if it is recent (never an hours-old one).
 */
object LocationFix {

    sealed interface Result {
        data class Found(val point: GeoPoint, val accuracyM: Float?) : Result
        data object NoPermission : Result
        data object LocationOff : Result
        data object NotFound : Result
    }

    private const val RECENT_MS = 15 * 60_000L

    fun hasPermission(c: Context): Boolean =
        granted(c, Manifest.permission.ACCESS_FINE_LOCATION) || granted(c, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun isLocationOn(c: Context): Boolean =
        c.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(c: Context): Result {
        if (!hasPermission(c)) return Result.NoPermission
        if (!isLocationOn(c)) return Result.LocationOff
        val fused = LocationServices.getFusedLocationProviderClient(c)
        val precise = granted(c, Manifest.permission.ACCESS_FINE_LOCATION)

        suspend fun attempt(priority: Int, durationMs: Long): Location? = withTimeoutOrNull(durationMs + 1_000) {
            runCatching {
                fused.getCurrentLocation(
                    CurrentLocationRequest.Builder()
                        .setPriority(priority)
                        .setMaxUpdateAgeMillis(120_000)
                        .setDurationMillis(durationMs)
                        .build(),
                    CancellationTokenSource().token
                ).await()
            }.getOrNull()
        }

        val fix = attempt(if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000)
            ?: attempt(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000)
            ?: runCatching { fused.lastLocation.await() }.getOrNull()?.takeIf { recent(it) }
            ?: platformLastKnown(c)
        return fix?.let { Result.Found(GeoPoint(it.latitude, it.longitude), if (it.hasAccuracy()) it.accuracy else null) }
            ?: Result.NotFound
    }

    @SuppressLint("MissingPermission")
    private fun platformLastKnown(c: Context): Location? = runCatching {
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.filter { recent(it) }.maxByOrNull { it.time }
    }.getOrNull()

    private fun recent(l: Location) = System.currentTimeMillis() - l.time <= RECENT_MS

    /** A short human name for a point ("Kukatpally, Hyderabad"), using the phone's own geocoder. */
    suspend fun placeName(c: Context, p: GeoPoint): String? = withTimeoutOrNull(3_000) {
        withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(c).getFromLocation(p.lat, p.lng, 1)?.firstOrNull()?.let { a ->
                    listOfNotNull(a.subLocality, a.locality ?: a.subAdminArea).distinct().joinToString(", ").ifBlank { null }
                }
            }.getOrNull()
        }
    }

    /** What to tell the traveller when there's no position — always with a way forward. */
    fun explain(r: Result): String? = when (r) {
        is Result.Found -> null
        Result.NoPermission -> "Koode needs location permission for this. Allow it when asked, or turn it on in Settings → Apps → Koode → Permissions."
        Result.LocationOff -> "Location is switched off on this phone. Turn it on and try again."
        Result.NotFound -> "Couldn't get a position yet. Step near a window or outdoors and try again — or drop a pin on the map."
    }
}
