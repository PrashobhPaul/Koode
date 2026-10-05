package com.trippulse.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.core.Profile
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.data.local.TripStateEntity
import com.trippulse.app.data.local.ViewerTripEntity
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.domain.JourneyStatus
import com.trippulse.app.domain.TransportCatalog
import com.trippulse.app.domain.Pictures
import com.trippulse.app.domain.WellbeingCoach
import com.trippulse.app.ui.HomeTabs
import com.trippulse.app.ui.HomeVm
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.components.ActivityItem
import com.trippulse.app.ui.components.ArtImage
import com.trippulse.app.ui.components.KoodeArt
import com.trippulse.app.ui.components.ModeArt
import com.trippulse.app.ui.components.ActivitySheet
import com.trippulse.app.ui.components.AdaptiveContainer
import com.trippulse.app.ui.components.KoodeBottomBar
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.KoodeChip
import com.trippulse.app.ui.components.KoodeHeroCard
import com.trippulse.app.ui.components.KoodeIcons
import com.trippulse.app.ui.components.KoodeNavRail
import com.trippulse.app.ui.components.KoodeTopBar
import com.trippulse.app.ui.components.LocalDims
import com.trippulse.app.ui.components.LocalWindowClass
import com.trippulse.app.ui.components.NavBadge
import com.trippulse.app.ui.components.NavTab
import com.trippulse.app.ui.components.PersonAvatar
import com.trippulse.app.ui.components.ProfileAvatar
import com.trippulse.app.ui.components.PrimaryButton
import com.trippulse.app.ui.components.PulsingDot
import com.trippulse.app.ui.components.SecondaryButton
import com.trippulse.app.ui.components.StatusPill
import com.trippulse.app.ui.components.TopBarAction
import com.trippulse.app.ui.components.rememberHaptics
import com.trippulse.app.ui.map.JourneyMap
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * The Koode shell: four destinations, exactly.
 *
 *   Home      the journey under way, or the next one
 *   Journeys  mine — active, scheduled, recent
 *   People    journey followers, and journeys shared with me
 *   More      a settings hub
 *
 * The Koode mark and wordmark sit top-left with actions top-right; icon +
 * label tabs sit at the bottom on a phone, a navigation rail on a tablet or a
 * phone held sideways. Tabs are a pager, so they can be swiped as well as
 * tapped. Starting a journey is offered where it belongs — Home with nothing
 * under way, and Journeys — never as a button floating over every page.
 */
@Composable
fun HomeScreen(nav: NavHostController) {
    val vm: HomeVm = viewModel(factory = HomeVm.Factory)
    val colors = KoodeTheme.colors
    val active by vm.activeTrip.collectAsStateWithLifecycle()
    val allTrips by vm.allTrips.collectAsStateWithLifecycle()
    val following by vm.following.collectAsStateWithLifecycle()
    val placeCount by vm.savedPlaceCount.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()

    val windowClass = LocalWindowClass.current
    val dims = LocalDims.current
    val haptics = rememberHaptics()
    val uriHandler = LocalUriHandler.current
    val density = LocalDensity.current

    val pager = rememberPagerState(pageCount = { TAB_COUNT })
    val scrolls = listOf(rememberScrollState(), rememberScrollState(), rememberScrollState(), rememberScrollState())
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var showActivity by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    // Profile and contacts are edited on their own pages: re-read on return.
    var profileVersion by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { profileVersion++ }
    val profileComplete = remember(profileVersion, placeCount) { Profile.isComplete(context) }

    // A brand-new person first meets the welcome; someone who has seen it but
    // not finished setting up lands on setup, since nothing works without it.
    // Shown to a new traveller, and again to anyone who has not yet read
    // the current privacy notice: continuing past it is their consent.
    var showWelcome by remember {
        mutableStateOf((!Onboarding.welcomeSeen(context) && !Profile.isComplete(context)) || !vm.privacyAccepted())
    }
    fun openSetup() {
        nav.navigate(Routes.settings(if (Profile.name(context).isBlank()) SettingsPage.PROFILE else SettingsPage.CONTACTS))
    }

    // ---- the collapsing top bar (phones) -------------------------------------
    val collapses = windowClass.isCompact
    val barPx = with(density) { (dims.topBarHeight + 1.dp).toPx() }
    var barOffset by remember { mutableFloatStateOf(0f) }
    val collapse = remember(barPx, collapses) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (collapses && available.y != 0f) {
                    barOffset = (barOffset + available.y).coerceIn(-barPx, 0f)
                }
                return Offset.Zero
            }
        }
    }

    // ---- tabs: tap, re-tap, swipe --------------------------------------------
    var tabAnimating by remember { mutableStateOf(false) }
    fun goTo(page: Int) {
        barOffset = 0f
        scope.launch {
            tabAnimating = true
            try { pager.animateScrollToPage(page) } finally { tabAnimating = false }
        }
    }
    fun selectTab(page: Int) {
        if (pager.currentPage == page) {
            // Tapping the tab you're on takes its page back to the top.
            barOffset = 0f
            scope.launch { scrolls[page].animateScrollTo(0) }
        } else goTo(page)
    }
    // A detent under the finger as each tab passes during a swipe.
    val currentHaptics by rememberUpdatedState(haptics)
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.drop(1).collect {
            if (pager.isScrollInProgress && !tabAnimating) currentHaptics.tick()
            barOffset = 0f
        }
    }
    // A pushed page asked for a tab (Settings › Journey followers → People).
    val tabRequest by HomeTabs.pending.collectAsStateWithLifecycle()
    LaunchedEffect(tabRequest) {
        tabRequest?.let { pager.scrollToPage(it); HomeTabs.consumed() }
    }

    // ---- what needs a look: the bell and the tab badges ----------------------
    val now = System.currentTimeMillis()
    val live = following.filter { !it.expired }
    fun who(v: ViewerTripEntity) = vm.snapshot(v.accessKey)?.ownerName ?: v.label
    val sos = live.filter { vm.snapshot(it.accessKey)?.sosActive == true }
    val concern = live.filter { it !in sos && vm.followStatus(it.accessKey).level == "CONCERN" }
    val waiting = live.filter { it !in sos && it !in concern && it.unreachableSinceMs != null }
    val reviews = allTrips.filter { it.status == "COMPLETED" && vm.awaitingReview(it.tripId) }
    val activity = buildList {
        sos.forEach { v ->
            add(ActivityItem("sos-${v.accessKey}", "${who(v)} raised an SOS", "Open their journey now.", colors.danger) {
                nav.navigate(Routes.viewer(v.accessKey))
            })
        }
        concern.forEach { v ->
            val reason = vm.followStatus(v.accessKey).reason
            add(ActivityItem("concern-${v.accessKey}", "${who(v)}'s journey needs a look", reason.ifBlank { "Open the journey to see what's happening." }, colors.danger) {
                nav.navigate(Routes.viewer(v.accessKey))
            })
        }
        waiting.forEach { v ->
            add(ActivityItem(
                "waiting-${v.accessKey}", "Waiting for ${who(v)}'s signal",
                "Last heard ${TimeFmt.ago(now, v.lastSeenAtMs ?: v.joinedAtMs)} — about the signal, not them", colors.warn
            ) { nav.navigate(Routes.viewer(v.accessKey)) })
        }
        reviews.forEach { t ->
            add(ActivityItem(
                "review-${t.tripId}", "Review your journey",
                "${t.originName} → ${t.destName} · followers see it only once you approve", colors.accent
            ) { nav.navigate(Routes.summary(t.tripId)) })
        }
        update?.let { u ->
            add(ActivityItem("update", "Koode ${u.versionName} is available", "Updating never affects a journey in progress.", colors.traveller) {
                uriHandler.openUri(u.downloadUrl)
            })
        }
        if (!profileComplete) {
            add(ActivityItem("setup", "Finish setting up Koode", Profile.missing(context).joinToString(" · "), colors.warn) { openSetup() })
        }
    }
    val urgent = sos.isNotEmpty() || concern.isNotEmpty()
    val tabs = listOf(
        NavTab("Home", KoodeIcons.Home, KoodeIcons.HomeSelected, if (urgent) NavBadge(0, colors.danger) else null),
        NavTab("Journeys", KoodeIcons.Journeys, KoodeIcons.JourneysSelected, if (reviews.isNotEmpty()) NavBadge(reviews.size, colors.accent) else null),
        NavTab("People", KoodeIcons.Circle, KoodeIcons.CircleSelected, if (live.isNotEmpty()) NavBadge(live.size, colors.traveller) else null),
        NavTab("More", KoodeIcons.More, KoodeIcons.MoreSelected, if (!profileComplete || update != null) NavBadge(0, colors.warn) else null)
    )

    // Starting a journey, wherever it is offered. One journey at a time.
    val startJourney = { if (profileComplete) nav.navigate(Routes.CREATE) else openSetup() }
    val scheduleJourney = { if (profileComplete) nav.navigate(Routes.CREATE_LATER) else openSetup() }

    val elevated = scrolls[pager.currentPage].value > 0 || barOffset < 0f
    val topBar: @Composable (Modifier) -> Unit = { modifier ->
        KoodeTopBar(elevated = elevated, onBrand = { nav.navigate(Routes.ABOUT) }, modifier = modifier) {
            TopBarAction(KoodeIcons.Follow, "Follow a journey", {
                if (profileComplete) nav.navigate(Routes.JOIN) else openSetup()
            })
            TopBarAction(
                KoodeIcons.Bell, "Activity", { showActivity = true },
                badge = if (activity.isNotEmpty()) NavBadge(activity.size, if (urgent) colors.danger else colors.accent) else null
            )
            Box(
                Modifier
                    .size(dims.actionTarget)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Your profile and settings") {
                        haptics.click()
                        selectTab(3)
                    },
                contentAlignment = Alignment.Center
            ) {
                ProfileAvatar(dims.avatarSize, ring = if (pager.currentPage == 3) colors.accent else null)
            }
        }
    }

    // ---- the pages ------------------------------------------------------------
    val pages: @Composable (Modifier, Dp) -> Unit = { modifier, topInset ->
        HorizontalPager(
            state = pager,
            modifier = modifier.nestedScroll(collapse),
            beyondViewportPageCount = 1
        ) { page ->
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // A page fades slightly as it slides away — no scaling, no bounce.
                        val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
                            .absoluteValue.coerceIn(0f, 1f)
                        alpha = 1f - 0.3f * off
                    }
                    .verticalScroll(scrolls[page])
            ) {
                Spacer(Modifier.height(topInset + Spacing.sm))
                AdaptiveContainer {
                    when (page) {
                        0 -> HomeFeed(nav, vm, active, allTrips, following, profileComplete, update, startJourney, scheduleJourney, ::openSetup)
                        1 -> JourneysSection(nav, vm, active, allTrips, startJourney, scheduleJourney) { deleteTarget = it }
                        2 -> PeopleSection(nav, vm, following, profileComplete, ::openSetup)
                        else -> SettingsTab(nav)
                    }
                }
                Spacer(Modifier.height(Spacing.section))
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
    ) {
        if (windowClass.isCompact) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val statusBar = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
                    pages(Modifier.fillMaxSize(), statusBar + dims.topBarHeight + 1.dp)
                    topBar(Modifier.offset { IntOffset(0, barOffset.roundToInt()) })
                    // The status bar stays covered while the top bar is away.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(if (elevated) colors.backgroundElevated else colors.background)
                    )
                }
                KoodeBottomBar(tabs, pager.currentPage, ::selectTab)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                topBar(Modifier)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    KoodeNavRail(tabs, pager.currentPage, ::selectTab)
                    pages(Modifier.weight(1f).fillMaxHeight(), 0.dp)
                }
            }
        }

        if (showWelcome) {
            WelcomeScreen(
                onGetStarted = {
                    Onboarding.markWelcomeSeen(context)
                    vm.acceptPrivacy()
                    showWelcome = false
                    if (!Profile.isComplete(context)) openSetup()
                },
                onSkip = {
                    Onboarding.markWelcomeSeen(context)
                    vm.acceptPrivacy()
                    showWelcome = false
                }
            )
        }
    }

    if (showActivity) {
        ActivitySheet(activity) { showActivity = false }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    haptics.heavy()
                    vm.deleteTrip(deleteTarget!!)
                    deleteTarget = null
                }) { Text("Delete forever", color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Keep") } },
            title = { Text("Delete this journey from your phone?") },
            text = { Text("Its route, timeline, playback and money records are removed from this device permanently.") }
        )
    }
}

private const val TAB_COUNT = 4

/** A fix older than this is shown as "last seen", never as live. */
private const val LIVE_FRESH_MS = 5 * 60_000L

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/** "Start a journey" with "Schedule for later" beneath it. */
@Composable
private fun StartJourneyActions(onStart: () -> Unit, onSchedule: () -> Unit) {
    val colors = KoodeTheme.colors
    PrimaryButton("Start a journey", onStart, height = 52.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Radii.md))
            .clickable(role = Role.Button, onClick = onSchedule),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(KoodeIcons.Calendar, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text("Schedule for later", color = colors.accent, style = MaterialTheme.typography.labelLarge)
    }
}

/** Origin, an arrow, destination — the journey at a glance. */
@Composable
private fun RouteBlock(origin: String, destination: String) {
    val colors = KoodeTheme.colors
    Column {
        Text(origin, color = colors.textHigh, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
        Icon(
            KoodeIcons.Arrow, contentDescription = "to", tint = colors.accent,
            modifier = Modifier.padding(vertical = 2.dp).size(20.dp)
        )
        Text(destination, color = colors.textHigh, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
    }
}

/** What the journey is doing, in the traveller's words. */
private fun journeyStateLabel(state: TripStateEntity?, trip: ActiveTripEntity): String = when {
    trip.status == "CREATED" -> "Planned"
    state == null -> "Active"
    state.journey == JourneyStatus.DRIVING.name -> "On the move"
    state.journey == JourneyStatus.STOPPED.name || state.journey == JourneyStatus.POSSIBLE_STOP.name -> "Stopped"
    state.journey == JourneyStatus.LONG_STOP.name -> "Taking a longer break"
    state.journey == JourneyStatus.OVERNIGHT.name -> "Halted"
    state.journey == JourneyStatus.PAUSED.name -> "Paused"
    state.journey == JourneyStatus.ARRIVED.name -> "Destination reached"
    else -> "Active"
}

/** "412 km left · 6h 24m to go", from whatever is known. */
private fun progressLine(vm: HomeVm, state: TripStateEntity?, now: Long, mode: String? = null): String? = listOfNotNull(
    state?.distanceRemainingM?.takeIf { it > 0 }?.let { "${vm.distance(it, mode)} left" },
    state?.etaLikelyMs?.takeIf { it > now }?.let { "${WellbeingCoach.duration((it - now) / 60_000)} to go" }
).joinToString(" · ").ifBlank { null }

/** "Today", "Yesterday", or "Sep 28". */
private fun relativeDay(ms: Long, nowMs: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH).format(day)
    }
}

/** Where a past journey stands. */
private fun closedStateLabel(vm: HomeVm, t: ActiveTripEntity): String = when {
    t.status == "EXPIRED" -> "Ended"
    vm.awaitingReview(t.tripId) -> "Awaiting your review"
    else -> "Finalized"
}

/** Recent journeys as compact rows, or a short empty state. */
@Composable
private fun RecentJourneys(
    nav: NavHostController,
    vm: HomeVm,
    trips: List<ActiveTripEntity>,
    onDelete: ((String) -> Unit)? = null
) {
    val colors = KoodeTheme.colors
    val now = System.currentTimeMillis()
    GroupLabel("Recent journeys")
    if (trips.isEmpty()) {
        SettingsGroup {
            Column(Modifier.fillMaxWidth().padding(LocalDims.current.cardPadding)) {
                Text("No journeys yet", color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Your completed journeys will appear here.",
                    color = colors.textMid, style = MaterialTheme.typography.bodySmall
                )
            }
        }
        return
    }
    SettingsGroup {
        trips.forEachIndexed { index, t ->
            if (index > 0) RowDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clickable(onClickLabel = "Open journey") { nav.navigate(Routes.summary(t.tripId)) }
                    .padding(start = Spacing.lg, end = if (onDelete != null) 0.dp else Spacing.lg, top = Spacing.sm, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeArt(t.transportMode, 44.dp, faceRight = true)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${t.originName} → ${t.destName}",
                        color = colors.textHigh, style = MaterialTheme.typography.titleSmall, maxLines = 1
                    )
                    val review = vm.awaitingReview(t.tripId)
                    Text(
                        "${relativeDay(t.completedAtMs ?: t.createdAtMs, now)} · ${closedStateLabel(vm, t)}",
                        color = if (review) colors.accent else colors.textMid,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (onDelete != null) {
                    TextButton(onClick = { onDelete(t.tripId) }) {
                        Icon(KoodeIcons.Trash, contentDescription = "Delete this journey", tint = colors.textLow, modifier = Modifier.size(20.dp))
                    }
                } else {
                    Icon(KoodeIcons.Chevron, contentDescription = null, tint = colors.textLow, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

/** One followed person Home can put on its map — only while their journey is live. */
private data class MapSubject(
    val key: String,
    val name: String,
    val current: GeoPoint,
    val origin: GeoPoint?,
    val destination: GeoPoint?,
    val destinationName: String?,
    val remainingM: Double?,
    val etaMs: Long?,
    val lastFixMs: Long?,
    val mode: String?
)

@Composable
private fun HomeFeed(
    nav: NavHostController,
    vm: HomeVm,
    active: ActiveTripEntity?,
    allTrips: List<ActiveTripEntity>,
    following: List<ViewerTripEntity>,
    profileComplete: Boolean,
    update: com.trippulse.app.data.update.UpdateChecker.Available?,
    onStart: () -> Unit,
    onSchedule: () -> Unit,
    onSetup: () -> Unit
) {
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val name = vm.greetingName()
    val now = System.currentTimeMillis()
    val myState by vm.activeState.collectAsStateWithLifecycle()

    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    Text(
        if (name.isNotBlank()) "$greeting, ${name.substringBefore(' ')}" else greeting,
        color = colors.textMid, style = MaterialTheme.typography.bodyLarge
    )

    // ---- update and setup, compact -------------------------------------------
    AnimatedBanner(visible = update != null) {
        update?.let { u ->
            SettingsGroup {
                SettingsRow(
                    "Koode ${u.versionName} is available",
                    subtitle = "Updating never affects a journey in progress",
                    titleColor = colors.traveller,
                    onClick = { uriHandler.openUri(u.downloadUrl) }
                )
                RowDivider()
                SettingsRow("Not now", showChevron = false, onClick = { vm.dismissUpdate() })
            }
        }
    }
    if (!profileComplete) {
        SettingsGroup {
            SettingsRow(
                "Finish setting up Koode",
                subtitle = Profile.missing(context).joinToString(" · "),
                titleColor = colors.warn,
                onClick = onSetup
            )
        }
    }

    // ---- a closed journey waiting for the traveller's approval -------------------
    allTrips.filter { it.status == "COMPLETED" && vm.awaitingReview(it.tripId) }.forEach { t ->
        KoodeCard(accent = colors.accent, onClick = { nav.navigate(Routes.summary(t.tripId)) }) {
            Text("Review your journey", color = colors.accent, style = MaterialTheme.typography.titleMedium)
            Text("${t.originName} → ${t.destName}", color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
            Text(
                "Shared with the people following it only once you approve.",
                color = colors.textMid, style = MaterialTheme.typography.bodySmall
            )
        }
    }

    val running = active?.takeIf { it.status == "ACTIVE" }
    val scheduled = active?.takeIf { it.status == "CREATED" }
    when {
        running != null -> ActiveJourneyHome(nav, vm, running, myState, now)
        scheduled != null -> {
            Text("Your next journey", color = colors.textHigh, style = MaterialTheme.typography.headlineMedium)
            KoodeCard(onClick = { nav.navigate(Routes.credentials(scheduled.tripId)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill("PLANNED", colors.warn)
                    Spacer(Modifier.weight(1f))
                    ModeArt(scheduled.transportMode, 56.dp, faceRight = true)
                }
                Spacer(Modifier.height(Spacing.sm))
                RouteBlock(scheduled.originName, scheduled.destName)
                scheduled.plannedDepartureMs?.let {
                    Spacer(Modifier.height(Spacing.sm))
                    Text("Departs ${TimeFmt.clockWithDay(it, now)}", color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        else -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ArtImage(KoodeArt.traveller, if (LocalWindowClass.current.isCompact) 188.dp else 232.dp)
            }
            Text("Ready for your next journey?", color = colors.textHigh, style = MaterialTheme.typography.headlineMedium)
            StartJourneyActions(onStart, onSchedule)
        }
    }

    // ---- journeys shared with me, only while they are live ------------------------
    SharedLiveJourneys(nav, vm, following, now)

    // ---- recent journeys, when nothing of mine is under way ------------------------
    if (running == null) {
        val recent = allTrips.filter { it.status == "COMPLETED" || it.status == "EXPIRED" }
            .sortedByDescending { it.completedAtMs ?: it.createdAtMs }
            .take(3)
        RecentJourneys(nav, vm, recent)
    }
}

/** Home while my own journey is under way: the journey first, then me. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveJourneyHome(
    nav: NavHostController,
    vm: HomeVm,
    trip: ActiveTripEntity,
    state: TripStateEntity?,
    now: Long
) {
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val windowClass = LocalWindowClass.current
    val spend by vm.activeSpend.collectAsStateWithLifecycle()
    val journeyFollowers by vm.journeyFollowers.collectAsStateWithLifecycle()

    KoodeHeroCard(accent = colors.accent, onClick = { nav.navigate(Routes.driver(trip.tripId)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(colors.accent, size = 7.dp)
            Spacer(Modifier.width(Spacing.xs))
            Text("ON YOUR JOURNEY", color = colors.accent, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(journeyStateLabel(state, trip), color = colors.textMid, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(Spacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { RouteBlock(trip.originName, trip.destName) }
            Spacer(Modifier.width(Spacing.sm))
            ModeArt(trip.transportMode, 84.dp, faceRight = true)
        }
        Spacer(Modifier.height(Spacing.md))
        state?.etaLikelyMs?.let {
            Text("ETA ${TimeFmt.clockWithDay(it, now)}", color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
        }
        progressLine(vm, state, now, trip.transportMode)?.let {
            Text(it, color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
        }
        if (state?.lat != null && state.lng != null) {
            Spacer(Modifier.height(Spacing.md))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.md))) {
                JourneyMap(
                    current = GeoPoint(state.lat!!, state.lng!!),
                    origin = GeoPoint(trip.originLat, trip.originLng),
                    destination = GeoPoint(trip.destLat, trip.destLng),
                    live = true,
                    showPlayControl = false,
                    height = windowClass.mapHeight,
                    mode = trip.transportMode
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        PrimaryButton("View journey", { nav.navigate(Routes.driver(trip.tripId)) }, height = 48.dp)
    }

    // ---- how you're doing: private, explicit, never forced ----
    val ownVehicle = vm.privateVehicle(trip.transportMode)
    val moving = state?.journey == JourneyStatus.DRIVING.name
    val started = trip.startedAtMs ?: trip.createdAtMs
    fun since(at: Long?) = at?.takeIf { it >= started }
    KoodeCard(title = "How you're doing") {
        WellbeingLine("Water", since(state?.waterAtMs)?.let { "Had water · ${TimeFmt.clock(it)}" } ?: "Not recorded yet", KoodeArt.file(Pictures.WATER))
        WellbeingLine("Food", since(state?.foodAtMs)?.let { "Ate · ${TimeFmt.clock(it)}" } ?: "Not recorded yet", KoodeArt.file(Pictures.FOOD))
        WellbeingLine("Restroom", since(state?.toiletAtMs)?.let { "Last stop · ${TimeFmt.clock(it)}" } ?: "Not recorded yet", KoodeArt.file(Pictures.TOILET))
        val breakLine = when {
            ownVehicle && moving && state?.drivingSinceMs != null && since(state.lastBreakEndAtMs) == null ->
                "${if (TransportCatalog.profile(trip.transportMode).key == "BIKE") "Riding" else "Driving"} for " +
                    "${WellbeingCoach.duration((now - state.drivingSinceMs) / 60_000)} · no break recorded yet"
            since(state?.lastBreakEndAtMs) != null -> "Last break · ${TimeFmt.clock(state!!.lastBreakEndAtMs!!)}"
            else -> "No break recorded yet"
        }
        WellbeingLine("Break", breakLine, KoodeArt.file(Pictures.REST))
        Spacer(Modifier.height(Spacing.sm))
        if (ownVehicle && moving) {
            Text(
                "Log these when you next stop — Koode never asks while you're on the move.",
                color = colors.textMid, style = MaterialTheme.typography.bodySmall
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                KoodeChip("Had water", false, { vm.hadWater() }, leadingArt = KoodeArt.file(Pictures.WATER))
                KoodeChip("Ate something", false, { vm.ateSomething() }, leadingArt = KoodeArt.file(Pictures.FOOD))
                KoodeChip("Restroom", false, { vm.restroomBreak() }, leadingArt = KoodeArt.file(Pictures.TOILET))
                KoodeChip("Taking a break", false, { vm.takingABreak() }, leadingArt = KoodeArt.file(Pictures.REST))
                KoodeChip("Had tea", false, { vm.hadTea() }, leading = "☕")
                KoodeChip("Remind me later", false, { vm.remindLater() })
            }
        }
    }

    // ---- food & expenses: private to the traveller ----
    spend?.let { sp ->
        KoodeCard(title = "Food & expenses · private", onClick = { nav.navigate(Routes.driver(trip.tripId)) }) {
            Text(
                if (sp.entries == 0) "Nothing recorded yet" else "${vm.money(sp.total)} recorded",
                color = colors.textHigh, style = MaterialTheme.typography.titleMedium
            )
            if (sp.open > 0) {
                Text(
                    "${sp.open} ${if (sp.open == 1) "entry" else "entries"} pending",
                    color = colors.textMid, style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    // ---- who is following this journey ----
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Following this journey", color = colors.textHigh,
            style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            scope.launch {
                vm.shareText(trip.tripId, includePasscode = true)?.let { text ->
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            },
                            "Share journey"
                        )
                    )
                }
            }
        }) {
            Text("Share", color = colors.accent, style = MaterialTheme.typography.labelLarge)
        }
    }
    if (journeyFollowers.isEmpty()) {
        Text(
            "No one is following this journey yet.",
            color = colors.textMid, style = MaterialTheme.typography.bodySmall
        )
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            journeyFollowers.forEach { n ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(Radii.pill))
                        .background(colors.surface)
                        .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PersonAvatar(n, 26.dp)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(n, color = colors.textHigh, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun WellbeingLine(label: String, value: String, @androidx.annotation.DrawableRes art: Int? = null) {
    val colors = KoodeTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (art != null) {
            ArtImage(art, 34.dp)
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(label, color = colors.textLow, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
        Text(value, color = colors.textHigh, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Journeys someone is sharing with me, while they last: the journey, never a
 * standing map of where people are.
 */
@Composable
private fun SharedLiveJourneys(
    nav: NavHostController,
    vm: HomeVm,
    following: List<ViewerTripEntity>,
    now: Long
) {
    val colors = KoodeTheme.colors
    val windowClass = LocalWindowClass.current
    val live = following.filter { !it.expired }
    if (live.isEmpty()) return
    val snapshots = live.associate { it.accessKey to vm.snapshot(it.accessKey) }
    fun personName(v: ViewerTripEntity) = snapshots[v.accessKey]?.ownerName ?: v.label

    val subjects = live.mapNotNull { v ->
        val snap = snapshots[v.accessKey] ?: return@mapNotNull null
        if (!snap.hasPosition) return@mapNotNull null
        MapSubject(
            key = v.accessKey, name = personName(v),
            current = GeoPoint(snap.lat!!, snap.lng!!),
            origin = if (snap.originLat != null && snap.originLng != null) GeoPoint(snap.originLat, snap.originLng) else null,
            destination = if (snap.destLat != null && snap.destLng != null) GeoPoint(snap.destLat, snap.destLng) else null,
            destinationName = snap.destination,
            remainingM = snap.distanceRemainingM, etaMs = snap.etaLikelyMs,
            lastFixMs = snap.lastLocationAtMs, mode = snap.mode
        )
    }
    var featuredKey by rememberSaveable { mutableStateOf<String?>(null) }
    val featured = subjects.firstOrNull { it.key == featuredKey } ?: subjects.firstOrNull()

    GroupLabel("Shared with you")
    if (featured != null) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.lg))) {
            JourneyMap(
                current = featured.current,
                origin = featured.origin,
                destination = featured.destination,
                live = true,
                showPlayControl = false,
                height = windowClass.mapHeight,
                mode = featured.mode
            )
            if (subjects.size > 1) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.md)
                        .clip(RoundedCornerShape(Radii.pill))
                        .background(colors.backgroundElevated.copy(alpha = 0.92f))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    subjects.forEach { sub ->
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClickLabel = "Show ${sub.name}'s journey") { featuredKey = sub.key }
                        ) {
                            PersonAvatar(sub.name, 32.dp, ring = if (sub.key == featured.key) colors.accent else null)
                        }
                    }
                }
            }
        }
    }
    SettingsGroup {
        live.forEachIndexed { index, v ->
            if (index > 0) RowDivider()
            val st = vm.followStatus(v.accessKey)
            val snap = snapshots[v.accessKey]
            val waitingSignal = v.unreachableSinceMs != null
            val (status, tint) = when {
                snap?.sosActive == true -> "SOS" to colors.danger
                waitingSignal -> "Waiting" to colors.warn
                st.level == "CONCERN" -> "Needs a look" to colors.danger
                st.level == "ATTENTION" -> "Keep an eye" to colors.warn
                else -> "On the way" to colors.accent
            }
            val fresh = snap?.lastLocationAtMs != null && now - snap.lastLocationAtMs < LIVE_FRESH_MS
            val subtitle = when {
                waitingSignal -> "Last heard ${TimeFmt.ago(now, v.lastSeenAtMs ?: v.joinedAtMs)} — about the signal, not them"
                st.reason.isNotBlank() -> st.reason
                snap?.destination != null -> listOfNotNull(
                    "To ${snap.destination}",
                    snap.etaLikelyMs?.let { "ETA ${TimeFmt.clockWithDay(it, now)}" },
                    if (!fresh) snap.lastLocationAtMs?.let { "updated ${TimeFmt.ago(now, it)}" } else null
                ).joinToString(" · ")
                else -> st.headline
            }
            PersonRow(
                name = personName(v), subtitle = subtitle, trailing = status, trailingColor = tint,
                onClick = { nav.navigate(Routes.viewer(v.accessKey)) }
            )
        }
    }
}

/** A compact person row: initial, name, one line, a status word. */
@Composable
private fun PersonRow(
    name: String,
    subtitle: String?,
    trailing: String?,
    trailingColor: Color,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null
) {
    val colors = KoodeTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = Spacing.lg, end = if (action != null) 0.dp else Spacing.lg, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PersonAvatar(name, 38.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(name, color = colors.textHigh, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = colors.textMid, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.sm))
            Text(trailing, color = trailingColor, style = MaterialTheme.typography.labelLarge)
        }
        action?.invoke()
    }
}

// ---------------------------------------------------------------------------
// Journeys
// ---------------------------------------------------------------------------

@Composable
private fun JourneysSection(
    nav: NavHostController,
    vm: HomeVm,
    active: ActiveTripEntity?,
    allTrips: List<ActiveTripEntity>,
    onStart: () -> Unit,
    onSchedule: () -> Unit,
    onDelete: (String) -> Unit
) {
    val colors = KoodeTheme.colors
    val now = System.currentTimeMillis()
    val state by vm.activeState.collectAsStateWithLifecycle()
    com.trippulse.app.ui.components.CrashNotice()
    Column {
        Text("Journeys", color = colors.textHigh, style = MaterialTheme.typography.headlineMedium)
        Text("Your trips, in one place", color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
    }

    when {
        active != null && active.status == "ACTIVE" -> {
            GroupLabel("Active")
            KoodeHeroCard(accent = colors.accent, onClick = { nav.navigate(Routes.driver(active.tripId)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(journeyStateLabel(state, active).uppercase(), colors.accent, pulsing = state?.journey == JourneyStatus.DRIVING.name)
                    Spacer(Modifier.weight(1f))
                    Text(TransportCatalog.profile(active.transportMode).label, color = colors.textMid, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(Spacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { RouteBlock(active.originName, active.destName) }
                    Spacer(Modifier.width(Spacing.sm))
                    ModeArt(active.transportMode, 84.dp, faceRight = true)
                }
                Spacer(Modifier.height(Spacing.sm))
                state?.etaLikelyMs?.let {
                    Text("ETA ${TimeFmt.clockWithDay(it, now)}", color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
                }
                val facts = listOfNotNull(
                    state?.distanceRemainingM?.takeIf { it > 0 }?.let { "${vm.distance(it)} left" },
                    active.startedAtMs?.let { "${WellbeingCoach.duration((now - it) / 60_000)} so far" },
                    state?.overnightType?.let { "Halt: ${it.lowercase().replace('_', ' ')}" }
                )
                if (facts.isNotEmpty()) {
                    Text(facts.joinToString(" · "), color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        active != null && active.status == "CREATED" -> {
            GroupLabel("Scheduled")
            SettingsGroup {
                SettingsRow(
                    "${active.originName} → ${active.destName}",
                    subtitle = active.plannedDepartureMs?.let { "Departs ${TimeFmt.clockWithDay(it, now)}" } ?: "Planned",
                    icon = KoodeIcons.Calendar,
                    onClick = { nav.navigate(Routes.credentials(active.tripId)) }
                )
            }
        }
        else -> {
            KoodeHeroCard(accent = colors.accent) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ArtImage(KoodeArt.traveller, 150.dp)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text("Ready to travel?", color = colors.textHigh, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "Start a journey and Koode will travel with you.",
                    color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(Spacing.md))
                StartJourneyActions(onStart, onSchedule)
            }
        }
    }

    val history = allTrips.filter { it.status == "COMPLETED" || it.status == "EXPIRED" }
        .sortedByDescending { it.completedAtMs ?: it.createdAtMs }
    RecentJourneys(nav, vm, history, onDelete)
    if (history.isNotEmpty()) {
        Text(
            "Kept on this phone until you delete it. Open one for its playback, timeline and report.",
            color = colors.textLow, style = MaterialTheme.typography.bodySmall
        )
    }
}

// ---------------------------------------------------------------------------
// People
// ---------------------------------------------------------------------------

@Composable
private fun PeopleSection(
    nav: NavHostController,
    vm: HomeVm,
    following: List<ViewerTripEntity>,
    profileComplete: Boolean,
    onSetup: () -> Unit
) {
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val circle = remember(version) { Profile.contacts(context).filter { it.filled } }
    val now = System.currentTimeMillis()

    Text("People", color = colors.textHigh, style = MaterialTheme.typography.headlineMedium)

    // ---- journey followers: who I can choose to share a journey with ----
    Column {
        Text("Journey followers", color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
        Text(
            "People you can choose to share a journey with.",
            color = colors.textMid, style = MaterialTheme.typography.bodySmall
        )
    }
    if (circle.isNotEmpty()) {
        SettingsGroup {
            circle.forEachIndexed { i, c ->
                if (i > 0) RowDivider()
                PersonRow(name = c.name, subtitle = "Trusted", trailing = null, trailingColor = colors.textMid)
            }
        }
    }
    SecondaryButton("Add a person", { nav.navigate(Routes.settings(SettingsPage.CONTACTS)) }, leading = "＋", height = 48.dp)

    // ---- journeys shared with me ----
    Spacer(Modifier.height(Spacing.xs))
    Text("Shared with you", color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
    if (following.isEmpty()) {
        SettingsGroup {
            Column(Modifier.fillMaxWidth().padding(LocalDims.current.cardPadding)) {
                Text("No active journeys", color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
                Text(
                    "When someone shares a journey with you, it will appear here.",
                    color = colors.textMid, style = MaterialTheme.typography.bodySmall
                )
            }
        }
    } else {
        SettingsGroup {
            following.forEachIndexed { i, v ->
                if (i > 0) RowDivider()
                val snap = vm.snapshot(v.accessKey)
                val (status, tint) = when {
                    v.expired -> "Ended" to colors.textLow
                    v.unreachableSinceMs != null -> "Waiting" to colors.warn
                    vm.followHealth(v.accessKey) == "CONCERN" -> "Needs a look" to colors.danger
                    vm.followHealth(v.accessKey) == "ATTENTION" -> "Keep an eye" to colors.warn
                    else -> "On the way" to colors.accent
                }
                PersonRow(
                    name = snap?.ownerName ?: v.label,
                    subtitle = when {
                        v.expired -> "Journey ended"
                        snap?.destination != null -> "To ${snap.destination}" +
                            (snap.etaLikelyMs?.let { " · ETA ${TimeFmt.clockWithDay(it, now)}" } ?: "")
                        else -> v.label
                    },
                    trailing = status, trailingColor = tint,
                    onClick = { nav.navigate(Routes.viewer(v.accessKey)) },
                    action = {
                        TextButton(onClick = { vm.unfollow(v.accessKey) }) {
                            Icon(KoodeIcons.Close, contentDescription = "Stop following", tint = colors.textLow, modifier = Modifier.size(18.dp))
                        }
                    }
                )
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Radii.md))
            .clickable(role = Role.Button) { if (profileComplete) nav.navigate(Routes.JOIN) else onSetup() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(KoodeIcons.Follow, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text("Follow a journey", color = colors.accent, style = MaterialTheme.typography.labelLarge)
    }
}
