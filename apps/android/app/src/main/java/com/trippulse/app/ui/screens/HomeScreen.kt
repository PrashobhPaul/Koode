package com.trippulse.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.core.Profile
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.data.local.ViewerTripEntity
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.ui.map.JourneyMap
import com.trippulse.app.ui.HomeVm
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.components.AdaptiveContainer
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.KoodeHeroCard
import com.trippulse.app.ui.components.KoodeIcons
import com.trippulse.app.ui.components.PersonAvatar
import com.trippulse.app.ui.components.LocalWindowClass
import com.trippulse.app.ui.components.PrimaryButton
import com.trippulse.app.ui.components.PulsingDot
import com.trippulse.app.ui.components.SecondaryButton
import com.trippulse.app.ui.components.SectionHeader
import com.trippulse.app.ui.components.StatusPill
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Motion
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.trippulse.app.ui.components.ActivityItem
import com.trippulse.app.ui.components.ActivitySheet
import com.trippulse.app.ui.components.AdaptiveGrid
import com.trippulse.app.ui.components.KoodeBottomBar
import com.trippulse.app.ui.components.KoodeFab
import com.trippulse.app.ui.components.KoodeNavRail
import com.trippulse.app.ui.components.KoodeTopBar
import com.trippulse.app.ui.components.LocalDims
import com.trippulse.app.ui.components.NavBadge
import com.trippulse.app.ui.components.NavTab
import com.trippulse.app.ui.components.TopBarAction
import com.trippulse.app.ui.components.rememberHaptics
import kotlinx.coroutines.flow.drop
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * The Koode shell: four destinations and one primary action.
 *
 *   🏠 Home      what matters right now
 *   🧭 Journeys  mine — active, scheduled, past
 *   👥 People    my circle, and who I follow
 *   ⚙️ More      places, contacts, behaviour, privacy
 *
 * The frame follows the global apps people already know by feel: the Koode
 * mark and wordmark top-left with actions top-right (follow, activity, me),
 * icon + label tabs at the bottom on a phone and a navigation rail on a
 * tablet or a phone held sideways. Tabs are a pager, so they can be swiped
 * as well as tapped, with a detent felt under the finger as each one passes.
 * On a phone the top bar slides away while reading down a page and returns
 * on the way back up; tapping the current tab again returns it to the top.
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
    var profileVersion by remember { mutableIntStateOf(0) }
    val profileComplete = remember(profileVersion, placeCount) { Profile.isComplete(context) }

    // A brand-new person first meets the welcome; someone who has seen it but
    // not finished setting up lands on setup, since nothing works without it.
    var showWelcome by remember {
        mutableStateOf(!Onboarding.welcomeSeen(context) && !Profile.isComplete(context))
    }
    LaunchedEffect(Unit) {
        if (!showWelcome && !Profile.isComplete(context)) pager.scrollToPage(3)
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
            add(ActivityItem("setup", "Finish setting up Koode", Profile.missing(context).joinToString(" · "), colors.warn) { goTo(3) })
        }
    }
    val urgent = sos.isNotEmpty() || concern.isNotEmpty()
    val tabs = listOf(
        NavTab("Home", KoodeIcons.Home, KoodeIcons.HomeSelected, if (urgent) NavBadge(0, colors.danger) else null),
        NavTab("Journeys", KoodeIcons.Journeys, KoodeIcons.JourneysSelected, if (reviews.isNotEmpty()) NavBadge(reviews.size, colors.accent) else null),
        NavTab("People", KoodeIcons.Circle, KoodeIcons.CircleSelected, if (live.isNotEmpty()) NavBadge(live.size, colors.traveller) else null),
        NavTab("More", KoodeIcons.More, KoodeIcons.MoreSelected, if (!profileComplete || update != null) NavBadge(0, colors.warn) else null)
    )

    // ---- the one primary action ----------------------------------------------
    // One journey at a time. While one is running this is the way back into
    // it rather than the way to start another: two live journeys would mean
    // two claims about where one person is, and whoever is following would
    // have no way to know which is true.
    val liveTrip = active
    val fabLabel = if (liveTrip != null) "Your journey" else "Start a journey"
    val fabEnabled = profileComplete || liveTrip != null
    val onFab = {
        when {
            liveTrip != null -> nav.navigate(Routes.driver(liveTrip.tripId))
            profileComplete -> nav.navigate(Routes.CREATE)
            else -> goTo(3)
        }
    }
    val fabIcon: @Composable () -> Unit = {
        if (liveTrip != null) PulsingDot(androidx.compose.material3.LocalContentColor.current, size = 8.dp)
        else Icon(KoodeIcons.Plus, contentDescription = null, modifier = Modifier.size(22.dp))
    }

    val elevated = scrolls[pager.currentPage].value > 0 || barOffset < 0f
    val topBar: @Composable (Modifier) -> Unit = { modifier ->
        KoodeTopBar(elevated = elevated, onBrand = { nav.navigate(Routes.ABOUT) }, modifier = modifier) {
            TopBarAction(KoodeIcons.Follow, "Follow a journey", {
                if (profileComplete) nav.navigate(Routes.JOIN) else goTo(3)
            })
            TopBarAction(
                KoodeIcons.Bell, "Activity", { showActivity = true },
                badge = if (activity.isNotEmpty()) NavBadge(activity.size, if (urgent) colors.danger else colors.accent) else null
            )
            val me = vm.greetingName().ifBlank { "You" }
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
                PersonAvatar(me, dims.avatarSize, ring = if (pager.currentPage == 3) colors.accent else null)
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
                        // Pages ease back and dim slightly as they slide away.
                        val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
                            .absoluteValue.coerceIn(0f, 1f)
                        alpha = 1f - 0.35f * off
                        val s = 1f - 0.04f * off
                        scaleX = s; scaleY = s
                    }
                    .verticalScroll(scrolls[page])
            ) {
                Spacer(Modifier.height(topInset + Spacing.sm))
                AdaptiveContainer {
                    when (page) {
                        0 -> HomeFeed(nav, vm, active, allTrips, following, profileComplete, update) { goTo(3) }
                        1 -> JourneysSection(nav, active, allTrips) { deleteTarget = it }
                        2 -> PeopleSection(nav, vm, following, profileComplete) { goTo(3) }
                        else -> SettingsTab(onProfileChanged = { profileVersion++ })
                    }
                }
                Spacer(Modifier.height(Spacing.scrollBottom))
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
                    KoodeFab(
                        label = fabLabel,
                        icon = fabIcon,
                        expanded = barOffset > -barPx / 2,
                        enabled = fabEnabled,
                        onClick = onFab,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = windowClass.gutter, bottom = Spacing.lg)
                    )
                }
                KoodeBottomBar(tabs, pager.currentPage, ::selectTab)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                topBar(Modifier)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    KoodeNavRail(tabs, pager.currentPage, ::selectTab) {
                        KoodeFab(
                            label = fabLabel,
                            icon = fabIcon,
                            expanded = false,
                            enabled = fabEnabled,
                            onClick = onFab,
                            shape = RoundedCornerShape(Radii.md)
                        )
                    }
                    pages(Modifier.weight(1f).fillMaxHeight(), 0.dp)
                }
            }
        }

        if (showWelcome) {
            WelcomeScreen(
                onGetStarted = {
                    Onboarding.markWelcomeSeen(context)
                    showWelcome = false
                    goTo(3)
                },
                onSkip = {
                    Onboarding.markWelcomeSeen(context)
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

// ---------------------------------------------------------------------------
// 🏠 Home
// ---------------------------------------------------------------------------

/** One person Home can put on its map: this phone's traveller, or someone followed. */
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

/** A fix older than this is shown as "last seen", never as live. */
private const val LIVE_FRESH_MS = 5 * 60_000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeFeed(
    nav: NavHostController,
    vm: HomeVm,
    active: ActiveTripEntity?,
    allTrips: List<ActiveTripEntity>,
    following: List<ViewerTripEntity>,
    profileComplete: Boolean,
    update: com.trippulse.app.data.update.UpdateChecker.Available?,
    goToSettings: () -> Unit
) {
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val name = vm.greetingName()
    val now = System.currentTimeMillis()
    val myState by vm.activeState.collectAsStateWithLifecycle()
    val journeyFollowers by vm.journeyFollowers.collectAsStateWithLifecycle()

    val live = following.filter { !it.expired }
    val statuses = live.associate { it.accessKey to vm.followStatus(it.accessKey) }
    val snapshots = following.associate { it.accessKey to vm.snapshot(it.accessKey) }
    fun personName(v: ViewerTripEntity) = snapshots[v.accessKey]?.ownerName ?: v.label

    // ---- who can be shown on the map ------------------------------------------
    val subjects = buildList {
        val st = myState
        if (active != null && active.status == "ACTIVE" && st?.lat != null && st.lng != null) {
            add(
                MapSubject(
                    key = "me", name = name.ifBlank { "You" },
                    current = GeoPoint(st.lat!!, st.lng!!),
                    origin = GeoPoint(active.originLat, active.originLng),
                    destination = GeoPoint(active.destLat, active.destLng),
                    destinationName = active.destName,
                    remainingM = st.distanceRemainingM, etaMs = st.etaLikelyMs,
                    lastFixMs = st.lastLocationAtMs, mode = active.transportMode
                )
            )
        }
        live.forEach { v ->
            val snap = snapshots[v.accessKey] ?: return@forEach
            if (!snap.hasPosition) return@forEach
            add(
                MapSubject(
                    key = v.accessKey, name = personName(v),
                    current = GeoPoint(snap.lat!!, snap.lng!!),
                    origin = if (snap.originLat != null && snap.originLng != null) GeoPoint(snap.originLat, snap.originLng) else null,
                    destination = if (snap.destLat != null && snap.destLng != null) GeoPoint(snap.destLat, snap.destLng) else null,
                    destinationName = snap.destination,
                    remainingM = snap.distanceRemainingM, etaMs = snap.etaLikelyMs,
                    lastFixMs = snap.lastLocationAtMs, mode = snap.mode
                )
            )
        }
    }
    var featuredKey by rememberSaveable { mutableStateOf<String?>(null) }
    val featured = subjects.firstOrNull { it.key == featuredKey } ?: subjects.firstOrNull()

    // ---- one line about the journey that is under way, if any ---------------
    // Home is journey-centric: it speaks about live journeys, never about
    // where people are when nobody is travelling.
    val concern = live.any {
        statuses[it.accessKey]?.level == "CONCERN" || snapshots[it.accessKey]?.sosActive == true
    }
    val myLive = active != null && active.status == "ACTIVE"
    val headline = when {
        concern -> "A journey needs a look"
        myLive -> "You're on a journey"
        live.size == 1 -> "${personName(live.first()).substringBefore(' ')} is on the way"
        live.size > 1 -> "${live.size} journeys under way"
        else -> "Ready for your next journey?"
    }
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (name.isNotBlank()) "$greeting, $name" else greeting,
                color = colors.textMid, style = MaterialTheme.typography.bodyLarge
            )
            Text(
                headline,
                color = if (concern) colors.danger else colors.textHigh,
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }

    // ---- update nudge -----------------------------------------------------
    AnimatedBanner(visible = update != null) {
        update?.let { u ->
            KoodeCard(accent = colors.traveller) {
                Text(
                    "Koode ${u.versionName} is available",
                    color = colors.traveller,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Updating never affects a journey in progress — yours or one you're watching.",
                    color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(Modifier.weight(1f)) {
                        PrimaryButton("Download", { uriHandler.openUri(u.downloadUrl) }, height = 44.dp)
                    }
                    Box(Modifier.weight(1f)) {
                        SecondaryButton("Not now", { vm.dismissUpdate() }, height = 44.dp)
                    }
                }
            }
        }
    }

    if (!profileComplete) {
        KoodeCard(accent = colors.warn, onClick = goToSettings) {
            Text("Finish setting up Koode", color = colors.warn, style = MaterialTheme.typography.titleMedium)
            Profile.missing(context).forEach {
                Text("• $it", color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    // ---- a closed journey waiting for the traveller's approval -------------------
    allTrips.filter { it.status == "COMPLETED" && vm.awaitingReview(it.tripId) }.forEach { t ->
        KoodeHeroCard(accent = colors.accent, onClick = { nav.navigate(Routes.summary(t.tripId)) }) {
            Text("Review your journey", color = colors.accent, style = MaterialTheme.typography.titleMedium)
            Text("${t.originName} → ${t.destName}", color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
            Text(
                "It's shared with the people following it only once you approve it.",
                color = colors.textMid, style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    // ---- the live map ---------------------------------------------------------
    if (featured != null) {
        Box(Modifier.fillMaxWidth()) {
            JourneyMap(
                current = featured.current,
                origin = featured.origin,
                destination = featured.destination,
                live = true,
                showPlayControl = false,
                height = 320.dp,
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
                        val selected = sub.key == featured.key
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClickLabel = "Show ${sub.name} on the map") { featuredKey = sub.key }
                        ) {
                            PersonAvatar(sub.name, 32.dp, ring = if (selected) colors.accent else null)
                        }
                    }
                }
            }
            val fresh = featured.lastFixMs != null && now - featured.lastFixMs < LIVE_FRESH_MS
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(Spacing.md)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.backgroundElevated.copy(alpha = 0.95f))
                    .clickable {
                        if (featured.key == "me" && active != null) nav.navigate(Routes.driver(active.tripId))
                        else nav.navigate(Routes.viewer(featured.key))
                    }
                    .padding(horizontal = Spacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PersonAvatar(featured.name, 36.dp)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        featured.destinationName?.let { "${featured.name} · to $it" } ?: featured.name,
                        color = colors.textHigh, style = MaterialTheme.typography.titleMedium, maxLines = 1
                    )
                    val detail = when {
                        !fresh && featured.lastFixMs != null -> "Last seen ${TimeFmt.ago(now, featured.lastFixMs)}"
                        else -> listOfNotNull(
                            featured.remainingM?.takeIf { it > 0 }?.let { "${vm.distance(it)} left" },
                            featured.etaMs?.let { "ETA ${TimeFmt.clockWithDay(it, now)}" }
                        ).joinToString(" · ").ifBlank { "On the way" }
                    }
                    Text(detail, color = colors.textMid, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                if (fresh) {
                    Spacer(Modifier.width(Spacing.sm))
                    StatusPill("LIVE", colors.accent, pulsing = true)
                }
            }
        }
    }

    // ---- my own journey when it isn't on the map (scheduled / waiting for a fix)
    if (active != null && featured?.key != "me" && subjects.none { it.key == "me" }) {
        val scheduled = active.status == "CREATED" && (active.plannedDepartureMs ?: 0) > now
        KoodeHeroCard(
            accent = if (scheduled) colors.warn else colors.accent,
            onClick = {
                if (active.status == "CREATED") nav.navigate(Routes.credentials(active.tripId))
                else nav.navigate(Routes.driver(active.tripId))
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!scheduled) {
                    PulsingDot(colors.accent, size = 8.dp)
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(
                    if (scheduled) "Your scheduled journey" else "Your journey is live",
                    color = if (scheduled) colors.warn else colors.accent,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "${active.originName} → ${active.destName}",
                color = colors.textHigh,
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                if (scheduled) "Departs ${TimeFmt.clockWithDay(active.plannedDepartureMs!!, now)}"
                else "Waiting for the first location fix · tap to open",
                color = colors.textMid, style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    // ---- my live journey: the people following it --------------------------------
    if (active != null && active.status == "ACTIVE") {
        Spacer(Modifier.height(Spacing.xs))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "People following this journey", color = colors.textHigh,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                scope.launch {
                    vm.shareText(active.tripId, includePasscode = true)?.let { text ->
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
                "No one is following this journey yet. Share it with the people you'd like to keep informed.",
                color = colors.textMid, style = MaterialTheme.typography.bodyMedium
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                journeyFollowers.forEach { n ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(Radii.pill))
                            .background(colors.backgroundElevated)
                            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PersonAvatar(n, 28.dp)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(n, color = colors.textHigh, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    // ---- journeys shared with me, only while they are live ------------------------
    if (live.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.xs))
        Text("Journeys shared with you", color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.lg))
                .background(colors.backgroundElevated)
        ) {
            live.forEachIndexed { index, v ->
                val st = statuses[v.accessKey]
                val snap = snapshots[v.accessKey]
                val waitingSignal = v.unreachableSinceMs != null
                val sos = snap?.sosActive == true
                val (status, tint) = when {
                    sos -> "SOS" to colors.danger
                    waitingSignal -> "Waiting" to colors.warn
                    st?.level == "CONCERN" -> "Needs a look" to colors.danger
                    st?.level == "ATTENTION" -> "Keep an eye" to colors.warn
                    else -> "On the way" to colors.accent
                }
                val subtitle = when {
                    waitingSignal -> "Last heard ${TimeFmt.ago(now, v.lastSeenAtMs ?: v.joinedAtMs)} — about the signal, not them"
                    st != null && st.reason.isNotBlank() -> st.reason
                    snap?.destination != null -> "On the way to ${snap.destination}"
                    else -> st?.headline ?: v.label
                }
                CircleRow(
                    name = personName(v),
                    title = personName(v),
                    subtitle = subtitle,
                    status = status,
                    statusColor = tint,
                    ring = if (!waitingSignal) tint else null,
                    divider = index > 0,
                    onClick = { nav.navigate(Routes.viewer(v.accessKey)) }
                )
            }
        }
    }

    // ---- nothing under way: ready for the next journey ------------------------------
    if (active == null && live.isEmpty()) {
        KoodeHeroCard(accent = colors.accent) {
            Text("Ready for your next journey?", color = colors.textHigh, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "Koode looks after you on the way and keeps the people you choose informed — only while the journey lasts.",
                color = colors.textMid, style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(Spacing.md))
            PrimaryButton(
                "Start a journey",
                { if (profileComplete) nav.navigate(Routes.CREATE) else goToSettings() },
                height = 48.dp
            )
        }
        val recent = allTrips.filter { it.status == "COMPLETED" }
            .sortedByDescending { it.completedAtMs ?: it.createdAtMs }
            .take(3)
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            Text("Recent journeys", color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.lg))
                    .background(colors.backgroundElevated)
            ) {
                recent.forEachIndexed { index, t ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().padding(start = Spacing.lg).height(1.dp).background(colors.surfaceRaised))
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav.navigate(Routes.summary(t.tripId)) }
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${t.originName} → ${t.destName}", color = colors.textHigh, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                "${relativeDay(t.completedAtMs ?: t.createdAtMs, now)} · " +
                                    (if (vm.awaitingReview(t.tripId)) "Awaiting your review" else "Completed"),
                                color = colors.textMid, style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Icon(KoodeIcons.Chevron, contentDescription = null, tint = colors.textMid, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

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

/** One person in the circle list: avatar, what they're doing, and a status word. */
@Composable
private fun CircleRow(
    name: String,
    title: String,
    subtitle: String,
    status: String,
    statusColor: Color,
    ring: Color?,
    divider: Boolean,
    onClick: () -> Unit
) {
    val colors = KoodeTheme.colors
    Column {
        if (divider) {
            Box(Modifier.fillMaxWidth().padding(start = 72.dp).height(1.dp).background(colors.surfaceRaised))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PersonAvatar(name, 40.dp, ring = ring)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.textHigh, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(subtitle, color = colors.textMid, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
            Spacer(Modifier.width(Spacing.sm))
            Text(status, color = statusColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---------------------------------------------------------------------------
// 🧭 Journeys
// ---------------------------------------------------------------------------

@Composable
private fun JourneysSection(
    nav: NavHostController,
    active: ActiveTripEntity?,
    allTrips: List<ActiveTripEntity>,
    onDelete: (String) -> Unit
) {
    val colors = KoodeTheme.colors
    val now = System.currentTimeMillis()
    com.trippulse.app.ui.components.CrashNotice()
    SectionHeader("Journeys")

    if (active != null) {
        val scheduled = active.status == "CREATED" && (active.plannedDepartureMs ?: 0) > now
        KoodeCard(
            accent = colors.accent,
            onClick = {
                if (active.status == "CREATED") nav.navigate(Routes.credentials(active.tripId))
                else nav.navigate(Routes.driver(active.tripId))
            }
        ) {
            StatusPill(if (scheduled) "SCHEDULED" else "ACTIVE NOW", colors.accent, pulsing = !scheduled)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "${active.originName} → ${active.destName}",
                color = colors.textHigh, style = MaterialTheme.typography.titleMedium
            )
            if (scheduled) {
                Text(
                    "Departs ${TimeFmt.clockWithDay(active.plannedDepartureMs!!, now)}",
                    color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    } else {
        Text(
            "No journey right now — use ＋ to start or schedule one.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
    }

    val history = allTrips.filter { it.status == "COMPLETED" || it.status == "EXPIRED" }
    if (history.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.sm))
        SectionHeader("History")
        Text(
            "Kept on this phone until you delete it. Open one to see its playback, timeline and costs — and to save them as a PDF.",
            color = colors.textLow, style = MaterialTheme.typography.bodySmall
        )
        AdaptiveGrid(history) { t, cell ->
            KoodeCard(modifier = cell, onClick = { nav.navigate(Routes.summary(t.tripId)) }) {
                Text(
                    "${t.originName} → ${t.destName}",
                    color = colors.textHigh, style = MaterialTheme.typography.titleSmall
                )
                Text(
                    TimeFmt.dateTime(t.completedAtMs ?: t.createdAtMs),
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(Modifier.weight(1f)) {
                        SecondaryButton("Open", { nav.navigate(Routes.summary(t.tripId)) }, height = 40.dp)
                    }
                    Box(Modifier.weight(1f)) {
                        SecondaryButton("Delete", { onDelete(t.tripId) }, accent = colors.danger, height = 40.dp)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 👥 People
// ---------------------------------------------------------------------------

@Composable
private fun PeopleSection(
    nav: NavHostController,
    vm: HomeVm,
    following: List<ViewerTripEntity>,
    profileComplete: Boolean,
    goToSettings: () -> Unit
) {
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    SectionHeader("People")

    Text("People you can share a journey with", color = colors.textMid, style = MaterialTheme.typography.titleMedium)
    val circle = Profile.contacts(context).filter { it.filled }
    if (circle.isEmpty()) {
        KoodeCard(accent = colors.warn, onClick = goToSettings) {
            Text("Add your emergency contacts", color = colors.warn, style = MaterialTheme.typography.titleSmall)
            Text(
                "They're approved automatically when they ask to follow one of your journeys.",
                color = colors.textMid, style = MaterialTheme.typography.bodyMedium
            )
        }
    } else {
        AdaptiveGrid(circle) { c, cell ->
            KoodeCard(modifier = cell) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(c.name, 40.dp)
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
                        Text(c.phone, color = colors.textLow, style = MaterialTheme.typography.bodySmall)
                    }
                    StatusPill("Trusted", colors.accent)
                }
            }
        }
        Text(
            "Trusted contacts are approved automatically when they join a journey with their name. Each journey is shared only while it lasts.",
            color = colors.textLow, style = MaterialTheme.typography.bodySmall
        )
    }

    Spacer(Modifier.height(Spacing.sm))
    Text("Journeys shared with you", color = colors.textMid, style = MaterialTheme.typography.titleMedium)
    SecondaryButton(
        "Follow a journey",
        { if (profileComplete) nav.navigate(Routes.JOIN) else goToSettings() },
        leading = "＋"
    )
    AdaptiveGrid(following) { v, cell ->
        KoodeCard(modifier = cell, onClick = { nav.navigate(Routes.viewer(v.accessKey)) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PersonAvatar(vm.snapshot(v.accessKey)?.ownerName ?: v.label, 40.dp)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(v.label, color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
                    Text(v.tripId, color = colors.textLow, style = MaterialTheme.typography.bodySmall)
                }
                HealthChip(
                    when {
                        v.expired -> "ENDED"
                        v.unreachableSinceMs != null -> "WAITING"
                        else -> vm.followHealth(v.accessKey)
                    }
                )
                Spacer(Modifier.width(Spacing.sm))
                TextButton(onClick = { vm.unfollow(v.accessKey) }) {
                    Text("Remove", color = colors.textLow, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Journey Health at a glance — the "Safe" chip from the brand banners. */
@Composable
private fun HealthChip(level: String) {
    val colors = KoodeTheme.colors
    val (label, color) = when (level) {
        "CONCERN" -> "Check now" to colors.danger
        "ATTENTION" -> "Attention" to colors.warn
        "ENDED" -> "Ended" to colors.textLow
        "WAITING" -> "Waiting" to colors.warn
        else -> "On the way" to colors.accent
    }
    StatusPill(label, color)
}
