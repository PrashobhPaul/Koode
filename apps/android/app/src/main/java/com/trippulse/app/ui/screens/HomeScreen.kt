package com.trippulse.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.ui.HomeVm
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.theme.Danger
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextMid

/**
 * Home with two roles in one app:
 *   MY TRIPS  — trips this phone owns: active, scheduled, and the private
 *               history that outlives the server's 30-min self-destruct.
 *   FOLLOWING — trips shared with this phone via a Trip ID.
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

    // Profile is the mandatory prerequisite: name + saved location + contacts.
    val context = androidx.compose.ui.platform.LocalContext.current
    var profileVersion by remember { mutableIntStateOf(0) }
    val profileComplete = remember(profileVersion, placeCount) {
        com.trippulse.app.core.Profile.isComplete(context, placeCount)
    }
    LaunchedEffect(Unit) {
        if (!com.trippulse.app.core.Profile.isComplete(context, placeCount)) tab = 2
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Koode", color = Teal, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(0.dp))
                Text("  ·  Always with you", color = TextMid, fontSize = 12.sp)
            }
            val name = vm.greetingName()
            Text(
                if (name.isNotBlank()) "Hi, $name 👋" else "Hi 👋",
                color = MaterialTheme.colorScheme.onSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold
            )
            Text("Here's what matters.", color = TextMid, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("My journeys") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Their journeys") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Settings") })
        }

        Column(
            Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (tab == 0) {
                // ---------------- MY TRIPS ----------------
                if (active != null) {
                    SectionCard {
                        val scheduled = active!!.status == "CREATED" &&
                            (active!!.plannedDepartureMs ?: 0) > System.currentTimeMillis()
                        Text(if (scheduled) "Scheduled trip" else "Active trip", color = TextMid, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${active!!.originName} → ${active!!.destName}",
                            color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                        )
                        if (scheduled) {
                            Text(
                                "Departs ${TimeFmt.clockWithDay(active!!.plannedDepartureMs!!, System.currentTimeMillis())}",
                                color = Teal, fontSize = 13.sp
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (active!!.status == "CREATED") nav.navigate(Routes.credentials(active!!.tripId))
                                else nav.navigate(Routes.driver(active!!.tripId))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Teal)
                        ) { Text(if (active!!.status == "CREATED") "Open / start trip" else "Resume trip", fontWeight = FontWeight.SemiBold) }
                    }
                }

                Button(
                    onClick = { if (profileComplete) nav.navigate(Routes.CREATE) else tab = 2 },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal)
                ) { Text("Start or schedule a journey", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                if (!profileComplete) {
                    Text("First, complete your profile in Settings (name, a saved location, emergency contacts).", color = TextMid, fontSize = 12.sp)
                }
                Text(
                    "Start your journey, then forget the app — it quietly keeps the people you love informed about your journey, wellbeing and arrival, so nobody has to call and ask.",
                    color = TextMid, fontSize = 12.sp
                )

                val history = allTrips.filter { it.status == "COMPLETED" || it.status == "EXPIRED" }
                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Trip history (kept on this phone until you delete it)", color = TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    history.forEach { t ->
                        SectionCard {
                            Text(
                                "${t.originName} → ${t.destName}",
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium
                            )
                            Text(
                                TimeFmt.clockWithDay(t.completedAtMs ?: t.createdAtMs, System.currentTimeMillis()),
                                color = TextMid, fontSize = 12.sp
                            )
                            Row {
                                TextButton(onClick = { nav.navigate(Routes.summary(t.tripId)) }) { Text("Summary", color = Teal, fontSize = 13.sp) }
                                TextButton(onClick = { nav.navigate(Routes.replay(t.tripId)) }) { Text("Replay", color = Teal, fontSize = 13.sp) }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { deleteTarget = t.tripId }) { Text("Delete", color = Danger, fontSize = 13.sp) }
                            }
                        }
                    }
                }
            } else if (tab == 1) {
                // ---------------- FOLLOWING ----------------
                OutlinedButton(
                    onClick = { if (profileComplete) nav.navigate(Routes.JOIN) else tab = 2 },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Follow a new journey", fontSize = 15.sp) }
                if (!profileComplete) {
                    Text("First, complete your profile in Settings.", color = TextMid, fontSize = 12.sp)
                }

                if (following.isEmpty()) {
                    Text(
                        "When someone shares a Journey ID with you, add it here. You'll see their journey health at a glance and get a quiet alert when they start, if something needs attention, and when they arrive safely.",
                        color = TextMid, fontSize = 13.sp
                    )
                }

                val live = following.filter { !it.expired }
                if (live.isNotEmpty() && live.all { vm.followHealth(it.accessKey) == "NORMAL" }) {
                    Text("Your people are safe ✅", color = Teal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }

                following.forEach { v ->
                    SectionCard(modifier = Modifier.clickable { nav.navigate(Routes.viewer(v.accessKey)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(v.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(v.tripId, color = TextMid, fontSize = 12.sp)
                            }
                            HealthChip(if (v.expired) "ENDED" else vm.followHealth(v.accessKey))
                            TextButton(onClick = { vm.unfollow(v.accessKey) }) { Text("Remove", color = TextMid, fontSize = 13.sp) }
                        }
                    }
                }
            } else {
                // ---------------- SETTINGS ----------------
                SettingsTab(onProfileChanged = { profileVersion++ })
            }

            Spacer(Modifier.height(6.dp))
            Text(
                if (vm.cloudAvailable) "Cloud sync: enabled — viewers can follow live."
                else "Running in local mode. Configure the free cloud backend (docs/SUPABASE_SETUP.md) to enable live viewer sharing.",
                color = TextMid, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
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
            title = { Text("Delete this trip from your phone?") },
            text = { Text("The route, timeline, replay and expense records for this trip will be permanently removed from this device. (The cloud copy already self-destructed 30 minutes after arrival.)") }
        )
    }
}

/** Journey Health at a glance — the "Safe" chip from the brand banners. */
@Composable
private fun HealthChip(level: String) {
    val (label, color) = when (level) {
        "CONCERN" -> "Check now" to Danger
        "ATTENTION" -> "Attention" to com.trippulse.app.ui.theme.Amber
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
