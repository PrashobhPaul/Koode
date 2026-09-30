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
import com.trippulse.app.ui.components.KoodeMarkTile
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

/**
 * The Koode shell: four destinations and one primary action.
 *
 *   🏠 Home      what matters right now
 *   🧭 Journeys  mine — active, scheduled, past
 *   👥 People    my circle, and who I follow
 *   ⚙️ More      places, contacts, behaviour, privacy
 *
 * Tabs are a pager, so they can be swiped as well as tapped — Android users
 * reach for the gesture first, and a tab bar that only responds to taps feels
 * like a web page rather than an app.
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

    val pager = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<String?>(null) }

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

    fun goTo(page: Int) = scope.launch { pager.animateScrollToPage(page) }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 1
            ) { page ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .statusBarsPadding()
                ) {
                    Spacer(Modifier.height(Spacing.md))
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
            KoodeTabBar(
                selected = pager.currentPage,
                onSelect = { goTo(it) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // The one primary action, floating clear of the tab bar.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = Spacing.xl, bottom = 96.dp)
                .navigationBarsPadding()
        ) {
            // One journey at a time. While one is running this is the way
            // back into it rather than the way to start another: two live
            // journeys would mean two claims about where one person is, and
            // whoever is following would have no way to know which is true.
            val live = active
            StartJourneyFab(
                enabled = profileComplete,
                live = live != null,
                onClick = {
                    when {
                        live != null -> nav.navigate(Routes.driver(live.tripId))
                        profileComplete -> nav.navigate(Routes.CREATE)
                        else -> goTo(3)
                    }
                }
            )
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

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(onClick = {
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

// ---------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------

private data class TabSpec(val icon: ImageVector, val label: String)

private val TABS = listOf(
    TabSpec(KoodeIcons.Home, "Home"),
    TabSpec(KoodeIcons.Journeys, "Journeys"),
    TabSpec(KoodeIcons.Circle, "People"),
    TabSpec(KoodeIcons.More, "More")
)

/**
 * The tab bar: Koode's own line icons, the selected tab sitting on a soft
 * accent pill. Emoji were dropped here because they render differently on
 * every phone and made the app's most-seen surface look unfinished.
 */
@Composable
private fun KoodeTabBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = KoodeTheme.colors
    Column(modifier.background(colors.backgroundElevated)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.surfaceRaised))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TABS.forEachIndexed { index, tab ->
                val isSelected = index == selected
                val tint by animateColorAsState(
                    targetValue = if (isSelected) colors.accent else colors.textMid,
                    animationSpec = tween(Motion.normal), label = "tabTint"
                )
                val pill by animateColorAsState(
                    targetValue = if (isSelected) colors.accent.copy(alpha = 0.14f) else Color.Transparent,
                    animationSpec = tween(Motion.normal), label = "tabPill"
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radii.md))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab
                        ) { onSelect(index) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(Radii.pill))
                            .background(pill)
                            .padding(horizontal = 18.dp, vertical = 4.dp)
                    ) {
                        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tab.label, color = tint,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 0.2.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StartJourneyFab(enabled: Boolean, live: Boolean, onClick: () -> Unit) {
    val colors = KoodeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val ink = if (colors.isDark) Color(0xFF07131D) else Color.White
    Row(
        Modifier
            .clip(RoundedCornerShape(Radii.pill))
            .background(if (enabled || live) colors.accent else colors.surfaceRaised)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (live) PulsingDot(ink, size = 8.dp)
        else Icon(KoodeIcons.Plus, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(
            if (live) "Your journey" else "Start a journey",
            color = ink,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

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
        Spacer(Modifier.width(Spacing.md))
        // The mark is the hidden door to About: tapping the logo opens it.
        KoodeMarkTile(
            size = 44.dp,
            contentDescription = "About Koode",
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable { nav.navigate(Routes.ABOUT) }
        )
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
        history.forEach { t ->
            KoodeCard(onClick = { nav.navigate(Routes.summary(t.tripId)) }) {
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
        circle.forEach { c ->
            KoodeCard {
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
    following.forEach { v ->
        KoodeCard(onClick = { nav.navigate(Routes.viewer(v.accessKey)) }) {
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
