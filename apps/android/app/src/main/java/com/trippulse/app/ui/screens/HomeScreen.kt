package com.trippulse.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
import com.trippulse.app.ui.HomeVm
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.components.AdaptiveContainer
import com.trippulse.app.ui.components.EmptyState
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.KoodeHeroCard
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

    // A first-run traveller lands on setup, because nothing else works without it.
    LaunchedEffect(Unit) { if (!Profile.isComplete(context)) pager.scrollToPage(3) }

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
                            0 -> HomeFeed(nav, vm, active, following, profileComplete, update) { goTo(3) }
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
            StartJourneyFab(
                enabled = profileComplete,
                onClick = { if (profileComplete) nav.navigate(Routes.CREATE) else goTo(3) }
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

private data class TabSpec(val emoji: String, val label: String)

private val TABS = listOf(
    TabSpec("🏠", "Home"),
    TabSpec("🧭", "Journeys"),
    TabSpec("👥", "People"),
    TabSpec("⚙️", "More")
)

/**
 * The tab bar. Each item scales and tints on selection rather than simply
 * changing colour, so switching tabs feels like moving somewhere.
 */
@Composable
private fun KoodeTabBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = KoodeTheme.colors
    Row(
        modifier
            .background(colors.backgroundElevated)
            .navigationBarsPadding()
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TABS.forEachIndexed { index, tab ->
            val isSelected = index == selected
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1.06f else 1f,
                animationSpec = spring(dampingRatio = 0.55f), label = "tabScale"
            )
            val tint by animateColorAsState(
                targetValue = if (isSelected) colors.accent else colors.textLow,
                animationSpec = tween(Motion.normal), label = "tabTint"
            )
            Column(
                Modifier
                    .clip(RoundedCornerShape(Radii.md))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(index) }
                    .padding(horizontal = Spacing.lg, vertical = 6.dp)
                    .scale(scale),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(tab.emoji, fontSize = 17.sp)
                Spacer(Modifier.height(2.dp))
                Text(tab.label, color = tint, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .width(if (isSelected) 16.dp else 0.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(Radii.pill))
                        .background(if (isSelected) colors.accent else Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun StartJourneyFab(enabled: Boolean, onClick: () -> Unit) {
    val colors = KoodeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .clip(RoundedCornerShape(Radii.pill))
            .background(if (enabled) colors.accent else colors.surfaceRaised)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("＋", fontSize = 17.sp, color = if (colors.isDark) Color(0xFF07131D) else Color.White)
        Spacer(Modifier.width(Spacing.sm))
        Text(
            "Start Journey",
            color = if (colors.isDark) Color(0xFF07131D) else Color.White,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

// ---------------------------------------------------------------------------
// 🏠 Home
// ---------------------------------------------------------------------------

@Composable
private fun HomeFeed(
    nav: NavHostController,
    vm: HomeVm,
    active: ActiveTripEntity?,
    following: List<ViewerTripEntity>,
    profileComplete: Boolean,
    update: com.trippulse.app.data.update.UpdateChecker.Available?,
    goToSettings: () -> Unit
) {
    val colors = KoodeTheme.colors
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val name = vm.greetingName()
    val now = System.currentTimeMillis()

    Text(
        if (name.isNotBlank()) "Hi, $name 👋" else "Hi 👋",
        color = colors.textHigh,
        style = MaterialTheme.typography.displaySmall
    )
    Text("Here's what matters.", color = colors.textMid, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(Spacing.xs))

    // ---- update nudge -----------------------------------------------------
    AnimatedBanner(visible = update != null) {
        update?.let { u ->
            KoodeCard(accent = colors.traveller) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⬆️", fontSize = 18.sp)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        "Koode ${u.versionName} is available",
                        color = colors.traveller,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
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

    // ---- my own journey, first ---------------------------------------------
    if (active != null) {
        val scheduled = active.status == "CREATED" && (active.plannedDepartureMs ?: 0) > now
        KoodeHeroCard(
            accent = if (scheduled) colors.warn else colors.accent,
            onClick = {
                if (active.status == "CREATED") nav.navigate(Routes.credentials(active.tripId))
                else nav.navigate(Routes.driver(active.tripId))
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scheduled) {
                    Text("🕐", fontSize = 16.sp)
                    Spacer(Modifier.width(Spacing.sm))
                } else {
                    PulsingDot(colors.accent, size = 8.dp)
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
                if (scheduled)
                    "Departs ${TimeFmt.clockWithDay(active.plannedDepartureMs!!, now)}"
                else "Tap to open",
                color = colors.textMid, style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    // ---- the people I follow ------------------------------------------------
    val live = following.filter { !it.expired }
    if (live.size > 1 && live.all { vm.followHealth(it.accessKey) == "NORMAL" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✅", fontSize = 15.sp)
            Spacer(Modifier.width(Spacing.sm))
            Text("Your people are safe", color = colors.accent, style = MaterialTheme.typography.titleMedium)
        }
    }

    following.forEach { v ->
        val s = vm.followStatus(v.accessKey)
        // "Ended" appears here for exactly one reason: the traveller ended it.
        // Everything else — no signal, a server we can't reach — is "waiting".
        val ended = v.expired
        val waiting = !ended && v.unreachableSinceMs != null
        val (emoji, title, tint) = when {
            ended -> Triple("🏁", "Journey ended", colors.textMid)
            waiting -> Triple("📡", "Waiting for updates", colors.warn)
            s.level == "CONCERN" -> Triple("🔴", "Needs attention", colors.danger)
            s.level == "ATTENTION" -> Triple("🟡", "Keep an eye", colors.warn)
            else -> Triple("🟢", "All good", colors.accent)
        }
        KoodeCard(accent = tint, onClick = { nav.navigate(Routes.viewer(v.accessKey)) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 15.sp)
                Spacer(Modifier.width(Spacing.sm))
                Text(title, color = tint, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (!ended && !waiting && s.level == "NORMAL") {
                    StatusPill("LIVE", colors.accent, pulsing = true)
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(v.label, color = colors.textHigh, style = MaterialTheme.typography.bodyLarge)
            val meta = when {
                ended -> v.endedAtMs?.let { "Ended ${TimeFmt.ago(now, it)}" }
                waiting -> "Last heard ${TimeFmt.ago(now, v.lastSeenAtMs ?: v.joinedAtMs)} — " +
                    "this is about the signal, not about them."
                else -> buildString {
                    if (s.reason.isNotBlank()) append("${s.reason} · ")
                    s.updatedAtMs?.let { append("Updated ${TimeFmt.ago(now, it)}") }
                }.ifBlank { s.headline }
            }
            if (!meta.isNullOrBlank()) {
                Text(meta, color = colors.textLow, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (active == null && following.isEmpty()) {
        EmptyState(
            emoji = "🧭",
            title = "Nothing here yet",
            body = "Start a journey with ＋, or follow someone from the People tab when they share their journey number with you."
        )
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

    Text("Your circle", color = colors.textMid, style = MaterialTheme.typography.titleMedium)
    val circle = Profile.contacts(context).filter { it.filled }
    if (circle.isEmpty()) {
        KoodeCard(accent = colors.warn, onClick = goToSettings) {
            Text("Add your emergency contacts", color = colors.warn, style = MaterialTheme.typography.titleSmall)
            Text(
                "They become your circle — your journeys are shared with them by default.",
                color = colors.textMid, style = MaterialTheme.typography.bodyMedium
            )
        }
    } else {
        circle.forEach { c ->
            KoodeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
                        Text(c.phone, color = colors.textLow, style = MaterialTheme.typography.bodySmall)
                    }
                    StatusPill("In your circle", colors.accent)
                }
            }
        }
        Text(
            "Circle members are approved automatically when they join your journey with their name.",
            color = colors.textLow, style = MaterialTheme.typography.bodySmall
        )
    }

    Spacer(Modifier.height(Spacing.sm))
    Text("You follow", color = colors.textMid, style = MaterialTheme.typography.titleMedium)
    SecondaryButton(
        "Follow a journey",
        { if (profileComplete) nav.navigate(Routes.JOIN) else goToSettings() },
        leading = "＋"
    )
    following.forEach { v ->
        KoodeCard(onClick = { nav.navigate(Routes.viewer(v.accessKey)) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
        else -> "Safe" to colors.accent
    }
    StatusPill(label, color)
}
