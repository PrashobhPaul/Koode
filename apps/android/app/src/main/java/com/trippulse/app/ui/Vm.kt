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
import com.trippulse.app.data.export.JourneyPdf
import com.trippulse.app.data.share.TimelineDelivery
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.data.local.EventEntity
import com.trippulse.app.data.local.ExpenseEntity
import com.trippulse.app.data.local.LocationSampleEntity
import com.trippulse.app.data.local.SavedPlaceEntity
import com.trippulse.app.data.local.TripLegEntity
import com.trippulse.app.data.local.VehicleEntity
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
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

    /**
     * Who is following the traveller's live journey — approved followers of
     * this journey only, never a standing list. Empty when nothing is live.
     */
    val journeyFollowers = MutableStateFlow<List<String>>(emptyList())

    init {
        viewModelScope.launch { update.value = graph.updateChecker.check() }
        viewModelScope.launch {
            while (true) {
                val t = graph.db.tripDao().activeTrip()
                journeyFollowers.value = if (t != null && t.status == "ACTIVE" && t.cloudEnabled) {
                    try {
                        graph.cloud.fetchJoinRequests(t.accessKey)
                            .filter { it["status"] == "APPROVED" }
                            .mapNotNull { (it["name"] as? String)?.trim()?.ifBlank { null } }
                            .distinct()
                    } catch (_: Exception) { journeyFollowers.value }
                } else emptyList()
                delay(30_000)
            }
        }
    }

    fun dismissUpdate() {
        update.value?.let { graph.updateChecker.dismiss(it.versionName) }
        update.value = null
    }

    fun greetingName(): String = Profile.name(graph.appContext)

    /** Closed and waiting for the traveller's review before anything is shared. */
    fun awaitingReview(tripId: String): Boolean = graph.tripManager.closureRecord(tripId)?.pendingReview == true

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

    /** Last known position/ETA of a followed journey, cached by the follow service. */
    fun snapshot(ref: String): com.trippulse.app.domain.FollowSnapshot? =
        com.trippulse.app.domain.FollowSnapshot.decode(
            graph.appContext
                .getSharedPreferences(com.trippulse.app.service.TripFollowService.SNAPSHOT_PREFS, android.content.Context.MODE_PRIVATE)
                .getString(ref, null)
        )

    /** Live state of this phone's own running journey, for Home's map. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeState: StateFlow<TripStateEntity?> =
        graph.tripManager.activeTripFlow()
            .flatMapLatest { t -> if (t == null) flowOf(null) else graph.tripManager.stateFlow(t.tripId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** A distance in the traveller's own units. */
    fun distance(metres: Double): String = graph.measures().distance(metres)


    /** Private spending on the running journey: what is recorded, and what is still open. */
    data class SpendSummary(val total: Double, val entries: Int, val open: Int)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeSpend: StateFlow<SpendSummary?> =
        graph.tripManager.activeTripFlow()
            .flatMapLatest { t ->
                if (t == null) flowOf(null)
                else combine(graph.db.expenseDao().flowForTrip(t.tripId), graph.tripManager.expenseVersion) { rows, _ ->
                    SpendSummary(
                        total = rows.sumOf { it.amount },
                        entries = rows.size,
                        open = graph.tripManager.expenseOpportunities(t.tripId).count { it.open }
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Money in the traveller's own currency. */
    fun money(amount: Double): String = graph.measures().money(amount)

    /** Whether the running journey is on the traveller's own car or bike. */
    fun privateVehicle(mode: String?): Boolean = com.trippulse.app.domain.TransportCatalog.profile(mode).isPrivateVehicle

    // ---- "How you're doing": the same answers as the journey screen and the nudges ----
    fun hadWater() = viewModelScope.launch { graph.tripManager.logNeedMet(com.trippulse.app.domain.WellbeingCoach.Need.WATER) }
    fun ateSomething() = viewModelScope.launch { graph.tripManager.logNeedMet(com.trippulse.app.domain.WellbeingCoach.Need.FOOD) }
    fun hadTea() = viewModelScope.launch { graph.tripManager.logNourishment(Nourishment.TEA_COFFEE) }
    fun restroomBreak() = viewModelScope.launch { graph.tripManager.submitCheckpoint(com.trippulse.app.data.TripManager.Checkpoint(toilet = true)) }
    fun takingABreak() = viewModelScope.launch { graph.tripManager.logNeedMet(com.trippulse.app.domain.WellbeingCoach.Need.BREAK) }
    fun remindLater() = viewModelScope.launch {
        com.trippulse.app.domain.WellbeingCoach.Need.entries.forEach { graph.tripManager.snoozeNudge(it) }
    }

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

    fun unfollow(ref: String) = viewModelScope.launch {
        // Stop the server pushing this journey to this phone, then forget it.
        graph.appScope.launch { graph.push.unregisterFor(ref) }
        graph.viewerRepository.unfollow(ref)
    }

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
            appendLine("Koode will keep you informed along the way, without you having to call.")
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

/** Labels a journey gets when nobody named its ends; never worth suggesting,
 *  and never shown on a report — a real place name is resolved instead. */
private val PLACEHOLDER_NAMES = setOf(
    "Start point", "Destination", "Pinned start", "Pinned destination", "En route",
    "Current location", "Pinned location"
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

    /** Last known position of this phone, if location access was granted. */
    val here = MutableStateFlow<GeoPoint?>(null)

    /** A short, dismissible message for the planning screen (e.g. a shared link that couldn't be read). */
    val notice = MutableStateFlow<String?>(null)

    init {
        loadSuggestions()
        // Where the traveller is now: biases search results toward nearby
        // places and centres the pin-drop map. Null without location access.
        viewModelScope.launch {
            val fix = currentLocation()
            here.value = fix
        }
    }

    var busy = MutableStateFlow(false); private set

    /** Set when creation was refused because a journey is already running. */
    var runningTripId = MutableStateFlow<String?>(null); private set
    var error = MutableStateFlow<String?>(null); private set

    /** The legs of this journey, in order. Starts as one. */
    val legs = MutableStateFlow(listOf(LegDraft(fromText = "Current location",
        details = com.trippulse.app.core.VehicleMemory.load(graph.appContext, "CAR"))))

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

        // Destinations of past journeys, captured durably at trip creation so
        // they survive the trip being swept and rank at the top of what to reuse.
        val recentDests = runCatching { graph.db.recentDestinationDao().recent(RECENT_TRIPS) }
            .getOrNull().orEmpty()
        for (d in recentDests) {
            val point = GeoPoint(d.lat, d.lng)
            if (d.name.isBlank() || d.name in PLACEHOLDER_NAMES) continue
            if (out.any { it.isSamePlace(point) }) continue
            out.add(PlaceSuggestion(d.name, point, saved = false))
            if (out.size >= MAX_SUGGESTIONS) break
        }

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
        if (mode == leg.mode) leg else leg.copy(
            mode = mode,
            // A remembered car or bike fills itself in.
            details = if (TransportCatalog.isPrivate(mode)) com.trippulse.app.core.VehicleMemory.load(graph.appContext, mode) else emptyMap()
        )
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
            details = if (previous.mode == "TRAIN") emptyMap() else com.trippulse.app.core.VehicleMemory.load(graph.appContext, "CAR"),
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

    private var searchJob: kotlinx.coroutines.Job? = null

    /**
     * Search-as-you-type. Each call supersedes the previous one, so a slow
     * response for "Thri" can never overwrite the results for "Thrissur".
     * Results lean toward where the traveller is, or the stage's start.
     */
    fun searchPlaces(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            searchResults.value = emptyList()
            searching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            searching.value = true
            val near = legs.value.getOrNull(editingLeg.value)?.from ?: here.value
            try {
                searchResults.value = placeSearch.search(q, near = near)
            } finally {
                if (isActive) searching.value = false
            }
        }
    }

    /** Puts [place] into one field of one stage — the place picker's single exit. */
    fun applyPlace(index: Int, asStart: Boolean, place: PlaceSearch.Place) {
        editingLeg.value = index.coerceIn(0, legs.value.lastIndex)
        val label = place.name.trim().ifBlank { if (asStart) "Start point" else "Destination" }
        if (asStart) updateLeg(index) { it.copy(from = place.point, fromText = label) }
        else updateLeg(index) { it.copy(to = place.point, toText = label) }
        lastDroppedPin = place.point
        searchResults.value = emptyList()
    }

    /** "Start from wherever I am when I press Create." */
    fun useCurrentLocationAsStart(index: Int) =
        updateLeg(index) { it.copy(from = null, fromText = "Current location") }

    /** Saves a picked place under a name the traveller chose, for one-tap reuse. */
    fun savePlaceAt(name: String, point: GeoPoint) {
        val label = InputRules.itemTextForStorage(name)
        if (label.isBlank()) return
        viewModelScope.launch {
            graph.db.savedPlaceDao().upsert(SavedPlaceEntity(label, point.lat, point.lng, System.currentTimeMillis()))
            loadSuggestions()
        }
    }

    /**
     * Text shared from the Google Maps app (or a pasted Maps link). Resolved to
     * a place and dropped into the field the traveller was filling in.
     */
    fun applySharedText(text: String, index: Int, asStart: Boolean) {
        viewModelScope.launch {
            searching.value = true
            val place = placeSearch.resolveLink(text, near = here.value)
                ?: placeSearch.search(text, limit = 1).firstOrNull()
            searching.value = false
            if (place == null) {
                notice.value = "Couldn't read a location from that Google Maps link. Try sharing the place again, or search by name."
            } else {
                applyPlace(index, asStart, place)
                notice.value = when {
                    place.detail.startsWith("Only the area") ->
                        "Google's link for \"${place.name}\" didn't include its exact spot, so only the area was found. " +
                            "Tap the place and use Pin on map to set it — or in Google Maps, drop a pin and share that for an exact location."
                    place.detail.startsWith("Matched by address") ->
                        "Found \"${place.name}\" by its address — glance at the map to check it's the right spot."
                    else -> "Added \"${place.name}\" from Google Maps."
                }
            }
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
    /** A fresh, real position (see LocationFix) — never an hours-old cached one. */
    private suspend fun currentLocation(): GeoPoint? =
        (com.trippulse.app.core.LocationFix.current(graph.appContext) as? com.trippulse.app.core.LocationFix.Result.Found)?.point

    /** Called once location permission is granted, so search and the pin map centre on the traveller. */
    fun refreshHere() = viewModelScope.launch { currentLocation()?.let { here.value = it } }

    @Suppress("DEPRECATION")
    private suspend fun geocode(text: String): GeoPoint? = withContext(Dispatchers.IO) {
        try {
            Geocoder(graph.appContext).getFromLocationName(text, 1)
                ?.firstOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
        } catch (_: Exception) { null }
    }

    /**
     * The name a leg endpoint should carry into the journey, its timeline and
     * the PDF. A real name the traveller typed or picked always wins; otherwise
     * the canonical [PlaceResolver] resolves the coordinate — a saved place the
     * user named (Home, Office) first, then a reverse-geocoded name, then the
     * neutral "Location recorded". A bare placeholder ("Current location",
     * "Pinned destination", …) or a coordinate string never survives.
     */
    private suspend fun resolveEndpointName(rawText: String, point: GeoPoint): String {
        val typed = rawText.trim()
        // A "Current location · Kukatpally" pick is resolved by its coordinate,
        // not by the geocoded suffix baked into the label — so if the traveller
        // is standing on their saved "Home", the journey says Home, not the
        // area name. A name the traveller actually typed or picked is honoured.
        val fromGps = typed.startsWith("Current location", ignoreCase = true)
        if (!fromGps && typed.isNotBlank() && !com.trippulse.app.domain.PlaceResolver.isPlaceholder(typed)) {
            return typed
        }
        val saved = runCatching { graph.db.savedPlaceDao().all() }.getOrNull().orEmpty()
            .map { com.trippulse.app.domain.PlaceResolver.SavedPlace(it.name, it.lat, it.lng) }
        return com.trippulse.app.core.LocationFix.resolveLabel(graph.appContext, saved, point)
    }

    /** Resolves every leg to coordinates and creates the journey. */
    fun create(onDone: (String) -> Unit) {
        // Remember a fully described car or bike for next time.
        legs.value.filter { TransportCatalog.isPrivate(it.mode) && it.ready }
            .forEach { com.trippulse.app.core.VehicleMemory.save(graph.appContext, it.mode, it.details) }
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
                            fromName = resolveEndpointName(leg.fromText, from), from = from,
                            toName = resolveEndpointName(leg.toText, to), to = to,
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
    fun correctExpense(id: Long, amount: Double) = viewModelScope.launch { graph.db.expenseDao().updateAmount(id, amount) }

    // ---- corrections to the timeline ----
    fun reviseBreak(breakId: String, startMs: Long?, durationS: Long?, removed: Boolean) =
        viewModelScope.launch { graph.tripManager.reviseBreak(breakId, startMs, durationS, removed) }
    fun editTimelineEntry(eventId: String, atMs: Long?, removed: Boolean) =
        viewModelScope.launch { graph.tripManager.editTimelineEntry(eventId, atMs, removed) }

    /** Expense moments noticed on this journey, re-read whenever any changes. */
    val opportunities: StateFlow<List<com.trippulse.app.domain.Expenses.Opportunity>> =
        graph.tripManager.expenseVersion.map { graph.tripManager.expenseOpportunities(tripId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), graph.tripManager.expenseOpportunities(tripId))

    fun recordExpenseAmount(id: String, amount: Double) = viewModelScope.launch { graph.tripManager.recordExpenseAmount(tripId, id, amount) }
    fun markNoExpense(id: String) = viewModelScope.launch { graph.tripManager.markNoExpense(tripId, id) }
    fun deferExpense(id: String) = viewModelScope.launch { graph.tripManager.deferExpense(tripId, id) }

    fun submitCheckpoint(c: TripManager.Checkpoint, startAtMs: Long? = null, durationS: Long? = null) =
        viewModelScope.launch { graph.tripManager.submitCheckpoint(c, startAtMs, durationS) }

    /** One-tap wellbeing log (water, tea, a meal the app will name for you). */
    fun logNourishment(kind: Nourishment) = viewModelScope.launch { graph.tripManager.logNourishment(kind) }

    /** Break log with a refuel: the checkpoint and the fuel cost in one gesture. */
    fun submitCheckpointWithRefuel(c: TripManager.Checkpoint, amount: Double, quantity: Double?, unit: String) =
        viewModelScope.launch {
            graph.tripManager.submitCheckpoint(c, fuelAmountKnown = true)
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
    // ---- halts ----
    fun confirmHalt(type: com.trippulse.app.domain.Halts.Type, expectedMinutes: Int?, sinceMs: Long? = null) =
        viewModelScope.launch { graph.tripManager.confirmHalt(type, expectedMinutes, sinceMs) }
    fun declineHalt() = viewModelScope.launch { graph.tripManager.declineHalt() }
    fun cancelHalt() = viewModelScope.launch { graph.tripManager.cancelHalt() }
    fun resumeFromHalt() = viewModelScope.launch { graph.tripManager.resumeFromHalt() }
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
                    is TripManager.SwitchResult.MissingDetails ->
                        "Still needed: ${r.labels.joinToString(", ")}."
                }
            } finally {
                editBusy.value = false
            }
        }

    /** The live journey plan: destination, mode, role, planned halt. */
    val plan = graph.tripManager.planFlow

    /** Every version of this journey's plan, oldest first. */
    fun planHistory(): List<com.trippulse.app.domain.JourneyPlan> = graph.tripManager.planHistory(tripId)

    // ---- finding a new destination ----
    val savedPlaces: StateFlow<List<SavedPlaceEntity>> =
        graph.db.savedPlaceDao().allFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val placeSearch = PlaceSearch()
    var destResults = MutableStateFlow<List<PlaceSearch.Place>>(emptyList()); private set
    var destSearching = MutableStateFlow(false); private set
    private var destSearchJob: kotlinx.coroutines.Job? = null

    /** Search-as-you-type near where the traveller is; the newest query wins. */
    fun searchDestination(query: String) {
        destSearchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) { destResults.value = emptyList(); destSearching.value = false; return }
        destSearchJob = viewModelScope.launch {
            destSearching.value = true
            val near = state.value?.let { s -> s.lat?.let { la -> s.lng?.let { lo -> GeoPoint(la, lo) } } }
            try { destResults.value = placeSearch.search(q, near = near) } finally { if (isActive) destSearching.value = false }
        }
    }

    /** A Google Maps link (shared in or copied) turned into a place, or null. */
    suspend fun resolveShared(text: String): PlaceSearch.Place? =
        placeSearch.resolveLink(text) ?: placeSearch.search(text, limit = 1).firstOrNull()

    fun savePlaceAt(name: String, point: GeoPoint) = viewModelScope.launch {
        val clean = InputRules.itemTextForStorage(name)
        if (clean.isNotBlank()) {
            graph.db.savedPlaceDao().upsert(SavedPlaceEntity(clean, point.lat, point.lng, System.currentTimeMillis()))
        }
    }

    fun deletePlace(name: String) = viewModelScope.launch { graph.db.savedPlaceDao().delete(name) }

    /** Going somewhere else now: a new plan revision everyone following hears about. */
    fun changeDestination(place: com.trippulse.app.data.routing.PlaceSearch.Place) = viewModelScope.launch {
        if (editBusy.value) return@launch
        editBusy.value = true
        try {
            val name = place.name.substringBefore(" · ").trim()
            val ok = graph.tripManager.changeDestination(name, place.point)
            editMessage.value =
                if (ok) "Destination updated — everyone following you has been told." else "This journey can no longer be changed."
        } finally {
            editBusy.value = false
        }
    }

    fun setTravellerRole(role: com.trippulse.app.domain.WellbeingCoach.Role) =
        viewModelScope.launch { graph.tripManager.setTravellerRole(role) }

    fun setPlannedHalt(place: String?) = viewModelScope.launch {
        graph.tripManager.setPlannedHalt(place)
        editMessage.value = if (place.isNullOrBlank()) "Planned halt removed." else "Halt planned — everyone following you has been told."
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
    suspend fun buildReport(endAtMs: Long? = null): JourneyAnalytics.JourneyReport? = try {
        buildReportUnsafe(endAtMs)
    } catch (e: Exception) {
        android.util.Log.e("DriverVm", "report failed", e); null
    }

    private suspend fun buildReportUnsafe(endAtMs: Long?): JourneyAnalytics.JourneyReport? {
        val t = graph.db.tripDao().byId(tripId) ?: return null
        val st = graph.db.stateDao().byId(tripId)
        val events = graph.db.eventDao().allForTrip(tripId).map { com.trippulse.app.data.EventCodec.toDomain(it) }
        val samples = graph.db.locationDao().allForTrip(tripId)
        val expense = graph.db.expenseDao().allForTrip(tripId).map {
            JourneyAnalytics.ExpenseInput(it.type, it.item, it.amount, it.quantity, it.unit, it.tMs)
        }
        val legRows = graph.db.legDao().forTrip(tripId).map {
            JourneyAnalytics.LegInput(it.legIndex, it.mode, it.fromName, it.toName, it.startedAtMs, it.completedAtMs, it.seat)
        }
        return JourneyAnalytics.analyse(
            JourneyAnalytics.Inputs(
                events = events,
                distanceCoveredM = com.trippulse.app.data.coveredDistanceM(st?.distanceCoveredM ?: 0.0, samples),
                startedAtMs = t.startedAtMs ?: t.createdAtMs,
                endedAtMs = endAtMs ?: System.currentTimeMillis(),
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
     * "End journey": closes it, pending the traveller's review.
     *
     * [closingNote] is held with the closure and added to the timeline when
     * the traveller approves the journey, so what followers receive is exactly
     * what was reviewed.
     */
    fun complete(closingNote: String? = null, endAtMs: Long? = null, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        // Closing is local and durable; nothing after it may take the app down.
        // It publishes nothing: the review that follows is where the traveller
        // approves what is shared (and, if they like, sends it on WhatsApp).
        try {
            graph.tripManager.completeTrip(closingNote, endAtMs)
        } catch (e: Exception) {
            android.util.Log.e("DriverVm", "completeTrip failed", e)
        }
        onDone(false)
    }

    /** When they actually arrived, if the journey is being closed later than that. */
    suspend fun suggestedEndMs(): Long? =
        try { graph.tripManager.suggestedEndMs(tripId) } catch (_: Exception) { null }

    /** Builds the approved journey's timeline PDF and resolves who it can go to. */
    suspend fun prepareTimeline(): Boolean = try { prepareTimelineForSending() } catch (e: Exception) {
        android.util.Log.e("DriverVm", "timeline PDF failed", e); false
    }

    private suspend fun prepareTimelineForSending(): Boolean {
        val recipients = TimelineDelivery.recipients(graph.appContext)
        val t = graph.db.tripDao().byId(tripId) ?: return false
        val r = buildReport() ?: return false
        val ev = graph.db.eventDao().allForTrip(tripId)
        val samples = graph.db.locationDao().allForTrip(tripId)
        val doc = com.trippulse.app.data.export.ReportFactory.journey(graph.appContext, t, ev, samples, r, graph.measures())
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
        val awaitingFirstRead: Boolean,
        /** The traveller closed it and is reviewing it: neither live nor "ended". */
        val wrappingUp: Boolean = false,
        /** The traveller approved the journey and its verified report is stored. */
        val reportReady: Boolean = false
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
                awaitingFirstRead = meta == null && state == null,
                wrappingUp = state?.get("wrappingUp") == true && !repo.isEndedByOwner(state, events),
                reportReady = events.any { it["type"] == com.trippulse.app.domain.EventTypes.JOURNEY_REPORT_AVAILABLE }
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
                offlineExpected = mode == "FLIGHT" && plannedDep != null &&
                    now >= plannedDep - 30 * 60_000L && now <= plannedDep + 9 * 3_600_000L,
                journeyClosed = s.endedByOwner || s.wrappingUp
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
    /**
     * The traveller's approved journey report, fetched through a short-lived
     * link. It is the journey timeline only — the traveller's expenses are
     * never part of it.
     */
    fun openVerifiedReport(onReady: (java.io.File?) -> Unit) = viewModelScope.launch {
        if (reportBusy.value) return@launch
        reportBusy.value = true
        try {
            val url = graph.cloud.reportDownloadUrl(accessKey)
            val dir = java.io.File(graph.appContext.cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(dir, "Koode-verified-journey.pdf")
            onReady(if (url != null && graph.cloud.download(url, file)) file else null)
        } finally {
            reportBusy.value = false
        }
    }

    fun buildLastKnownReport(onReady: (java.io.File?) -> Unit) = viewModelScope.launch {
        if (reportBusy.value) return@launch
        reportBusy.value = true
        try {
            val s = ui.value
            val doc = runCatching {
                com.trippulse.app.data.export.ReportFactory.lastKnown(
                    graph.appContext, s.meta, s.state, s.events, darkness.value, fallbackRef = accessKey.take(8)
                )
            }.onFailure { android.util.Log.e("ViewerVm", "last-known report failed", it) }.getOrNull()
            val file = doc?.let { runCatching { JourneyPdf.write(graph.appContext, it) }.getOrNull() }
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

    /** The journey told as a story: the same words and charts the PDF carries. */
    val story = MutableStateFlow<com.trippulse.app.domain.report.JourneyStory.Story?>(null)

    /**
     * Display labels for the journey's ends, resolved offline: a meaningful
     * stored name is kept; a placeholder or coordinate left by an older build
     * is replaced by a saved-place match or the neutral fallback. Never a raw
     * coordinate or "Current location", including on historical journeys.
     */
    val originLabel = MutableStateFlow<String?>(null)
    val destLabel = MutableStateFlow<String?>(null)

    /**
     * The FASTag balance of the vehicle this journey was made on, as a ready-to-
     * show line ("47 crossings left", "₹1,240 left"), or null when the journey
     * carried no registered vehicle with a tracked balance. Resolved by matching
     * the journey's registration plate to the traveller's vehicle registry.
     */
    val fastagSummary = MutableStateFlow<String?>(null)

    /** Distances, speeds and money in the traveller's own units. */
    val measures: Measures get() = graph.measures()

    /** Last export produced, so the screen can offer to share it again. */
    val lastExport = MutableStateFlow<java.io.File?>(null)
    val exporting = MutableStateFlow(false)

    /** A finished journey is a record: nothing on this screen may be edited. */
    val editable: Boolean get() = graph.tripManager.isEditable(trip.value)

    // ---- review, approval and the private expense review ----

    val opportunities: StateFlow<List<com.trippulse.app.domain.Expenses.Opportunity>> =
        graph.tripManager.expenseVersion.map { graph.tripManager.expenseOpportunities(tripId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), graph.tripManager.expenseOpportunities(tripId))

    fun recordExpenseAmount(id: String, amount: Double) = viewModelScope.launch { graph.tripManager.recordExpenseAmount(tripId, id, amount) }
    fun markNoExpense(id: String) = viewModelScope.launch { graph.tripManager.markNoExpense(tripId, id) }
    fun leaveExpenseUnknown(id: String) = viewModelScope.launch { graph.tripManager.leaveExpenseUnknown(tripId, id) }
    fun deleteExpense(id: Long) = viewModelScope.launch { graph.db.expenseDao().delete(id) }
    fun correctExpense(id: Long, amount: Double) = viewModelScope.launch { graph.db.expenseDao().updateAmount(id, amount) }

    /** An expense added at review, in a category the journey made relevant. */
    fun addExpense(category: com.trippulse.app.domain.Expenses.Category, amount: Double) = viewModelScope.launch {
        val t = trip.value ?: return@launch
        graph.db.expenseDao().insert(
            ExpenseEntity(
                tripId = tripId, type = category.name, amount = amount, quantity = null, unit = null,
                note = "Source: added at review", tMs = t.completedAtMs ?: System.currentTimeMillis(), item = category.label
            )
        )
    }

    /** Toll crossings an annual pass covered on this journey: crossings, never money. */
    fun passCoveredCrossings(): Int = events.value.count {
        it.type == com.trippulse.app.domain.EventTypes.TOLL_CROSSED &&
            com.trippulse.app.data.EventCodec.payloadFromJson(it.payloadJson)["passCovered"] == true
    }

    /** Where this journey is in closing; null for journeys closed by older builds. */
    val closure = MutableStateFlow(graph.tripManager.closureRecord(tripId))
    val approving = MutableStateFlow(false)

    private fun refreshClosure() { closure.value = graph.tripManager.closureRecord(tripId) }

    /** The review screen was opened: recorded once. */
    fun startReview() = viewModelScope.launch { graph.tripManager.startReview(tripId); refreshClosure() }

    fun correctDestination(name: String) = viewModelScope.launch {
        if (graph.tripManager.correctDestination(tripId, name)) reload()
    }

    fun correctEndTime(endAtMs: Long) = viewModelScope.launch {
        if (graph.tripManager.correctEndTime(tripId, endAtMs)) reload()
    }

    /** "Approve & share journey": the only point followers hear it ended. */
    fun approve(safeConfirmed: Boolean, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        approving.value = true
        val ok = try { graph.tripManager.approveJourney(tripId, safeConfirmed) } catch (e: Exception) {
            android.util.Log.e("SummaryVm", "approve failed", e); false
        }
        refreshClosure(); reload()
        approving.value = false
        onDone(ok)
    }

    /** "Confirm expenses": private; the screen then saves the expense PDF on the phone. */
    suspend fun confirmExpenses() {
        graph.tripManager.approveExpenses(tripId)
        refreshClosure()
    }

    private fun reload() = viewModelScope.launch {
        val t = graph.db.tripDao().byId(tripId) ?: return@launch
        val ev = graph.db.eventDao().allForTrip(tripId)
        trip.value = t
        events.value = ev
        val saved = runCatching { graph.db.savedPlaceDao().all() }.getOrNull().orEmpty()
            .map { com.trippulse.app.domain.PlaceResolver.SavedPlace(it.name, it.lat, it.lng) }
        destLabel.value = com.trippulse.app.domain.PlaceResolver.display(
            t.destName, com.trippulse.app.domain.PlaceResolver.nearestSavedLabel(saved, t.destLat, t.destLng)
        )
        recompute(t, ev, samples.value, legs.value, graph.db.expenseDao().allForTrip(tripId))
    }

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
                if (t != null) {
                    val saved = runCatching { graph.db.savedPlaceDao().all() }.getOrNull().orEmpty()
                        .map { com.trippulse.app.domain.PlaceResolver.SavedPlace(it.name, it.lat, it.lng) }
                    originLabel.value = com.trippulse.app.domain.PlaceResolver.display(
                        t.originName,
                        com.trippulse.app.domain.PlaceResolver.nearestSavedLabel(saved, t.originLat, t.originLng)
                    )
                    destLabel.value = com.trippulse.app.domain.PlaceResolver.display(
                        t.destName,
                        com.trippulse.app.domain.PlaceResolver.nearestSavedLabel(saved, t.destLat, t.destLng)
                    )
                    fastagSummary.value = resolveFastagSummary(lg)
                    recompute(t, ev, sp, lg, graph.db.expenseDao().allForTrip(tripId))
                    // A record trimmed by an older build is completed from the
                    // cloud, and the numbers are worked out again from all of it.
                    if (graph.restorer.restore(t)) {
                        val whole = graph.db.locationDao().allForTrip(tripId)
                        samples.value = whole
                        recompute(t, ev, whole, lg, graph.db.expenseDao().allForTrip(tripId))
                    }
                }
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
                distanceCoveredM = com.trippulse.app.data.coveredDistanceM(state?.distanceCoveredM ?: 0.0, sp),
                startedAtMs = t.startedAtMs ?: t.createdAtMs,
                endedAtMs = t.completedAtMs ?: System.currentTimeMillis(),
                expenses = expenseRows.map {
                    JourneyAnalytics.ExpenseInput(it.type, it.item, it.amount, it.quantity, it.unit, it.tMs)
                },
                legs = lg.map {
                    JourneyAnalytics.LegInput(
                        it.legIndex, it.mode, it.fromName, it.toName, it.startedAtMs, it.completedAtMs, it.seat
                    )
                },
                transportMode = t.transportMode,
                topSpeedKmh = sp.mapNotNull { it.speedMps }.maxOrNull()?.times(3.6)
            )
        )
        story.value = runCatching {
            com.trippulse.app.data.export.ReportFactory.storyFor(graph.appContext, t, ev, sp, report.value, originLabel.value, destLabel.value)
        }.onFailure { android.util.Log.w("SummaryVm", "story failed", it) }.getOrNull()
    }

    /**
     * The FASTag balance line for the vehicle this journey used, or null.
     *
     * The journey stores its registration in a leg's details; we normalise that
     * plate and look for a matching vehicle in the registry with a tracked
     * balance. FASTag is optional throughout, so any missing piece — no plate on
     * the journey, no matching vehicle, or a vehicle with mode NONE — simply
     * yields null and the summary shows nothing about a pass.
     */
    private suspend fun resolveFastagSummary(legs: List<TripLegEntity>): String? {
        val plate = legs.asSequence()
            .mapNotNull { com.trippulse.app.domain.LegDetails.fromJson(it.detailsJson)[com.trippulse.app.domain.DetailKeys.REGISTRATION] }
            .map { com.trippulse.app.domain.fastag.TollSmsParser.normalizePlate(it) }
            .firstOrNull { it.isNotBlank() } ?: return null
        val vehicle = runCatching { graph.db.vehicleDao().all() }.getOrNull().orEmpty()
            .firstOrNull {
                it.registration.isNotBlank() &&
                    com.trippulse.app.domain.fastag.TollSmsParser.normalizePlate(it.registration) == plate
            } ?: return null
        return when (com.trippulse.app.domain.fastag.FastagMode.fromKey(vehicle.fastagMode)) {
            com.trippulse.app.domain.fastag.FastagMode.ANNUAL_PASS ->
                vehicle.passCrossingsLeft?.let { "$it crossing${if (it == 1) "" else "s"} left" }
            com.trippulse.app.domain.fastag.FastagMode.AMOUNT ->
                vehicle.amountLeft?.let { amt ->
                    // FASTag is a rupee wallet; format whole rupees plainly,
                    // paise only when the balance actually carries them.
                    val n = if (amt % 1.0 == 0.0) "%,.0f".format(amt) else "%,.2f".format(amt)
                    "₹$n left"
                }
            com.trippulse.app.domain.fastag.FastagMode.NONE -> null
        }
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

    // ---- FASTag ------------------------------------------------------------

    fun setTollDetection(on: Boolean) =
        graph.settings.update { it.copy(tollDetectionEnabled = on) }

    // ---- vehicles (optional per-vehicle FASTag) ---------------------------

    /** The traveller's saved vehicles, oldest first. May be empty. */
    val vehicles: StateFlow<List<VehicleEntity>> =
        graph.db.vehicleDao().allFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Add a new vehicle or update an existing one (by [id]). Everything except
     * the kind is optional: a bare "Bike" with no plate and no FASTag is valid,
     * and FASTag details are only kept when the chosen mode uses them.
     */
    fun saveVehicle(
        id: String?,
        kind: com.trippulse.app.domain.fastag.VehicleKind,
        name: String,
        registration: String,
        fastagMode: com.trippulse.app.domain.fastag.FastagMode,
        passCrossingsLeft: Int?,
        amountLeft: Double?
    ) = viewModelScope.launch {
        val plate = com.trippulse.app.domain.fastag.TollSmsParser.normalizePlate(registration)
        graph.db.vehicleDao().upsert(
            VehicleEntity(
                id = id ?: java.util.UUID.randomUUID().toString(),
                kind = kind.name,
                name = InputRules.itemText(name),
                registration = plate,
                fastagMode = fastagMode.name,
                passCrossingsLeft = passCrossingsLeft
                    ?.takeIf { fastagMode == com.trippulse.app.domain.fastag.FastagMode.ANNUAL_PASS },
                amountLeft = amountLeft
                    ?.takeIf { fastagMode == com.trippulse.app.domain.fastag.FastagMode.AMOUNT },
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    fun deleteVehicle(id: String) = viewModelScope.launch { graph.db.vehicleDao().delete(id) }

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

    private var searchJob: kotlinx.coroutines.Job? = null

    /** Search-as-you-type for the saved-places picker; the newest query always wins. */
    fun searchPlaces(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) { searchResults.value = emptyList(); searching.value = false; return }
        searchJob = viewModelScope.launch {
            searching.value = true
            try { searchResults.value = placeSearch.search(q) } finally { if (isActive) searching.value = false }
        }
    }

    /** A Google Maps link (shared in or copied) turned into a place, or null. */
    suspend fun resolveShared(text: String): PlaceSearch.Place? =
        placeSearch.resolveLink(text) ?: placeSearch.search(text, limit = 1).firstOrNull()

    fun addPlace(label: String, point: GeoPoint, fallbackName: String) {
        val name = InputRules.itemTextForStorage(label).ifBlank { fallbackName.split(",").first().trim() }
        if (name.isBlank()) { message.value = "Give the place a name (e.g. Home)."; return }
        viewModelScope.launch {
            graph.db.savedPlaceDao().upsert(SavedPlaceEntity(name, point.lat, point.lng, System.currentTimeMillis()))
            searchResults.value = emptyList()
            message.value = "Saved \"$name\"."
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
