package com.trippulse.app.ui

import android.annotation.SuppressLint
import android.app.Application
import android.location.Geocoder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.trippulse.app.TripPulseApp
import com.trippulse.app.data.TripManager
import com.trippulse.app.data.ViewerRepository
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.data.local.EventEntity
import com.trippulse.app.data.local.LocationSampleEntity
import com.trippulse.app.data.local.TripStateEntity
import com.trippulse.app.di.AppGraph
import com.trippulse.app.domain.Freshness
import com.trippulse.app.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// ViewModel factory helper
// ---------------------------------------------------------------------------

private fun graphOf(extras: CreationExtras): AppGraph =
    (extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TripPulseApp).graph

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

class HomeVm(private val graph: AppGraph) : ViewModel() {
    val activeTrip: StateFlow<ActiveTripEntity?> =
        graph.tripManager.activeTripFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val cloudAvailable: Boolean = graph.cloudAvailableSafe()

    companion object {
        val Factory = viewModelFactory { initializer { HomeVm(graphOf(this)) } }
    }
}

// ---------------------------------------------------------------------------
// Create trip
// ---------------------------------------------------------------------------

class CreateVm(private val graph: AppGraph) : ViewModel() {

    var busy = MutableStateFlow(false); private set
    var error = MutableStateFlow<String?>(null); private set

    var originText = MutableStateFlow("Current location")
    var destText = MutableStateFlow("")
    var emergencyName = MutableStateFlow("")
    var emergencyPhone = MutableStateFlow("")
    var pickedDest = MutableStateFlow<GeoPoint?>(null)
    var pickedOrigin = MutableStateFlow<GeoPoint?>(null)

    /** Which point a map long-press sets: "DEST" (default) or "START". */
    var pinMode = MutableStateFlow("DEST")
    private var lastDroppedPin: GeoPoint? = null

    val savedPlaces: StateFlow<List<com.trippulse.app.data.local.SavedPlaceEntity>> =
        graph.db.savedPlaceDao().allFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun cloudDefault() = graph.cloudAvailableSafe()

    fun onMapLongPress(p: GeoPoint) {
        lastDroppedPin = p
        if (pinMode.value == "START") {
            pickedOrigin.value = p
            originText.value = "Start pin"
        } else {
            pickedDest.value = p
        }
    }

    fun useAsStart(place: com.trippulse.app.data.local.SavedPlaceEntity) {
        pickedOrigin.value = GeoPoint(place.lat, place.lng)
        originText.value = place.name
    }

    fun useAsDest(place: com.trippulse.app.data.local.SavedPlaceEntity) {
        pickedDest.value = GeoPoint(place.lat, place.lng)
        destText.value = place.name
    }

    /** Saves the last dropped pin — or, with no pin, the phone's current
     *  location (handy for saving "Home" while at home). */
    fun savePlace(name: String) {
        val label = name.trim()
        if (label.isBlank()) { error.value = "Give the place a name first (e.g. Home, Office)."; return }
        viewModelScope.launch {
            val point = lastDroppedPin ?: currentLocation()
            if (point == null) {
                error.value = "Drop a pin on the map (or enable location) to save a place."
                return@launch
            }
            graph.db.savedPlaceDao().upsert(
                com.trippulse.app.data.local.SavedPlaceEntity(label, point.lat, point.lng, System.currentTimeMillis())
            )
            error.value = null
        }
    }

    fun deletePlace(name: String) {
        viewModelScope.launch { graph.db.savedPlaceDao().delete(name) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): GeoPoint? = try {
        val client = LocationServices.getFusedLocationProviderClient(graph.appContext)
        // last known fix first; fall back to requesting a fresh fix, which is
        // what a brand-new phone (or freshly granted permission) needs.
        val loc = client.lastLocation.await()
            ?: client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token
            ).await()
        loc?.let { GeoPoint(it.latitude, it.longitude) }
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private suspend fun geocode(text: String): GeoPoint? = withContext(Dispatchers.IO) {
        try {
            val gc = Geocoder(graph.appContext)
            val res = gc.getFromLocationName(text, 1)
            res?.firstOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
        } catch (_: Exception) { null }
    }

    /** Resolves inputs to coordinates and creates the trip, returning its id. */
    fun create(onDone: (String) -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true; error.value = null
            try {
                val dest = pickedDest.value ?: geocode(destText.value.trim())
                if (dest == null) { error.value = "Could not find the destination. Try a more specific name or long-press the map."; return@launch }

                val origin = pickedOrigin.value
                    ?: if (originText.value.trim().equals("Current location", true)) currentLocation() else geocode(originText.value.trim())
                    ?: currentLocation()
                if (origin == null) {
                    error.value = "Could not get your location — check that GPS is on and location permission is allowed. " +
                        "Or switch the pin to 'Start' and long-press the map, or pick a saved place."
                    return@launch
                }

                val originLabel = originText.value.trim().ifBlank { "Start point" }

                val trip = graph.tripManager.createTrip(
                    TripManager.NewTrip(
                        originName = originLabel, origin = origin,
                        destName = destText.value.trim().ifBlank { "Destination" }, destination = dest,
                        plannedDepartureMs = System.currentTimeMillis(),
                        emergencyName = emergencyName.value.trim().ifBlank { null },
                        emergencyPhone = emergencyPhone.value.trim().ifBlank { null },
                        cloudEnabled = graph.cloudAvailableSafe()
                    )
                )
                onDone(trip.tripId)
            } catch (e: Exception) {
                error.value = e.message ?: "Something went wrong creating the trip."
            } finally {
                busy.value = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { CreateVm(graphOf(this)) } }
    }
}

// ---------------------------------------------------------------------------
// Driver
// ---------------------------------------------------------------------------

class DriverVm(private val graph: AppGraph, val tripId: String) : ViewModel() {

    val trip: StateFlow<ActiveTripEntity?> =
        graph.tripManager.activeTripFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val state: StateFlow<TripStateEntity?> =
        graph.tripManager.stateFlow(tripId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val events: StateFlow<List<EventEntity>> =
        graph.tripManager.eventsFlow(tripId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pending: StateFlow<Int> =
        graph.tripManager.pendingCountFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun submitCheckpoint(c: TripManager.Checkpoint) = viewModelScope.launch { graph.tripManager.submitCheckpoint(c) }
    fun skipCheckpoint() = viewModelScope.launch { graph.tripManager.skipCheckpoint() }
    fun answerOvernight(type: String) = viewModelScope.launch { graph.tripManager.answerOvernight(type) }
    fun addNote(type: String, text: String?) = viewModelScope.launch { graph.tripManager.addQuickNote(type, text) }
    fun activateSos() = viewModelScope.launch { graph.tripManager.activateSos() }
    fun resolveSos() = viewModelScope.launch { graph.tripManager.resolveSos() }
    fun pause() = viewModelScope.launch { graph.tripManager.pause() }
    fun resume() = viewModelScope.launch { graph.tripManager.resume() }
    fun complete() = viewModelScope.launch { graph.tripManager.completeTrip() }
    fun changeDestination(name: String, p: GeoPoint) = viewModelScope.launch { graph.tripManager.changeDestination(name, p) }

    companion object {
        fun factory(tripId: String) = viewModelFactory { initializer { DriverVm(graphOf(this), tripId) } }
    }
}

// ---------------------------------------------------------------------------
// Viewer
// ---------------------------------------------------------------------------

class ViewerVm(private val graph: AppGraph, val accessKey: String) : ViewModel() {

    private val repo: ViewerRepository = graph.viewerRepository
    private val ticker = MutableStateFlow(0L)
    private val serverOffset = MutableStateFlow(0L)

    data class ViewerState(
        val meta: Map<String, Any?>?,
        val state: Map<String, Any?>?,
        val events: List<Map<String, Any?>>,
        val freshness: Freshness
    )

    val ui: StateFlow<ViewerState> =
        combine(
            repo.metaFlow(accessKey),
            repo.currentStateFlow(accessKey),
            repo.eventsFlow(accessKey),
            ticker
        ) { meta, state, events, _ ->
            ViewerState(meta, state, events, repo.freshness(state, serverOffset.value))
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            ViewerState(null, null, emptyList(), Freshness.UNKNOWN)
        )

    init {
        viewModelScope.launch { serverOffset.value = repo.serverOffsetMs() }
        viewModelScope.launch { repo.touch(accessKey) }
        // recompute freshness every 5s even without new data
        viewModelScope.launch {
            while (true) { delay(5000); ticker.value = System.currentTimeMillis() }
        }
    }

    fun leave() = viewModelScope.launch { repo.leave(accessKey) }

    companion object {
        fun factory(accessKey: String) = viewModelFactory { initializer { ViewerVm(graphOf(this), accessKey) } }
    }
}

// ---------------------------------------------------------------------------
// Join
// ---------------------------------------------------------------------------

class JoinVm(private val graph: AppGraph) : ViewModel() {
    val saved = graph.viewerRepository.savedFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var busy = MutableStateFlow(false); private set
    var error = MutableStateFlow<String?>(null); private set

    fun cloudAvailable() = graph.cloudAvailableSafe()

    fun join(tripId: String, secret: String, viewerName: String? = null, onOk: (String) -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true; error.value = null
            when (val r = graph.viewerRepository.join(tripId, secret, viewerName)) {
                is ViewerRepository.JoinResult.Ok -> onOk(r.accessKey)
                ViewerRepository.JoinResult.InvalidOrExpired ->
                    error.value = "That trip id and password don't match an active trip. Check them and try again."
                ViewerRepository.JoinResult.CloudUnavailable ->
                    error.value = "Live viewing needs the cloud connection, which isn't configured on this build yet."
            }
            busy.value = false
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { JoinVm(graphOf(this)) } }
    }
}

// ---------------------------------------------------------------------------
// Replay + Summary
// ---------------------------------------------------------------------------

class ReplayVm(private val graph: AppGraph, val tripId: String) : ViewModel() {
    var samples = MutableStateFlow<List<LocationSampleEntity>>(emptyList()); private set
    var events = MutableStateFlow<List<EventEntity>>(emptyList()); private set
    var loading = MutableStateFlow(true); private set

    init {
        viewModelScope.launch {
            samples.value = graph.db.locationDao().allForTrip(tripId)
            events.value = graph.db.eventDao().allForTrip(tripId)
            loading.value = false
        }
    }

    companion object {
        fun factory(tripId: String) = viewModelFactory { initializer { ReplayVm(graphOf(this), tripId) } }
    }
}

class SummaryVm(private val graph: AppGraph, val tripId: String) : ViewModel() {
    var trip = MutableStateFlow<ActiveTripEntity?>(null); private set
    var events = MutableStateFlow<List<EventEntity>>(emptyList()); private set

    init {
        viewModelScope.launch {
            trip.value = graph.db.tripDao().byId(tripId)
            events.value = graph.db.eventDao().allForTrip(tripId)
        }
    }

    companion object {
        fun factory(tripId: String) = viewModelFactory { initializer { SummaryVm(graphOf(this), tripId) } }
    }
}

/** Null-safe cloud availability that never throws if the backend is unconfigured. */
fun AppGraph.cloudAvailableSafe(): Boolean = try { cloud.isAvailable() } catch (_: Throwable) { false }
