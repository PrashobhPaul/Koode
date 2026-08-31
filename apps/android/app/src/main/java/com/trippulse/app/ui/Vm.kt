package com.trippulse.app.ui

import android.annotation.SuppressLint
import android.location.Geocoder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.trippulse.app.TripPulseApp
import com.trippulse.app.core.InputRules
import com.trippulse.app.core.KoodeSettings
import com.trippulse.app.core.LocationCadence
import com.trippulse.app.core.Profile
import com.trippulse.app.core.TripCredentials
import com.trippulse.app.core.ViewerRefresh
import com.trippulse.app.data.TripManager
import com.trippulse.app.data.JourneyAlreadyRunning
import com.trippulse.app.data.ViewerRepository
import com.trippulse.app.data.export.JourneyDocuments
import com.trippulse.app.data.export.JourneyPdf
import com.trippulse.app.data.share.TimelineDelivery
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.data.local.EventEntity
import com.trippulse.app.data.local.ExpenseEntity
import com.trippulse.app.data.local.LocationSampleEntity
import com.trippulse.app.data.local.SavedPlaceEntity
import com.trippulse.app.data.local.TripLegEntity
import com.trippulse.app.data.local.TripStateEntity
import com.trippulse.app.data.local.ViewerTripEntity
import com.trippulse.app.data.routing.PlaceSearch
import com.trippulse.app.data.update.UpdateChecker
import com.trippulse.app.di.AppGraph
import com.trippulse.app.domain.Freshness
import com.trippulse.app.domain.Darkness
import com.trippulse.app.domain.DarkAssessment
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.JourneyAnalytics
import com.trippulse.app.domain.Measures
import com.trippulse.app.domain.Nourishment
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.TravelDetails
import com.trippulse.app.domain.DetailKeys
import com.trippulse.app.domain.UnitPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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

    /** Every journey this device ever created — the traveller's own history. */
    val allTrips: StateFlow<List<ActiveTripEntity>> =
        graph.db.tripDao().allFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Journeys shared with this device (follower side). */
    val following: StateFlow<List<ViewerTripEntity>> =
        graph.viewerRepository.savedFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedPlaceCount: StateFlow<Int> =
        graph.db.savedPlaceDao().allFlow().map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** A newer build, when one exists. Rendered as a dismissible card. */
    val update = MutableStateFlow<UpdateChecker.Available?>(graph.updateChecker.cached())

    val cloudAvailable: Boolean = graph.cloudAvailableSafe()

    init {
        viewModelScope.launch { update.value = graph.updateChecker.check() }
    }

    fun dismissUpdate() {
        update.value?.let { graph.updateChecker.dismiss(it.versionName) }
        update.value = null
    }

    fun greetingName(): String = Profile.name(graph.appContext)

    /**
     * The humanised status of a followed journey, as last evaluated by the
     * follow service. Read from preferences so Home renders instantly with no
     * network call at all.
     */
    data class FollowStatus(
        val level: String,
        val headline: String,
        val reason: String,
        val updatedAtMs: Long?
    )

    fun followStatus(ref: String): FollowStatus {
        val raw = graph.appContext
            .getSharedPreferences(com.trippulse.app.service.TripFollowService.STATUS_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(ref, null) ?: return FollowStatus("NORMAL", "Waiting for the first update…", "", null)
        val p = raw.split("|")
        return FollowStatus(
            p.getOrElse(0) { "NORMAL" },
            p.getOrElse(1) { "Journey progressing normally" },
            p.getOrElse(2) { "" },
            p.getOrNull(3)?.toLongOrNull()
        )
    }

    fun followHealth(ref: String): String = followStatus(ref).level

    /** Erases one journey completely from this device. */
    fun deleteTrip(tripId: String) = viewModelScope.launch {
        with(graph.db) {
            eventDao().deleteForTrip(tripId)
            locationDao().deleteForTrip(tripId)
            stateDao().delete(tripId)
            breakDao().deleteForTrip(tripId)
            expenseDao().deleteForTrip(tripId)
            legDao().deleteForTrip(tripId)
            tripDao().delete(tripId)
        }
    }

    fun unfollow(ref: String) = viewModelScope.launch { graph.viewerRepository.unfollow(ref) }

    /**
     * The invitation text for a journey, ready to hand to any messaging app.
     *
     * Lives here rather than on the credentials screen because the common case
     * is remembering someone mid-journey — "oh, send it to my sister too" —
     * and that should be one tap from the home card, not a hunt back through
     * a screen that was shown once at the start.
     */
    suspend fun shareText(tripId: String, includePasscode: Boolean): String? {
        val t = graph.db.tripDao().byId(tripId) ?: return null
        val name = greetingName()
        return buildString {
            append(if (name.isBlank()) "I'm on a journey" else "$name is on a journey")
            appendLine(" — follow along on Koode.")
            appendLine("You'll know the moment I arrive safely, without having to call.")
            appendLine()
            appendLine("Journey number: ${t.tripId}")
            if (includePasscode) appendLine("Passcode: ${t.secret}")
            appendLine()
            appendLine("Watch in any web browser — nothing to install:")
            appendLine(Links.WEB_VIEWER)
            appendLine()
            appendLine("Or get the Koode app (free):")
            append(Links.APK)
            if (!includePasscode) {
                appendLine()
                appendLine()
                append("Open Koode → People → Follow a journey, enter the number and your name. I'll approve you.")
            }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { HomeVm(graphOf(this)) } }
    }
}

// ---------------------------------------------------------------------------
// Create journey
// ---------------------------------------------------------------------------

/**
 * One stage of the journey being planned.
 *
 * The create screen always holds at least one of these. A single-mode journey
 * is a one-leg list, so there is no "simple mode" and "advanced mode" — adding
 * a second leg is just adding a row.
 */
/**
 * A place the create screen can offer as a tap rather than a typed search.
 *
 * [saved] separates a place someone named on purpose from one merely inferred
 * from where they have been, because the two deserve different prominence and
 * different wording.
 */
data class PlaceSuggestion(
    val name: String,
    val point: GeoPoint,
    val saved: Boolean
) {
    /**
     * Whether this is, for a traveller's purposes, the same spot.
     *
     * Two fixes of the same doorway are never bit-identical, so exact
     * comparison would offer "Home" three times under three names.
     */
    fun isSamePlace(other: GeoPoint): Boolean =
        kotlin.math.abs(point.lat - other.lat) < SAME_PLACE_DEGREES &&
            kotlin.math.abs(point.lng - other.lng) < SAME_PLACE_DEGREES
}

/** Roughly 150 metres — close enough to be the same doorway. */
private const val SAME_PLACE_DEGREES = 0.0015

/** How far back to look for places, and how many to offer. */
private const val RECENT_TRIPS = 12
private const val MAX_SUGGESTIONS = 10

/** Labels a journey gets when nobody named its ends; never worth suggesting. */
private val PLACEHOLDER_NAMES = setOf(
    "Start point", "Destination", "Pinned start", "Pinned destination", "En route"
)

data class LegDraft(
    val mode: String = "CAR",
    val fromText: String = "",
    val from: GeoPoint? = null,
    val toText: String = "",
    val to: GeoPoint? = null,
    val boardingPoint: String = "",
    /**
     * Vehicle and booking details, keyed by [com.trippulse.app.domain.DetailKeys]
     * and rendered from [com.trippulse.app.domain.TravelDetails]. Fuel type
     * lives in here too, so there is one answer to "what do we know about this
     * vehicle" rather than one field plus a map.
     */
    val details: Map<String, String> = emptyMap()
) {
    val profile get() = TransportCatalog.profile(mode)
    val fuelType: String? get() = details[DetailKeys.FUEL_TYPE]
    /** A private vehicle cannot start a journey half-described. */
    val ready: Boolean get() = TravelDetails.isComplete(mode, details)
}

class CreateVm(private val graph: AppGraph) : ViewModel() {

    init { loadSuggestions() }

    var busy = MutableStateFlow(false); private set

    /** Set when creation was refused because a journey is already running. */
    var runningTripId = MutableStateFlow<String?>(null); private set
    var error = MutableStateFlow<String?>(null); private set

    /** The legs of this journey, in order. Starts as one. */
    val legs = MutableStateFlow(listOf(LegDraft(fromText = "Current location")))

    /** Which leg the editor is focused on. */
    val editingLeg = MutableStateFlow(0)

    var emergencyName = MutableStateFlow(Profile.contact(graph.appContext, 1).name)
    var emergencyPhone = MutableStateFlow(Profile.contact(graph.appContext, 1).phone)

    var myName = MutableStateFlow(Profile.name(graph.appContext))

    /** Six digits the traveller chooses; pre-filled with a random suggestion. */
    val passcode = MutableStateFlow(TripCredentials.newPasscode())

    /** Scheduled departure (epoch ms); null = leaving now. */
    var departureMs = MutableStateFlow<Long?>(null)

    /** Which point a map long-press sets on the leg being edited. */
    var pinMode = MutableStateFlow("DEST")
    private var lastDroppedPin: GeoPoint? = null

    private val placeSearch = PlaceSearch()
    var searchResults = MutableStateFlow<List<PlaceSearch.Place>>(emptyList()); private set
    var searching = MutableStateFlow(false); private set

    val savedPlaces: StateFlow<List<SavedPlaceEntity>> =
        graph.db.savedPlaceDao().allFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Places worth offering without being asked: the ones deliberately saved,
     * then the ones recently travelled between.
     *
     * Almost nobody's next journey starts somewhere they have never been. The
     * hard part of the create screen was always typing a place name and hoping
     * the search agreed with you, and most of the time the answer was already
     * in the history -- so it is offered as a tap instead.
     *
     * Saved places come first because they were named on purpose, and a
     * recently-travelled point that is already a saved place is dropped rather
     * than shown twice under two names.
     */
    val suggestedPlaces = MutableStateFlow<List<PlaceSuggestion>>(emptyList())

    private fun loadSuggestions() = viewModelScope.launch {
        val saved = runCatching { graph.db.savedPlaceDao().all() }.getOrNull().orEmpty()
        val out = ArrayList<PlaceSuggestion>()
        saved.forEach { out.add(PlaceSuggestion(it.name, GeoPoint(it.lat, it.lng), saved = true)) }

        val trips = runCatching { graph.db.tripDao().recent(RECENT_TRIPS) }.getOrNull().orEmpty()
        for (t in trips) {
            for ((name, point) in listOf(
                t.originName to GeoPoint(t.originLat, t.originLng),
                t.destName to GeoPoint(t.destLat, t.destLng)
            )) {
                if (name.isBlank()) continue
                // Placeholder labels from a journey that never got a real name
                // are worse than no suggestion at all.
                if (name in PLACEHOLDER_NAMES) continue
                if (out.any { it.isSamePlace(point) }) continue
                out.add(PlaceSuggestion(name, point, saved = false))
                if (out.size >= MAX_SUGGESTIONS) break
            }
            if (out.size >= MAX_SUGGESTIONS) break
        }
        suggestedPlaces.value = out
    }

    fun useSuggestion(index: Int, place: PlaceSuggestion, asStart: Boolean) {
        if (asStart) updateLeg(index) { it.copy(from = place.point, fromText = place.name) }
        else updateLeg(index) { it.copy(to = place.point, toText = place.name) }
    }

    // ---- leg editing ------------------------------------------------------

    private fun updateLeg(index: Int, transform: (LegDraft) -> LegDraft) {
        legs.value = legs.value.mapIndexed { i, leg -> if (i == index) transform(leg) else leg }
    }

    fun editLeg(index: Int) { editingLeg.value = index.coerceIn(0, legs.value.lastIndex) }

    fun setMode(index: Int, mode: String) = updateLeg(index) { leg ->
        // The questions change with the mode, so the answers cannot carry
        // over: a coach number means nothing in a car, and a fuel type means
        // nothing on a train. Keeping them would leave stale details attached
        // to a vehicle that never had them.
        if (mode == leg.mode) leg else leg.copy(mode = mode, details = emptyMap())
    }

    fun setDetail(index: Int, key: String, value: String) =
        updateLeg(index) { it.copy(details = it.details + (key to value)) }
    fun setFromText(index: Int, text: String) = updateLeg(index) { it.copy(fromText = text) }
    fun setToText(index: Int, text: String) = updateLeg(index) { it.copy(toText = text) }
    fun setBoardingPoint(index: Int, text: String) = updateLeg(index) { it.copy(boardingPoint = text) }

    fun setPasscode(raw: String) { passcode.value = InputRules.digits(raw, TripCredentials.PASSCODE_LENGTH) }
    fun regeneratePasscode() { passcode.value = TripCredentials.newPasscode() }

    /**
     * Adds the next stage of a hybrid journey. It starts where the previous leg
     * ends, because that is always true and asking again would be busywork.
     */
    fun addLeg() {
        val previous = legs.value.last()
        legs.value = legs.value + LegDraft(
            mode = if (previous.mode == "TRAIN") "CAB" else "CAR",
            fromText = previous.toText,
            from = previous.to
        )
        editingLeg.value = legs.value.lastIndex
    }

    fun removeLeg(index: Int) {
        if (legs.value.size <= 1) return
        legs.value = legs.value.filterIndexed { i, _ -> i != index }
        editingLeg.value = editingLeg.value.coerceAtMost(legs.value.lastIndex)
    }

    // ---- place lookup -----------------------------------------------------

    fun searchPlaces(query: String) {
        if (query.trim().length < 2 || searching.value) return
        viewModelScope.launch {
            searching.value = true
            searchResults.value = placeSearch.search(query)
            searching.value = false
            error.value = if (searchResults.value.isEmpty())
                "No places found for \"${query.trim()}\" — try adding the city or district." else null
        }
    }

    fun clearSearch() { searchResults.value = emptyList() }

    private fun shortName(displayName: String): String =
        displayName.split(",").take(2).joinToString(",").trim().ifBlank { displayName }

    fun useSearchResult(place: PlaceSearch.Place, asStart: Boolean) {
        val index = editingLeg.value
        if (asStart) updateLeg(index) { it.copy(from = place.point, fromText = shortName(place.name)) }
        else updateLeg(index) { it.copy(to = place.point, toText = shortName(place.name)) }
        searchResults.value = emptyList()
    }

    fun onMapLongPress(p: GeoPoint) {
        lastDroppedPin = p
        val index = editingLeg.value
        if (pinMode.value == "START") updateLeg(index) { it.copy(from = p, fromText = "Pinned start") }
        else updateLeg(index) { it.copy(to = p, toText = it.toText.ifBlank { "Pinned destination" }) }
    }

    fun useAsStart(place: SavedPlaceEntity) =
        updateLeg(editingLeg.value) { it.copy(from = GeoPoint(place.lat, place.lng), fromText = place.name) }

    fun useAsDest(place: SavedPlaceEntity) =
        updateLeg(editingLeg.value) { it.copy(to = GeoPoint(place.lat, place.lng), toText = place.name) }

    fun savePlace(name: String) {
        val label = InputRules.itemTextForStorage(name)
        if (label.isBlank()) { error.value = "Give the place a name first (e.g. Home, Office)."; return }
        viewModelScope.launch {
            val point = lastDroppedPin ?: currentLocation()
            if (point == null) {
                error.value = "Drop a pin on the map (or enable location) to save a place."
                return@launch
            }
            graph.db.savedPlaceDao().upsert(SavedPlaceEntity(label, point.lat, point.lng, System.currentTimeMillis()))
            error.value = null
        }
    }

    fun deletePlace(name: String) = viewModelScope.launch { graph.db.savedPlaceDao().delete(name) }

    fun cloudDefault() = graph.cloudAvailableSafe()

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): GeoPoint? = try {
        val client = LocationServices.getFusedLocationProviderClient(graph.appContext)
        val loc = client.lastLocation.await()
            ?: client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token
            ).await()
        loc?.let { GeoPoint(it.latitude, it.longitude) }
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private suspend fun geocode(text: String): GeoPoint? = withContext(Dispatchers.IO) {
        try {
            Geocoder(graph.appContext).getFromLocationName(text, 1)
                ?.firstOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
        } catch (_: Exception) { null }
    }

    /** Resolves every leg to coordinates and creates the journey. */
    fun create(onDone: (String) -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true; error.value = null
            try {
                if (!TripCredentials.isCompletePasscode(passcode.value)) {
                    error.value = "Choose a ${TripCredentials.PASSCODE_LENGTH}-digit passcode — it's what your family types to follow you."
                    return@launch
                }
                Profile.setName(graph.appContext, myName.value)

                val resolved = ArrayList<TripManager.NewLeg>()
                for ((index, leg) in legs.value.withIndex()) {
                    val to = leg.to ?: geocode(leg.toText.trim())
                    if (to == null) {
                        error.value = "Couldn't find \"${leg.toText.trim().ifBlank { "the destination" }}\"" +
                            (if (legs.value.size > 1) " on leg ${index + 1}" else "") +
                            ". Try a more specific name, or long-press the map."
                        return@launch
                    }
                    // Where this leg starts, in order of confidence: an
                    // explicit pin, the end of the previous leg, the typed
                    // place name, and finally the phone's own position.
                    val typedFrom = leg.fromText.trim()
                    val from: GeoPoint? = leg.from
                        ?: resolved.lastOrNull()?.to
                        ?: if (typedFrom.isBlank() || typedFrom.equals("Current location", true)) {
                            currentLocation()
                        } else {
                            geocode(typedFrom) ?: currentLocation()
                        }
                    if (from == null) {
                        error.value = "Couldn't work out where you're starting from. Turn on location, " +
                            "pick a saved place, or switch the pin to 'Start' and long-press the map."
                        return@launch
                    }
                    // In your own vehicle these details are the safety
                    // information, so the journey does not start without them.
                    val missing = TravelDetails.missingRequired(leg.mode, leg.details)
                    if (missing.isNotEmpty()) {
                        error.value = "Stage ${index + 1} still needs: " +
                            missing.joinToString(", ") { it.label } + "."
                        return@launch
                    }
                    resolved.add(
                        TripManager.NewLeg(
                            mode = leg.mode,
                            fromName = leg.fromText.trim().ifBlank { "Start point" }, from = from,
                            toName = leg.toText.trim().ifBlank { "Destination" }, to = to,
                            fuelType = leg.fuelType,
                            plannedDepartureMs = if (index == 0) departureMs.value else null,
                            boardingPoint = leg.boardingPoint.trim().ifBlank { null },
                            details = leg.details
                        )
                    )
                }

                val departure = departureMs.value ?: System.currentTimeMillis()
                val trip = graph.tripManager.createTrip(
                    TripManager.NewTrip(
                        legs = resolved,
                        plannedDepartureMs = departure,
                        emergencyName = emergencyName.value.trim().ifBlank { null },
                        emergencyPhone = emergencyPhone.value.trim().ifBlank { null },
                        cloudEnabled = graph.cloudAvailableSafe(),
                        passcode = passcode.value
                    )
                )

                if (departure > System.currentTimeMillis() + 35 * 60_000L) {
                    com.trippulse.app.service.DepartureReminder.schedule(
                        graph.appContext, trip.tripId, trip.destName, departure
                    )
                }
                onDone(trip.tripId)
            } catch (e: JourneyAlreadyRunning) {
                // Not really an error on their part: they almost certainly
                // meant to open the one they are on, so say which it is.
                runningTripId.value = e.tripId
                error.value = "You're already on a journey to ${e.destination}. " +
                    "Finish that one first — a second live journey would leave " +
                    "everyone following you with two different answers about where you are."
            } catch (e: Exception) {
                error.value = e.message ?: "Something went wrong creating the journey."
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
// Traveller (journey in progress)
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

    val legs: StateFlow<List<TripLegEntity>> =
        graph.tripManager.legsFlow(tripId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expenses: StateFlow<List<ExpenseEntity>> =
        graph.db.expenseDao().flowForTrip(tripId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pending: StateFlow<Int> =
        graph.tripManager.pendingCountFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** The path recorded so far, for the map. Refreshed on a gentle cadence. */
    val breadcrumb = MutableStateFlow<List<LocationSampleEntity>>(emptyList())

    /** Followers asking to join with only the journey id. */
    var joinRequests = MutableStateFlow<List<Map<String, Any?>>>(emptyList()); private set

    init {
        viewModelScope.launch {
            while (true) {
                breadcrumb.value = graph.db.locationDao().allForTrip(tripId)
                delay(30_000)
            }
        }
        viewModelScope.launch {
            while (true) {
                val t = graph.db.tripDao().byId(tripId)
                if (t?.cloudEnabled == true) {
                    var reqs = try { graph.cloud.fetchJoinRequests(t.accessKey) } catch (_: Exception) { emptyList() }
                    // Emergency contacts ARE the traveller's circle: someone
                    // joining under a contact's name is approved automatically,
                    // so the journey is effectively shared with them by default.
                    val autoApproved = reqs.filter {
                        it["status"] == "PENDING" &&
                            Profile.isCircleName(graph.appContext, it["name"] as? String ?: "")
                    }
                    if (autoApproved.isNotEmpty()) {
                        autoApproved.forEach { r ->
                            (r["token"] as? String)?.let { graph.cloud.setViewerStatus(t.accessKey, it, true) }
                        }
                        reqs = try { graph.cloud.fetchJoinRequests(t.accessKey) } catch (_: Exception) { reqs }
                    }
                    joinRequests.value = reqs
                }
                delay(20_000)
            }
        }
    }

    fun setViewerApproval(viewerToken: String, approve: Boolean) = viewModelScope.launch {
        val t = graph.db.tripDao().byId(tripId) ?: return@launch
        graph.cloud.setViewerStatus(t.accessKey, viewerToken, approve)
        joinRequests.value = try { graph.cloud.fetchJoinRequests(t.accessKey) } catch (_: Exception) { joinRequests.value }
    }

    /**
     * Records an expense. The item is text and the amount is a number — the
     * split is enforced here as well as in the field, so no caller can put a
     * price in the description column.
     */
    fun addExpense(type: String, item: String, amount: Double, quantity: Double?, unit: String?, note: String?) =
        viewModelScope.launch {
            graph.db.expenseDao().insert(
                ExpenseEntity(
                    tripId = tripId, type = type, amount = amount,
                    quantity = quantity, unit = unit, note = note?.ifBlank { null },
                    tMs = System.currentTimeMillis(), item = InputRules.itemTextForStorage(item)
                )
            )
        }

    fun deleteExpense(id: Long) = viewModelScope.launch { graph.db.expenseDao().delete(id) }

    fun submitCheckpoint(c: TripManager.Checkpoint) = viewModelScope.launch { graph.tripManager.submitCheckpoint(c) }

    /** One-tap wellbeing log (water, tea, a meal the app will name for you). */
    fun logNourishment(kind: Nourishment) = viewModelScope.launch { graph.tripManager.logNourishment(kind) }

    /** Break log with a refuel: the checkpoint and the fuel cost in one gesture. */
    fun submitCheckpointWithRefuel(c: TripManager.Checkpoint, amount: Double, quantity: Double?, unit: String) =
        viewModelScope.launch {
            graph.tripManager.submitCheckpoint(c)
            graph.db.expenseDao().insert(
                ExpenseEntity(
                    tripId = tripId, type = "FUEL", amount = amount,
                    quantity = quantity, unit = unit, note = null,
                    tMs = System.currentTimeMillis(),
                    item = if (unit == "kWh") "Charging" else "Fuel"
                )
            )
        }

    fun skipCheckpoint() = viewModelScope.launch { graph.tripManager.skipCheckpoint() }
    fun answerOvernight(type: String) = viewModelScope.launch { graph.tripManager.answerOvernight(type) }
    fun addNote(type: String, text: String?) = viewModelScope.launch { graph.tripManager.addQuickNote(type, text) }
    fun activateSos() = viewModelScope.launch { graph.tripManager.activateSos() }
    fun resolveSos() = viewModelScope.launch { graph.tripManager.resolveSos() }
    fun pause() = viewModelScope.launch { graph.tripManager.pause() }
    fun resume() = viewModelScope.launch { graph.tripManager.resume() }
    fun dismissArrivalPrompt() = viewModelScope.launch { graph.tripManager.dismissArrivalPrompt() }
    fun nextLeg() = viewModelScope.launch { graph.tripManager.advanceToNextLeg() }

    // ---- how this journey is measured and priced ----

    val measures: Measures get() = graph.measures()

    // ---- editing a journey that is already under way -----------------------

    var editMessage = MutableStateFlow<String?>(null); private set
    var editBusy = MutableStateFlow(false); private set

    fun clearEditMessage() { editMessage.value = null }

    /**
     * The traveller has changed vehicles.
     *
     * Everything this needs it already has: where they are comes from the last
     * fix, where they are going has never changed. So it asks for the new mode
     * and its details and nothing else -- no destination field, no "where are
     * you now", because both of those are questions the app should be
     * embarrassed to ask someone standing on a platform.
     */
    fun switchMode(mode: String, details: Map<String, String>, breakdown: Boolean = false) =
        viewModelScope.launch {
            if (editBusy.value) return@launch
            editBusy.value = true
            try {
                editMessage.value = when (val r = graph.tripManager.switchMode(mode, details, breakdown)) {
                    is TripManager.SwitchResult.Ok ->
                        "Updated — everyone following you can see it."
                    is TripManager.SwitchResult.NotEditable ->
                        "This journey is closed and can no longer be changed."
                    is TripManager.SwitchResult.NoLocationYet ->
                        "Waiting for a location fix — one moment, then try again."
                    is TripManager.SwitchResult.PrivateVehicleNeedsBreakdown ->
                        "Changing out of your own vehicle mid-journey is for a breakdown. " +
                            "Tick that if the car has let you down."
                    is TripManager.SwitchResult.MissingDetails ->
                        "Still needed: ${r.labels.joinToString(", ")}."
                }
            } finally {
                editBusy.value = false
            }
        }

    /** Fills in details the traveller only learned after boarding. */
    fun updateStageDetails(legIndex: Int, details: Map<String, String>) = viewModelScope.launch {
        if (editBusy.value) return@launch
        editBusy.value = true
        try {
            val ok = graph.tripManager.updateLegDetails(legIndex, details)
            editMessage.value =
                if (ok) "Saved." else "That stage is finished and can no longer be changed."
        } finally {
            editBusy.value = false
        }
    }

    /**
     * The analysed picture of the journey so far, for the review shown before
     * closure. Recomputed on demand: the traveller is about to publish these
     * numbers to everyone watching, so they must be current.
     */
    suspend fun buildReport(): JourneyAnalytics.JourneyReport? {
        val t = graph.db.tripDao().byId(tripId) ?: return null
        val st = graph.db.stateDao().byId(tripId)
        val events = graph.db.eventDao().allForTrip(tripId).map { com.trippulse.app.data.EventCodec.toDomain(it) }
        val samples = graph.db.locationDao().allForTrip(tripId)
        val expense = graph.db.expenseDao().allForTrip(tripId).map {
            JourneyAnalytics.ExpenseInput(it.type, it.item, it.amount, it.quantity, it.unit, it.tMs)
        }
        val legRows = graph.db.legDao().forTrip(tripId).map {
            JourneyAnalytics.LegInput(it.legIndex, it.mode, it.fromName, it.toName, it.startedAtMs, it.completedAtMs)
        }
        return JourneyAnalytics.analyse(
            JourneyAnalytics.Inputs(
                events = events,
                distanceCoveredM = st?.distanceCoveredM ?: 0.0,
                startedAtMs = t.startedAtMs ?: t.createdAtMs,
                endedAtMs = System.currentTimeMillis(),
                expenses = expense,
                legs = legRows,
                transportMode = t.transportMode,
                topSpeedKmh = samples.mapNotNull { it.speedMps }.maxOrNull()?.times(3.6)
            )
        )
    }

    // ---- sending the timeline to the circle -------------------------------

    /** People the finished timeline can be sent to, and the file to send. */
    val sendRecipients = MutableStateFlow<List<TimelineDelivery.Recipient>>(emptyList())
    val timelinePdf = MutableStateFlow<java.io.File?>(null)
    val sendMessage = MutableStateFlow("")

    val whatsAppEnabled: Boolean get() = graph.settings.current.shareTimelineOnWhatsApp
    val whatsAppAvailable: Boolean get() = TimelineDelivery.isAvailable(graph.appContext)

    /**
     * Ends the journey after the traveller has reviewed it.
     *
     * [closingNote] is appended to the timeline first, so the document everyone
     * receives is the one that was just verified — and because nothing can be
     * edited after completion, this is the traveller's last chance to add it.
     *
     * When timeline sharing is on, the PDF is built immediately afterwards so
     * that "as soon as I mark it complete" means exactly that: by the time the
     * send sheet appears, the document already exists.
     */
    fun complete(closingNote: String? = null, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        graph.tripManager.completeTrip(closingNote)
        val prepared = if (whatsAppEnabled) prepareTimelineForSending() else false
        onDone(prepared)
    }

    /** Builds the timeline PDF and resolves who it can go to. */
    private suspend fun prepareTimelineForSending(): Boolean {
        val recipients = TimelineDelivery.recipients(graph.appContext)
        val t = graph.db.tripDao().byId(tripId) ?: return false
        val r = buildReport() ?: return false
        val ev = graph.db.eventDao().allForTrip(tripId)
        val doc = JourneyDocuments.timeline(t, ev, r, graph.measures())
        val file = runCatching { JourneyPdf.write(graph.appContext, doc) }.getOrNull() ?: return false

        sendRecipients.value = recipients
        timelinePdf.value = file
        sendMessage.value = TimelineDelivery.buildMessage(
            travellerName = Profile.name(graph.appContext).ifBlank { null },
            origin = t.originName,
            destination = t.destName
        )
        return recipients.isNotEmpty()
    }

    /** WhatsApp, pre-addressed to one person. Null when WhatsApp isn't installed. */
    fun sendIntentFor(recipient: TimelineDelivery.Recipient): android.content.Intent? {
        val pdf = timelinePdf.value ?: return null
        return TimelineDelivery.intentFor(graph.appContext, recipient, pdf, sendMessage.value)
    }

    /** The ordinary share sheet, when WhatsApp isn't an option. */
    fun fallbackSendIntent(): android.content.Intent? {
        val pdf = timelinePdf.value ?: return null
        return TimelineDelivery.fallbackIntent(graph.appContext, pdf, sendMessage.value)
    }

    companion object {
        fun factory(tripId: String) = viewModelFactory { initializer { DriverVm(graphOf(this), tripId) } }
    }
}

// ---------------------------------------------------------------------------
// Follower
// ---------------------------------------------------------------------------

class ViewerVm(private val graph: AppGraph, val accessKey: String) : ViewModel() {

    private val repo: ViewerRepository = graph.viewerRepository
    private val ticker = MutableStateFlow(0L)
    private val serverOffset = MutableStateFlow(0L)

    data class ViewerState(
        val meta: Map<String, Any?>?,
        val state: Map<String, Any?>?,
        val events: List<Map<String, Any?>>,
        val freshness: Freshness,
        /** True only because the traveller ended the journey. */
        val endedByOwner: Boolean,
        /** We have never managed to read this journey yet. */
        val awaitingFirstRead: Boolean
    )

    val ui: StateFlow<ViewerState> =
        combine(
            repo.metaFlow(accessKey),
            repo.currentStateFlow(accessKey),
            repo.eventsFlow(accessKey),
            ticker
        ) { meta, state, events, _ ->
            ViewerState(
                meta = meta,
                state = state,
                events = events,
                freshness = repo.freshness(state, serverOffset.value),
                endedByOwner = repo.isEndedByOwner(state, events),
                awaitingFirstRead = meta == null && state == null
            )
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            ViewerState(null, null, emptyList(), Freshness.UNKNOWN, false, true)
        )

    /**
     * What this device believes about the traveller's phone going quiet.
     *
     * Computed here, on the follower's phone, from the last state that reached
     * the server. That is the whole architecture in one line: the assessment
     * cannot be stopped by whatever happened to the phone it is about.
     */
    val darkness: StateFlow<DarkAssessment> = ui.map { s ->
        val st = s.state
        fun ln(k: String): Long? = (st?.get(k) as? Number)?.toLong()
        val mode = s.meta?.get("transportMode") as? String
        val plannedDep = (s.meta?.get("plannedDeparture") as? Number)?.toLong()
        val now = System.currentTimeMillis()
        Darkness.assess(
            Darkness.Inputs(
                nowMs = now,
                lastUpdateMs = ln("lastLocationAt") ?: ln("updatedAt"),
                lastBatteryPct = ln("battery")?.toInt(),
                shutdownAtMs = ln("wentDarkAt"),
                shutdownBatteryPct = ln("battery")?.toInt(),
                simChangedAtMs = ln("simChangedAt"),
                deviationActive = st?.get("deviationActive") as? Boolean ?: false,
                offlineExpected = mode == "FLIGHT" && plannedDep != null &&
                    now >= plannedDep - 30 * 60_000L && now <= plannedDep + 9 * 3_600_000L,
                journeyClosed = s.endedByOwner
            )
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000),
        Darkness.assess(Darkness.Inputs(nowMs = 0, lastUpdateMs = null, lastBatteryPct = null))
    )

    /** Set once a last-known-position report has been written. */
    val lastKnownReport = MutableStateFlow<java.io.File?>(null)
    var reportBusy = MutableStateFlow(false); private set

    /**
     * Writes the report a family would take to a police station.
     *
     * Built from what this device has already received, so it works when the
     * traveller's phone is unreachable -- which is the only situation in which
     * anybody wants it.
     */
    fun buildLastKnownReport(onReady: (java.io.File?) -> Unit) = viewModelScope.launch {
        if (reportBusy.value) return@launch
        reportBusy.value = true
        try {
            val s = ui.value
            val st = s.state
            fun ln(k: String): Long? = (st?.get(k) as? Number)?.toLong()
            fun dn(k: String): Double? = (st?.get(k) as? Number)?.toDouble()
            val label = s.meta?.get("label") as? String
            @Suppress("UNCHECKED_CAST")
            val device = (s.meta?.get("device") as? Map<String, Any?>).orEmpty()
            val doc = JourneyDocuments.lastKnownPosition(
                JourneyDocuments.LastKnown(
                    tripId = s.meta?.get("tripId") as? String ?: accessKey.take(8),
                    originName = s.meta?.get("origin") as? String ?: "Start",
                    destName = s.meta?.get("destination") as? String ?: "Destination",
                    startedAtMs = (s.meta?.get("startedAt") as? Number)?.toLong()
                        ?: System.currentTimeMillis(),
                    travellerName = label,
                    lat = dn("lat"), lng = dn("lng"),
                    accuracyM = dn("accuracy"), speedKmh = dn("speedKmh"),
                    fixAtMs = ln("lastLocationAt"),
                    simChangedAtMs = ln("simChangedAt"),
                    assessment = darkness.value,
                    device = device,
                    events = JourneyDocuments.momentsFromCloud(s.events)
                )
            )
            val file = runCatching { JourneyPdf.write(graph.appContext, doc) }.getOrNull()
            lastKnownReport.value = file
            onReady(file)
        } finally {
            reportBusy.value = false
        }
    }

    /** The path the traveller has taken, rebuilt from the shared timeline. */
    val breadcrumb: StateFlow<List<GeoPoint>> = ui.map { s ->
        s.events.mapNotNull { e ->
            val lat = (e["lat"] as? Number)?.toDouble()
            val lng = (e["lng"] as? Number)?.toDouble()
            if (lat != null && lng != null) GeoPoint(lat, lng) else null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { serverOffset.value = repo.serverOffsetMs() }
        viewModelScope.launch { repo.touch(accessKey) }
        // Recompute freshness on a gentle beat even when no new data arrives.
        viewModelScope.launch {
            while (true) { delay(15_000); ticker.value = System.currentTimeMillis() }
        }
        // Persist the two facts Home needs to render honestly.
        viewModelScope.launch {
            ui.collect { s ->
                when {
                    s.endedByOwner -> repo.markEnded(accessKey)
                    s.state != null || s.meta != null -> repo.markSeen(accessKey)
                }
            }
        }
    }

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
    var notice = MutableStateFlow<String?>(null); private set

    /** True while an id-only request awaits the traveller's approval. */
    var awaitingApproval = MutableStateFlow(false); private set

    fun cloudAvailable() = graph.cloudAvailableSafe()

    fun clearMessages() { error.value = null; notice.value = null }

    /**
     * Follow a journey.
     *
     * The passcode really is optional, and this is where that promise used to
     * break: the id-only path returned "pending approval", the screen reported
     * a network problem, and the follower was left staring at an error while
     * nothing was actually wrong. Both paths now report exactly what happened —
     * approved, waiting, declined, wrong passcode, or genuinely offline — and
     * the waiting case is a state, not a failure.
     */
    fun join(tripIdRaw: String, passcodeRaw: String, viewerName: String, onOk: (String) -> Unit) {
        if (busy.value) return
        val tripId = TripCredentials.resolve(tripIdRaw)
        if (tripId == null) {
            error.value = "Enter the journey number your traveller shared with you."
            return
        }
        val passcode = InputRules.digits(passcodeRaw, TripCredentials.PASSCODE_LENGTH)

        viewModelScope.launch {
            busy.value = true; clearMessages()
            try {
                if (passcode.isNotBlank()) {
                    joinWithPasscode(tripId, passcode, viewerName, onOk)
                } else {
                    requestApproval(tripId, viewerName, onOk)
                }
            } finally {
                if (!awaitingApproval.value) busy.value = false
            }
        }
    }

    private suspend fun joinWithPasscode(
        tripId: String, passcode: String, viewerName: String, onOk: (String) -> Unit
    ) {
        if (!TripCredentials.isCompletePasscode(passcode)) {
            error.value = "The passcode is ${TripCredentials.PASSCODE_LENGTH} digits. " +
                "Leave it empty to ask the traveller to let you in instead."
            return
        }
        when (val r = graph.viewerRepository.join(tripId, passcode, viewerName.trim().ifBlank { null })) {
            is ViewerRepository.JoinResult.Ok -> onOk(r.accessKey)
            ViewerRepository.JoinResult.InvalidCredentials ->
                error.value = "That journey number and passcode don't match a live journey. " +
                    "Check both with the traveller — or leave the passcode empty and ask them to approve you."
            ViewerRepository.JoinResult.Unreachable ->
                error.value = "Couldn't reach Koode just now. Check your internet connection and try again."
            ViewerRepository.JoinResult.CloudUnavailable ->
                error.value = "Live following needs the cloud connection, which isn't configured on this build."
        }
    }

    private suspend fun requestApproval(tripId: String, viewerName: String, onOk: (String) -> Unit) {
        if (viewerName.isBlank()) {
            error.value = "Add your name so the traveller knows who's asking to follow."
            return
        }
        when (graph.viewerRepository.requestJoinById(tripId, viewerName)) {
            is ViewerRepository.IdJoinResult.Ok -> { busy.value = false; onOk(tripId) }
            ViewerRepository.IdJoinResult.NotFound ->
                error.value = "No live journey with that number. Double-check the digits with the traveller."
            ViewerRepository.IdJoinResult.Denied ->
                error.value = "The traveller declined this request."
            ViewerRepository.IdJoinResult.Unreachable ->
                error.value = "Couldn't reach Koode just now. Check your internet connection and try again."
            ViewerRepository.IdJoinResult.CloudUnavailable ->
                error.value = "Live following needs the cloud connection, which isn't configured on this build."
            ViewerRepository.IdJoinResult.Pending -> {
                // Not an error: this is the no-passcode flow working exactly as
                // designed. Say so, and wait.
                awaitingApproval.value = true
                notice.value = "Request sent. Waiting for the traveller to let you in — " +
                    "this screen opens the journey the moment they approve."
                pollUntilApproved(tripId, onOk)
            }
        }
    }

    private suspend fun pollUntilApproved(tripId: String, onOk: (String) -> Unit) {
        while (awaitingApproval.value) {
            delay(5000)
            when (graph.viewerRepository.pollJoinStatus(tripId)) {
                is ViewerRepository.IdJoinResult.Ok -> {
                    awaitingApproval.value = false; busy.value = false
                    onOk(tripId); return
                }
                ViewerRepository.IdJoinResult.Denied -> {
                    awaitingApproval.value = false; busy.value = false
                    error.value = "The traveller declined this request."
                    return
                }
                ViewerRepository.IdJoinResult.NotFound -> {
                    awaitingApproval.value = false; busy.value = false
                    error.value = "That journey is no longer available."
                    return
                }
                // Still pending, or briefly offline — keep waiting quietly.
                else -> {}
            }
        }
        busy.value = false
    }

    fun cancelWaiting() { awaitingApproval.value = false; busy.value = false; clearMessages() }

    companion object {
        val Factory = viewModelFactory { initializer { JoinVm(graphOf(this)) } }
    }
}

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

class SummaryVm(private val graph: AppGraph, val tripId: String) : ViewModel() {

    var trip = MutableStateFlow<ActiveTripEntity?>(null); private set
    var events = MutableStateFlow<List<EventEntity>>(emptyList()); private set
    var samples = MutableStateFlow<List<LocationSampleEntity>>(emptyList()); private set
    var legs = MutableStateFlow<List<TripLegEntity>>(emptyList()); private set

    val expenses: StateFlow<List<ExpenseEntity>> =
        graph.db.expenseDao().flowForTrip(tripId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The analysed journey — the same object the dashboard and the PDFs use. */
    val report = MutableStateFlow<JourneyAnalytics.JourneyReport?>(null)

    /** Distances, speeds and money in the traveller's own units. */
    val measures: Measures get() = graph.measures()

    /** Last export produced, so the screen can offer to share it again. */
    val lastExport = MutableStateFlow<java.io.File?>(null)
    val exporting = MutableStateFlow(false)

    /** A finished journey is a record: nothing on this screen may be edited. */
    val editable: Boolean get() = graph.tripManager.isEditable(trip.value)

    init {
        // A completed journey is a record the traveller opens to look back on.
        // Loading or analysing it must never be able to take the whole app down
        // — a single malformed row would otherwise crash on open. Anything that
        // fails here leaves the screen on its "working out the numbers" state
        // rather than closing the app.
        viewModelScope.launch {
            try {
                val t = graph.db.tripDao().byId(tripId)
                val ev = graph.db.eventDao().allForTrip(tripId)
                val sp = graph.db.locationDao().allForTrip(tripId)
                val lg = graph.db.legDao().forTrip(tripId)
                trip.value = t
                events.value = ev
                samples.value = sp
                legs.value = lg
                if (t != null) recompute(t, ev, sp, lg, graph.db.expenseDao().allForTrip(tripId))
            } catch (e: Exception) {
                android.util.Log.e("SummaryVm", "Could not load journey $tripId", e)
            }
        }
        // Costs can still be added to a journey that is running, so the
        // dashboard follows them.
        viewModelScope.launch {
            expenses.collect { list ->
                try {
                    val t = trip.value ?: return@collect
                    recompute(t, events.value, samples.value, legs.value, list)
                } catch (e: Exception) {
                    android.util.Log.e("SummaryVm", "Could not analyse journey $tripId", e)
                }
            }
        }
    }

    /**
     * Re-runs the analysis. Expenses are passed in rather than defaulted to a
     * read, because a default parameter value cannot call a suspend function —
     * and because the caller usually already has the list in hand.
     */
    private suspend fun recompute(
        t: ActiveTripEntity,
        ev: List<EventEntity>,
        sp: List<LocationSampleEntity>,
        lg: List<TripLegEntity>,
        expenseRows: List<ExpenseEntity>
    ) {
        val state = graph.db.stateDao().byId(tripId)
        report.value = JourneyAnalytics.analyse(
            JourneyAnalytics.Inputs(
                events = ev.map { com.trippulse.app.data.EventCodec.toDomain(it) },
                distanceCoveredM = state?.distanceCoveredM ?: 0.0,
                startedAtMs = t.startedAtMs ?: t.createdAtMs,
                endedAtMs = t.completedAtMs ?: System.currentTimeMillis(),
                expenses = expenseRows.map {
                    JourneyAnalytics.ExpenseInput(it.type, it.item, it.amount, it.quantity, it.unit, it.tMs)
                },
                legs = lg.map {
                    JourneyAnalytics.LegInput(
                        it.legIndex, it.mode, it.fromName, it.toName, it.startedAtMs, it.completedAtMs
                    )
                },
                transportMode = t.transportMode,
                topSpeedKmh = sp.mapNotNull { it.speedMps }.maxOrNull()?.times(3.6)
            )
        )
    }

    companion object {
        fun factory(tripId: String) = viewModelFactory { initializer { SummaryVm(graphOf(this), tripId) } }
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

class SettingsVm(private val graph: AppGraph) : ViewModel() {

    val settings: StateFlow<KoodeSettings> = graph.settings.state

    val savedPlaces: StateFlow<List<SavedPlaceEntity>> =
        graph.db.savedPlaceDao().allFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val placeSearch = PlaceSearch()
    var searchResults = MutableStateFlow<List<PlaceSearch.Place>>(emptyList()); private set
    var searching = MutableStateFlow(false); private set
    var message = MutableStateFlow<String?>(null); private set

    val update = MutableStateFlow<UpdateChecker.Available?>(graph.updateChecker.cached())
    val checkingUpdate = MutableStateFlow(false)
    val installedVersion: String = graph.updateChecker.installedVersion

    // ---- behaviour --------------------------------------------------------

    fun setLocationCadence(c: LocationCadence) =
        graph.settings.update { it.copy(locationCadence = c) }

    fun setViewerRefresh(v: ViewerRefresh) =
        graph.settings.update { it.copy(viewerRefresh = v) }

    fun setBatterySaverThreshold(pct: Int) =
        graph.settings.update { it.copy(batterySaverBelowPct = pct.coerceIn(5, 50)) }

    fun setKeepScreenOn(on: Boolean) =
        graph.settings.update { it.copy(keepScreenOnDuringJourney = on) }

    fun setHaptics(on: Boolean) = graph.settings.update { it.copy(hapticFeedback = on) }

    fun setThemeMode(mode: String) = graph.settings.update { it.copy(themeMode = mode) }

    fun setCheckForUpdates(on: Boolean) = graph.settings.update { it.copy(checkForUpdates = on) }

    fun setUnitPreference(p: UnitPreference) = graph.settings.update { it.copy(unitPreference = p) }

    /** Blank means "follow wherever I am", which is the default. */
    fun setCurrencyCode(code: String) =
        graph.settings.update { it.copy(currencyCode = code.trim().uppercase()) }

    fun setShareTimelineOnWhatsApp(on: Boolean) =
        graph.settings.update { it.copy(shareTimelineOnWhatsApp = on) }

    /** What the app has worked out for this device right now, for display. */
    fun detectedRegionSummary(): String {
        val country = graph.region.countryCode()
        val m = graph.measures()
        val where = country ?: "your device settings"
        return "Detected $where — showing ${m.distanceUnit} and ${m.currency.symbol}${m.currency.code}."
    }

    val whatsAppAvailable: Boolean
        get() = com.trippulse.app.data.share.TimelineDelivery.isAvailable(graph.appContext)

    fun circleSize(): Int = com.trippulse.app.data.share.TimelineDelivery.recipients(graph.appContext).size

    fun checkForUpdateNow() = viewModelScope.launch {
        checkingUpdate.value = true
        val found = graph.updateChecker.check(force = true)
        update.value = found
        message.value = if (found == null) "You're on the latest version ($installedVersion)."
        else "Koode ${found.versionName} is available."
        checkingUpdate.value = false
    }

    // ---- places -----------------------------------------------------------

    fun searchPlaces(query: String) {
        if (query.trim().length < 2 || searching.value) return
        viewModelScope.launch {
            searching.value = true
            searchResults.value = placeSearch.search(query)
            searching.value = false
            message.value = if (searchResults.value.isEmpty()) "No places found — try adding the city." else null
        }
    }

    fun addPlace(label: String, point: GeoPoint, fallbackName: String) {
        val name = InputRules.itemTextForStorage(label).ifBlank { fallbackName.split(",").first().trim() }
        if (name.isBlank()) { message.value = "Give the place a name (e.g. Home)."; return }
        viewModelScope.launch {
            graph.db.savedPlaceDao().upsert(SavedPlaceEntity(name, point.lat, point.lng, System.currentTimeMillis()))
            searchResults.value = emptyList()
            message.value = "Saved \"$name\"."
        }
    }

    @SuppressLint("MissingPermission")
    fun addCurrentLocation(label: String) {
        if (label.trim().isBlank()) {
            message.value = "Type a name first (e.g. Home), then tap Use current location."
            return
        }
        viewModelScope.launch {
            val point = try {
                val client = LocationServices.getFusedLocationProviderClient(graph.appContext)
                val loc = client.lastLocation.await()
                    ?: client.getCurrentLocation(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token
                    ).await()
                loc?.let { GeoPoint(it.latitude, it.longitude) }
            } catch (_: Exception) { null }
            if (point == null) {
                message.value = "Couldn't read your location — check GPS and location permission."
                return@launch
            }
            addPlace(label, point, label)
        }
    }

    fun deletePlace(name: String) = viewModelScope.launch { graph.db.savedPlaceDao().delete(name) }

    fun saveProfile(name: String, contacts: List<Profile.Contact>) {
        Profile.setName(graph.appContext, name)
        contacts.forEachIndexed { i, c -> Profile.setContact(graph.appContext, i + 1, c.name, c.phone) }
        message.value = "Profile saved."
    }

    companion object {
        val Factory = viewModelFactory { initializer { SettingsVm(graphOf(this)) } }
    }
}

/** Null-safe cloud availability that never throws if the backend is unconfigured. */
fun AppGraph.cloudAvailableSafe(): Boolean = try { cloud.isAvailable() } catch (_: Throwable) { false }
