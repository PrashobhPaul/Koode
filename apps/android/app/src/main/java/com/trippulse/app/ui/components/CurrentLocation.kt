package com.trippulse.app.ui.components

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.trippulse.app.core.LocationFix
import kotlinx.coroutines.launch

/** Hands out the phone's position on request; [locating] is true while it looks. */
class CurrentLocationRequester internal constructor(private val start: () -> Unit, val locating: State<Boolean>) {
    fun request() = start()
}

/**
 * Everything "use my current location" needs, in one place: asks for the
 * permission if missing, offers Android's own "turn on location" dialog if
 * location is off, waits for a real fix, and names the spot. [onResult] gets
 * the outcome and, when found, a short place name.
 */
@Composable
fun rememberCurrentLocation(onResult: (LocationFix.Result, String?) -> Unit): CurrentLocationRequester {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locating = remember { mutableStateOf(false) }
    val callback by rememberUpdatedState(onResult)

    fun fetch() {
        scope.launch {
            locating.value = true
            val r = LocationFix.current(context)
            val name = (r as? LocationFix.Result.Found)?.let { LocationFix.placeName(context, it.point) }
            locating.value = false
            callback(r, name)
        }
    }

    val turnOnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) fetch() else callback(LocationFix.Result.LocationOff, null)
    }

    fun ensureOnThenFetch() {
        if (LocationFix.isLocationOn(context)) { fetch(); return }
        val settings = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000).build())
            .setAlwaysShow(true)
            .build()
        LocationServices.getSettingsClient(context).checkLocationSettings(settings)
            .addOnSuccessListener { fetch() }
            .addOnFailureListener { e ->
                val resolvable = e as? ResolvableApiException
                if (resolvable != null) {
                    runCatching { turnOnLauncher.launch(IntentSenderRequest.Builder(resolvable.resolution).build()) }
                        .onFailure { callback(LocationFix.Result.LocationOff, null) }
                } else callback(LocationFix.Result.LocationOff, null)
            }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) ensureOnThenFetch() else callback(LocationFix.Result.NoPermission, null)
    }

    return remember {
        CurrentLocationRequester({
            if (locating.value) return@CurrentLocationRequester
            if (LocationFix.hasPermission(context)) ensureOnThenFetch()
            else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }, locating)
    }
}
