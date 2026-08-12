package com.trippulse.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.core.Profile
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.ui.HomeVm
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.theme.Amber
import com.trippulse.app.ui.theme.Danger
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextHigh
import com.trippulse.app.ui.theme.TextMid

/**
 * The Koode shell: four simple destinations plus one primary action.
 *
 *   🏠 Home      everyone's status at a glance (Koode Status)
 *   🧭 Journeys  my active / scheduled journeys + history
 *   👥 People    the circle — who I share with, who I follow
 *   ⚙️ More      places, emergency contacts, privacy, settings
 *   ＋ Start Journey (floating action)
 */
@Composable
fun HomeScreen(nav: NavHostController) {
    val vm: HomeVm = viewModel(factory = HomeVm.Factory)
    val active by vm.activeTrip.collectAsStateWithLifecycle()
    val allTrips by vm.allTrips.collectAsStateWithLifecycle()
    val following by vm.following.collectAsStateWithLifecycle()
    val placeCount by vm.savedPlaceCount.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    var profileVersion by remember { mutableIntStateOf(0) }
    val profileComplete = remember(profileVersion, placeCount) {
        Profile.isComplete(context)
    }
    LaunchedEffect(Unit) { if (!Profile.isComplete(context)) tab = 3 }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text("🏠", fontSize = 18.sp) }, label = { Text("Home", fontSize = 11.sp) })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Text("🧭", fontSize = 18.sp) }, label = { Text("Journeys", fontSize = 11.sp) })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Text("👥", fontSize = 18.sp) }, label = { Text("People", fontSize = 11.sp) })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                    icon = { Text("⚙️", fontSize = 18.sp) }, label = { Text("More", fontSize = 11.sp) })
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (profileComplete) nav.navigate(Routes.CREATE) else tab = 3 },
                containerColor = Teal
            ) { Text("＋ Start Journey", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            when (tab) {
                0 -> HomeFeed(nav, vm, active, following, profileComplete) { tab = 3 }
                1 -> JourneysSection(nav, vm, active, allTrips) { deleteTarget = it }
                2 -> PeopleSection(nav, vm, following, profileComplete) { tab = 3 }
                else -> SettingsTab(onProfileChanged = { profileVersion++ })
            }
            Spacer(Modifier.height(80.dp)) // keep content clear of the FAB
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTrip(deleteTarget!!)
                    deleteTarget = null
                }) { Text("Delete forever", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Keep") } },
            title = { Text("Delete this journey from your phone?") },
            text = { Text("The route, timeline, replay and expense records will be permanently removed from this device.") }
        )
    }
}

// ---------------------------------------------------------------------------
// 🏠 Home — Koode Status: everyone, humanized, at a glance
// ---------------------------------------------------------------------------

@Composable
private fun HomeFeed(
    nav: NavHostController,
    vm: HomeVm,
    active: com.trippulse.app.data.local.ActiveTripEntity?,
    following: List<com.trippulse.app.data.local.ViewerTripEntity>,
    profileComplete: Boolean,
    goToSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val name = vm.greetingName()
    Text(if (name.isNotBlank()) "Hi, $name 👋" else "Hi 👋", color = TextHigh, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text("Here's what matters.", color = TextMid, fontSize = 13.sp)
    Spacer(Modifier.height(2.dp))

    if (!profileComplete) {
        SectionCard(modifier = Modifier.clickable { goToSettings() }) {
            Text("Finish setting up Koode", color = Amber, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Profile.missing(context).forEach { Text("• $it", color = TextMid, fontSize = 13.sp) }
        }
    }

    // my own journey first
    if (active != null) {
        val scheduled = active.status == "CREATED" && (active.plannedDepartureMs ?: 0) > System.currentTimeMillis()
        StatusCard(
            emoji = if (scheduled) "🕐" else "🟢",
            title = if (scheduled) "Your scheduled journey" else "Your journey is live",
            line = "${active.originName} → ${active.destName}",
            meta = if (scheduled) "Departs ${TimeFmt.clockWithDay(active.plannedDepartureMs!!, System.currentTimeMillis())}"
                else "Tap to open",
            color = Teal,
            onClick = {
                if (active.status == "CREATED") nav.navigate(Routes.credentials(active.tripId))
                else nav.navigate(Routes.driver(active.tripId))
            }
        )
    }

    // then everyone I follow, humanized
    val live = following.filter { !it.expired }
    if (live.isNotEmpty() && live.all { vm.followHealth(it.accessKey) == "NORMAL" }) {
        Text("Your people are safe ✅", color = Teal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
    following.forEach { v ->
        val s = vm.followStatus(v.accessKey)
        val (emoji, title, color) = when {
            v.expired -> Triple("🏁", "Journey ended", TextMid)
            s.level == "CONCERN" -> Triple("🔴", "Attention", Danger)
            s.level == "ATTENTION" -> Triple("🟡", "Keep an eye", Amber)
            else -> Triple("🟢", "All good", Teal)
        }
        StatusCard(
            emoji = emoji, title = title,
            line = if (v.expired) v.label else "${v.label} — ${s.headline}",
            meta = buildString {
                if (s.reason.isNotBlank() && !v.expired) append("${s.reason} · ")
                s.updatedAtMs?.let { append("Updated ${TimeFmt.ago(System.currentTimeMillis(), it)}") }
            }.ifBlank { null },
            color = color,
            onClick = { nav.navigate(Routes.viewer(v.accessKey)) }
        )
    }

    if (active == null && following.isEmpty()) {
        SectionCard {
            Text("Nothing here yet", color = TextHigh, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Start a journey with ＋, or follow someone from the People tab when they share a Journey ID with you.",
                color = TextMid, fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun StatusCard(
    emoji: String, title: String, line: String, meta: String?,
    color: androidx.compose.ui.graphics.Color, onClick: () -> Unit
) {
    SectionCard(modifier = Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 18.sp)
            Spacer(Modifier.height(0.dp))
            Text("  $title", color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(line, color = TextHigh, fontSize = 14.sp)
        if (meta != null) Text(meta, color = TextMid, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------------------
// 🧭 Journeys — mine: active, scheduled, history
// ---------------------------------------------------------------------------

@Composable
private fun JourneysSection(
    nav: NavHostController,
    vm: HomeVm,
    active: com.trippulse.app.data.local.ActiveTripEntity?,
    allTrips: List<com.trippulse.app.data.local.ActiveTripEntity>,
    onDelete: (String) -> Unit
) {
    Text("Journeys", color = TextHigh, fontSize = 28.sp, fontWeight = FontWeight.Bold)

    if (active != null) {
        val scheduled = active.status == "CREATED" && (active.plannedDepartureMs ?: 0) > System.currentTimeMillis()
        SectionCard(modifier = Modifier.clickable {
            if (active.status == "CREATED") nav.navigate(Routes.credentials(active.tripId))
            else nav.navigate(Routes.driver(active.tripId))
        }) {
            Text(if (scheduled) "Scheduled" else "Active now", color = Teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("${active.originName} → ${active.destName}", color = TextHigh, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (scheduled) {
                Text("Departs ${TimeFmt.clockWithDay(active.plannedDepartureMs!!, System.currentTimeMillis())}", color = TextMid, fontSize = 12.sp)
            }
        }
    } else {
        Text("No journey right now — use ＋ to start or schedule one.", color = TextMid, fontSize = 13.sp)
    }

    val history = allTrips.filter { it.status == "COMPLETED" || it.status == "EXPIRED" }
    if (history.isNotEmpty()) {
        Text("History", color = TextMid, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Kept on this phone until you delete it.", color = TextMid, fontSize = 11.sp)
        history.forEach { t ->
            SectionCard {
                Text("${t.originName} → ${t.destName}", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(TimeFmt.clockWithDay(t.completedAtMs ?: t.createdAtMs, System.currentTimeMillis()), color = TextMid, fontSize = 11.sp)
                Row {
                    TextButton(onClick = { nav.navigate(Routes.summary(t.tripId)) }) { Text("Summary", color = Teal, fontSize = 13.sp) }
                    TextButton(onClick = { nav.navigate(Routes.replay(t.tripId)) }) { Text("Replay", color = Teal, fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onDelete(t.tripId) }) { Text("Delete", color = Danger, fontSize = 13.sp) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 👥 People — the circle
// ---------------------------------------------------------------------------

@Composable
private fun PeopleSection(
    nav: NavHostController,
    vm: HomeVm,
    following: List<com.trippulse.app.data.local.ViewerTripEntity>,
    profileComplete: Boolean,
    goToSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Text("People", color = TextHigh, fontSize = 28.sp, fontWeight = FontWeight.Bold)

    Text("Your circle", color = TextMid, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val circle = Profile.contacts(context).filter { it.filled }
    if (circle.isEmpty()) {
        SectionCard(modifier = Modifier.clickable { goToSettings() }) {
            Text("Add your emergency contacts", color = Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("They become your circle — your journeys are shared with them by default.", color = TextMid, fontSize = 12.sp)
        }
    } else {
        circle.forEach { c ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = TextHigh, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(c.phone, color = TextMid, fontSize = 12.sp)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(Teal.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text("In your circle", color = Teal, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        Text(
            "Circle members are approved automatically when they join your journey with their name.",
            color = TextMid, fontSize = 11.sp
        )
    }

    Spacer(Modifier.height(4.dp))
    Text("You follow", color = TextMid, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    androidx.compose.material3.OutlinedButton(
        onClick = { if (profileComplete) nav.navigate(Routes.JOIN) else goToSettings() },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Follow a new journey", fontSize = 14.sp) }
    following.forEach { v ->
        SectionCard(modifier = Modifier.clickable { nav.navigate(Routes.viewer(v.accessKey)) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(v.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(v.tripId, color = TextMid, fontSize = 11.sp)
                }
                HealthChip(if (v.expired) "ENDED" else vm.followHealth(v.accessKey))
                TextButton(onClick = { vm.unfollow(v.accessKey) }) { Text("Remove", color = TextMid, fontSize = 13.sp) }
            }
        }
    }
}

/** Journey Health at a glance — the "Safe" chip from the brand banners. */
@Composable
private fun HealthChip(level: String) {
    val (label, color) = when (level) {
        "CONCERN" -> "Check now" to Danger
        "ATTENTION" -> "Attention" to Amber
        "ENDED" -> "Ended" to TextMid
        else -> "Safe" to Teal
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
